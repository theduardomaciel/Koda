package com.ivor.ivormusic.data.youtube

import com.ivor.ivormusic.data.ArtistItem
import com.ivor.ivormusic.data.MusicMetadata
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.SessionManager
import com.ivor.ivormusic.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The signed-in account's YouTube Music library: the albums saved to it, the
 * artists followed on it, and the writes that add and remove each - a saved
 * album, a liked song, a followed artist.
 *
 * Reads are account browse ids, so signed out they answer a valid sign-in
 * shell rather than an error - every call therefore gates on `isLoggedIn()`
 * and returns empty rather than presenting the shell as an empty library. The
 * writes gate the same way, because YouTube's write endpoints answer 200 to
 * requests they did not act on and a caller cannot tell from the status code.
 *
 * Both pages are walked through their continuation chain, one page per call
 * boundary, with a seen-token guard so a repeated cursor cannot loop. Verified
 * signed in on music.youtube.com (WEB_REMIX), October 2026; the shapes and the
 * write targets are written up in `docs/youtube-data.md`.
 */
internal class MusicLibrary(
    private val musicApi: MusicApi,
    private val sessionManager: SessionManager,
) {
    /**
     * The albums saved to the account's library. Empty signed out or when the
     * fetch fails; distinct by browse id, in the order YouTube serves them
     * (most recently saved first).
     */
    suspend fun getSavedAlbums(): List<PlaylistDisplayItem> =
        getSavedAlbumsOrNull() ?: emptyList()

    /**
     * As [getSavedAlbums], but `null` means the first page could not be
     * fetched. Callers holding a cached list use that to keep it rather than
     * replacing it with an empty result; a genuinely empty page still answers
     * `emptyList()`.
     *
     * A continuation failure after a good first page returns the partial list:
     * the first page is what the screen needs, and a truncated list is closer
     * to the truth than no list at all.
     *
     * [onPage] receives the deduplicated list after each page, so a large
     * library can be drawn while the rest is still arriving. It runs on IO.
     */
    suspend fun getSavedAlbumsOrNull(
        onPage: (List<PlaylistDisplayItem>) -> Unit = {},
    ): List<PlaylistDisplayItem>? = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) return@withContext emptyList()
        try {
            val albums = mutableListOf<PlaylistDisplayItem>()
            val seenTokens = mutableSetOf<String>()
            var json = musicApi.browseMusic(SAVED_ALBUMS_BROWSE_ID)
                ?: return@withContext null
            var pages = 0
            while (json.isNotBlank() && pages < MAX_PAGES) {
                albums += parseSavedAlbums(json)
                onPage(albums.distinctBy { it.id })
                pages++
                val token = extractGridContinuationToken(json) ?: break
                if (!seenTokens.add(token)) {
                    KLog.w(YOUTUBE_TAG, "Repeated saved-albums continuation")
                    break
                }
                json = musicApi.fetchContinuation(token)
                if (json.isBlank()) {
                    KLog.w(YOUTUBE_TAG, "Saved-albums continuation failed after ${albums.size} albums")
                    break
                }
            }
            albums.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching saved albums", e)
            null
        }
    }

    /**
     * The artists followed in YouTube. Empty signed out or when the fetch
     * fails.
     */
    suspend fun getFollowedArtists(): List<ArtistItem> =
        getFollowedArtistsOrNull() ?: emptyList()

    /** As [getFollowedArtists], with `null` meaning the first page failed. */
    suspend fun getFollowedArtistsOrNull(
        onPage: (List<ArtistItem>) -> Unit = {},
    ): List<ArtistItem>? = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) return@withContext emptyList()
        try {
            val artists = mutableListOf<ArtistItem>()
            val seenTokens = mutableSetOf<String>()
            var json = musicApi.browseMusic(FOLLOWED_ARTISTS_BROWSE_ID, FOLLOWED_ARTISTS_PARAMS)
                ?: return@withContext null
            var pages = 0
            while (json.isNotBlank() && pages < MAX_PAGES) {
                artists += parseFollowedArtists(json)
                onPage(artists.distinctBy { it.id })
                pages++
                val token = extractShelfContinuationToken(json) ?: break
                if (!seenTokens.add(token)) {
                    KLog.w(YOUTUBE_TAG, "Repeated followed-artists continuation")
                    break
                }
                json = musicApi.fetchContinuation(token)
                if (json.isBlank()) {
                    KLog.w(
                        YOUTUBE_TAG,
                        "Followed-artists continuation failed after ${artists.size} artists"
                    )
                    break
                }
            }
            artists.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching followed artists", e)
            null
        }
    }

    /**
     * The album's audio playlist id (`OLAK5uy_...`) — the target the account's
     * save/remove action writes to.
     *
     * An album has no playlist id of its own: the browse id is `MPREb_...`,
     * which `like/like` refuses, and the id the account accepts is this one,
     * read from the album page's header. One extra browse per save or remove,
     * which is the price of the write landing at all [verified October 2026:
     * like/like with the resolved id adds the album to `FEmusic_liked_albums`,
     * like/removelike takes it out].
     */
    suspend fun getAlbumAudioPlaylistId(browseId: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = musicApi.browseMusic(browseId) ?: return@withContext null
            MusicMetadata.albumAudioPlaylistId(JSONObject(json))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Could not resolve the audio playlist for $browseId", e)
            null
        }
    }

    /**
     * Like or unlike a song on the account, which is what puts it in the
     * account's Liked Music.
     *
     * [videoId] is the song's own id; anything that is not an 11-character
     * video id (a device file, a download) has nothing to rate and is refused
     * rather than sent as a request YouTube would answer with an error.
     * [verified October 2026: like/like with `target.videoId` lands the song in
     * `LM` (Liked Music) within a second; like/removelike takes it out].
     */
    suspend fun setSongLiked(videoId: String, liked: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) return@withContext false
        if (videoId.length != VIDEO_ID_LENGTH) return@withContext false
        val body = JSONObject()
            .put("context", musicApi.musicContext())
            .put("target", JSONObject().put("videoId", videoId))
        val raw = musicApi.postMusicApi(if (liked) "like/like" else "like/removelike", body)
        if (raw == null) KLog.w(YOUTUBE_TAG, "Song ${if (liked) "like" else "unlike"} failed for $videoId")
        raw != null
    }

    /**
     * Follow or unfollow an artist on the account.
     *
     * The subscribe target is not the artist's browse id. YouTube answers an
     * artist page with a `subscribeButtonRenderer` naming the channel to
     * subscribe to and a params blob that picks the music-artist variant of the
     * call, and the channel id can differ from the id the page was opened on
     * [verified October 2026: browsing `UC0KFB-23-1AeWp1pOi6bvpQ` served
     * `channelIds: ["UCatoHeOoaJbOLfEyjjb-k8A"]`, and subscribing to the browse
     * id instead would not follow the artist]. So the page is read first and
     * its own endpoint replayed rather than guessed: one browse and one write.
     *
     * Returns true when the write was accepted. The subscription lists take a
     * few seconds to catch up, which is why callers update their own copy
     * optimistically rather than re-reading.
     */
    suspend fun setArtistFollowed(artistBrowseId: String, follow: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!sessionManager.isLoggedIn()) return@withContext false
            try {
                val json = musicApi.browseMusic(artistBrowseId) ?: return@withContext false
                val renderer = subscribeButton(JSONObject(json)) ?: return@withContext false
                val endpoint = renderer.optJSONObject(
                    if (follow) "subscribeEndpoint" else "unsubscribeEndpoint"
                ) ?: return@withContext false
                val channelIds = endpoint.optJSONArray("channelIds")
                if (channelIds == null || channelIds.length() == 0) return@withContext false
                val body = JSONObject().put("context", musicApi.musicContext())
                    .put("channelIds", channelIds)
                // Only the subscribe side carries params; the served
                // unsubscribe endpoint has none.
                if (follow) {
                    endpoint.optString("params").takeIf { it.isNotBlank() }
                        ?.let { body.put("params", it) }
                }
                val raw = musicApi.postMusicApi(
                    if (follow) "subscription/subscribe" else "subscription/unsubscribe",
                    body
                )
                if (raw == null) {
                    KLog.w(YOUTUBE_TAG, "Artist ${if (follow) "follow" else "unfollow"} failed for $artistBrowseId")
                }
                raw != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.e(YOUTUBE_TAG, "Could not set follow=$follow for $artistBrowseId", e)
                false
            }
        }

    /**
     * The artist's own subscribe button: the immersive header's, which is the
     * one that describes this page, falling back to the first anywhere for the
     * header variants that carry it elsewhere.
     */
    private fun subscribeButton(root: JSONObject): JSONObject? {
        val headers = mutableListOf<JSONObject>()
        findObjectsByKey(root, "musicImmersiveHeaderRenderer", headers)
        headers.asSequence()
            .mapNotNull {
                it.optJSONObject("subscriptionButton")
                    ?.optJSONObject("subscribeButtonRenderer")
            }
            .firstOrNull()
            ?.let { return it }
        val buttons = mutableListOf<JSONObject>()
        findObjectsByKey(root, "subscribeButtonRenderer", buttons)
        return buttons.firstOrNull()
    }

    private companion object {
        /**
         * The Library landing's Albums chip. Its own browse id, not
         * `FEmusic_library_corpus_albums` (which answers HTTP 400): the corpus
         * pages that exist are tracks and track-artists, while albums live on
         * the releases page.
         */
        const val SAVED_ALBUMS_BROWSE_ID = "FEmusic_liked_albums"

        /**
         * The Library's Subscriptions chip - the artists followed on YouTube.
         * The params blob is the one that page served for its own chip and
         * selects it; the browse id alone answers the same page, so this is
         * the served value rather than an invented one.
         */
        const val FOLLOWED_ARTISTS_BROWSE_ID = "FEmusic_library_corpus_artists"
        const val FOLLOWED_ARTISTS_PARAMS = "ggMCCAU%3D"

        /** A library that walks further than this is followed no further. */
        const val MAX_PAGES = 20

        /** A YouTube video id is always this long; nothing else can be rated. */
        const val VIDEO_ID_LENGTH = 11
    }
}
