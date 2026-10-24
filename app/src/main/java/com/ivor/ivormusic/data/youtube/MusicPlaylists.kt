package com.ivor.ivormusic.data.youtube

import com.ivor.ivormusic.data.LikedMusicPage
import com.ivor.ivormusic.data.MusicMetadata
import com.ivor.ivormusic.data.MusicPlaylistPage
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.SessionManager
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.UNKNOWN_ALBUM
import com.ivor.ivormusic.data.UNKNOWN_ARTIST
import com.ivor.ivormusic.data.UNKNOWN_TITLE
import com.ivor.ivormusic.data.items
import com.ivor.ivormusic.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Songs a generated mix (`RD...`) is read to; its continuation chain need not end. */
internal const val MIX_SONG_CEILING = 500

/** Continuation pages any playlist walk follows before it stops. */
private const val MAX_PLAYLIST_PAGES = 80

/**
 * The songs of a YouTube Music playlist or album, and the account's own
 * library of playlists and likes.
 *
 * One walker, [getBrowsePlaylistSongs], reads a playlist through WEB_REMIX
 * browse and its continuation chain for both session states. A page that
 * fails marks the load incomplete rather than ending it, so the NewPipe
 * fallback can still run; an HTTP error is never a playlist's end.
 */
internal class MusicPlaylists(
    private val musicApi: MusicApi,
    private val sessionManager: SessionManager,
    /**
     * The cached Liked Music, used when a caller asks for the whole list by
     * the `LM`/`VLLM` id (Android Auto, a queue built from the Liked Songs
     * tile). Reading `LM` directly walks the whole list, so the cache is
     * preferred and the walk is the fallback for a cold cache.
     */
    private val likedSongs: () -> List<Song> = { emptyList() },
) {
    suspend fun getPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        // For "Your Likes" playlist, serve the cache when it has anything -
        // the account's list, not a fresh walk of LM, which costs 17 pages.
        // A cold cache (first sign-in, before the first sync) still walks.
        if (playlistId == "LM" || playlistId == "VLLM") {
            if (!sessionManager.isLoggedIn()) return@withContext getPlaylistInternal("LM")
            val cached = likedSongs()
            if (cached.isNotEmpty()) return@withContext cached
            return@withContext getLikedMusic()
        }

        // Album browse ids aren't playlists; they only resolve via /browse
        if (playlistId.startsWith("MPRE")) {
            return@withContext getAlbumSongs(playlistId)
        }

        // For other playlists, use internal method
        getPlaylistInternal(playlistId)
    }

    /**
     * Fetch exactly one page of a playlist for the detail screen.
     *
     * The first page browses the playlist; later pages follow the cursor that
     * page returned. Unlike [getPlaylistInternal] this never walks the chain:
     * a 490-track playlist becomes five requests that each render as they
     * arrive, instead of five requests before the screen shows anything.
     */
    suspend fun getPlaylistPage(
        playlistId: String,
        continuation: String? = null,
    ): MusicPlaylistPage? = withContext(Dispatchers.IO) {
        try {
            val json = if (continuation != null) {
                musicApi.fetchContinuation(continuation)
            } else {
                val browseId = if (playlistId.startsWith("VL") || playlistId.startsWith("FE")) {
                    playlistId
                } else {
                    "VL$playlistId"
                }
                musicApi.browseMusic(browseId) ?: return@withContext null
            }
            if (json.isBlank()) return@withContext null
            MusicPlaylistPage(
                songs = parseSongsFromInternalJson(json, preserveDuplicates = true),
                continuation = extractPlaylistContinuationToken(json),
                // A generated mix has no fixed total (its chain need not end),
                // so only a finite playlist takes the header count.
                totalCount = if (continuation == null &&
                    !playlistId.removePrefix("VL").startsWith("RD")
                ) {
                    parsePageTotalCount(JSONObject(json))
                } else {
                    null
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching playlist page $playlistId", e)
            null
        }
    }

    /**
     * Fetch an album's tracks by its browse id (MPREb…).
     * Album pages aren't playlists, so they must go through /browse.
     */
    suspend fun getAlbumSongs(browseId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val body = musicApi.browseMusic(browseId) ?: return@withContext emptyList()
            val root = JSONObject(body)
            val songs = MusicMetadata.albumSongs(root, browseId)
            // Signed out, the page lists each track's video where it has one.
            // The album's audio playlist has the songs; one more request, and
            // only for an album that needs it.
            if (songs.isEmpty() || !MusicMetadata.albumHasVideoVersions(root)) return@withContext songs
            val audioPlaylist = MusicMetadata.albumAudioPlaylistId(root) ?: return@withContext songs
            val audio = getBrowsePlaylistSongs(audioPlaylist)
            if (audio.songs.isEmpty()) songs else MusicMetadata.withSongVersions(songs, audio.songs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching album $browseId", e)
            emptyList()
        }
    }

    /**
     * One page of the account's Liked Music - `LM`, browsed as `VLLM` - for
     * `AccountLikedSongsRepository`'s walk. [cursor] null requests page one.
     *
     * Null signed out or when the page did not arrive, which the caller reads
     * as "this page failed", never as the end of the list.
     */
    suspend fun getLikedMusicPage(cursor: String?): LikedMusicPage? = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) return@withContext null
        try {
            val json = if (cursor == null) {
                musicApi.browseMusic(LIKED_MUSIC_BROWSE_ID)
            } else {
                musicApi.fetchContinuation(cursor)
            }
            if (json.isNullOrBlank()) {
                KLog.w(YOUTUBE_TAG, "Liked Music page failed (cursor=${cursor != null})")
                return@withContext null
            }
            parseLikedMusicPage(json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching a Liked Music page", e)
            null
        }
    }

    /**
     * The account's Liked Music, read whole. Signed in this walks `LM` -
     * `like/like`'s own list, not `FEmusic_liked_videos` - through
     * [getLikedMusicPage], which is what the cache uses a page at a time;
     * this whole-list variant is for callers that have no cache to read.
     *
     * It costs one request per 100 songs - 17 on the account this was measured
     * against - so the app's own lists go through
     * `AccountLikedSongsRepository` instead.
     */
    suspend fun getLikedMusic(): List<Song> = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) {
            return@withContext getPlaylistInternal("LM")
        }
        
        try {
            val allSongs = mutableListOf<Song>()
            val seenTokens = mutableSetOf<String>()
            var cursor: String? = null
            var pageCount = 0

            while (pageCount < com.ivor.ivormusic.data.LIKED_MUSIC_MAX_PAGES) {
                val page = getLikedMusicPage(cursor) ?: break
                pageCount++
                allSongs += page.songs
                KLog.d(
                    YOUTUBE_TAG,
                    "Liked Music page $pageCount: ${page.songs.size} songs, total: ${allSongs.size}"
                )
                val next = page.nextCursor ?: return@withContext allSongs.distinctBy { it.id }
                if (!seenTokens.add(next)) {
                    KLog.w(YOUTUBE_TAG, "Repeated Liked Music continuation")
                    break
                }
                cursor = next
            }

            if (allSongs.isNotEmpty()) {
                return@withContext allSongs.distinctBy { it.id }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching liked music", e)
        }

        // Fallback to NewPipe method
        getPlaylistInternal("LM")
    }

    /**
     * Get the user's playlists.
     * Uses Internal YTM API with Cookies.
     */
    suspend fun getUserPlaylists(): List<PlaylistDisplayItem> =
        getUserPlaylistsOrNull() ?: emptyList()

    /**
     * As [getUserPlaylists], with `null` meaning the first page failed.
     *
     * Callers holding a cached list use that to keep it rather than replacing
     * it with an empty result. A successful fetch can legitimately be empty
     * (an account with no saved playlists), so the first page's response body
     * is the signal: `fetchInternalApi` answers `""` when the request never
     * arrived, while a parsed page is the account's real answer.
     */
    suspend fun getUserPlaylistsOrNull(
        onPage: (List<PlaylistDisplayItem>) -> Unit = {},
    ): List<PlaylistDisplayItem>? = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn()) return@withContext emptyList()
        
        try {
            val playlists = mutableListOf<PlaylistDisplayItem>()

            // "Your Likes" is already shown as the Liked Songs tile on the
            // Library screen, so adding it here only duplicates it.

            // Fetch Library (Liked Playlists), following grid continuations so
            // large libraries come back in full rather than just the first page.
            // Note: FEmusic_liked_playlists gets playlists you've saved/liked
            var json = musicApi.fetchInternalApi("FEmusic_liked_playlists")
            if (json.isEmpty()) return@withContext null
            var pageCount = 0
            val maxPages = 20
            while (json.isNotEmpty() && pageCount < maxPages) {
                val parsed = parsePlaylistsFromInternalJson(json)
                playlists.addAll(parsed)
                onPage(playlists.distinctBy { it.id })
                pageCount++
                KLog.d(YOUTUBE_TAG, "Library playlists page $pageCount: ${parsed.size} items")
                if (parsed.isEmpty()) break
                val token = extractContinuationToken(json) ?: break
                json = musicApi.fetchContinuation(token)
            }

            // The library grid can include "Your Likes" (VLLM), which the
            // Library draws separately as the Liked Songs tile.
            playlists.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(YOUTUBE_TAG, "Error fetching user playlists", e)
            null
        }
    }

    /**
     * Internal playlist fetching without the LM redirect to avoid infinite recursion.
     */
    suspend fun getPlaylistInternal(playlistId: String): List<Song> = withContext(Dispatchers.IO) {

        // WEB_REMIX first for both session states: NewPipe drops album links
        // and cannot distinguish a playlist's title from a track's album.
        val accountResult = getBrowsePlaylistSongs(playlistId)
        if (accountResult.complete && accountResult.songs.isNotEmpty()) {
            return@withContext accountResult.songs
        }

        var newPipeComplete = true
        val newPipeSongs = try {
            val urlId = if (playlistId.startsWith("VL")) playlistId.removePrefix("VL") else playlistId
            val playlistUrl = "https://www.youtube.com/playlist?list=$urlId"

            val playlistExtractor = youtubeService.getPlaylistExtractor(playlistUrl)
            playlistExtractor.fetchPage()

            val allItems = mutableListOf<StreamInfoItem>()
            allItems.addAll(playlistExtractor.initialPage.items.filterIsInstance<StreamInfoItem>())

            var currentPage = playlistExtractor.initialPage
            while (currentPage.hasNextPage()) {
                try {
                    currentPage = playlistExtractor.getPage(currentPage.nextPage)
                    allItems.addAll(currentPage.items.filterIsInstance<StreamInfoItem>())
                } catch (e: Exception) {
                    newPipeComplete = false
                    KLog.w(
                        YOUTUBE_TAG,
                        "NewPipe playlist continuation failed for $playlistId after ${allItems.size} items",
                        e
                    )
                    break
                }
            }

            allItems.mapNotNull { item ->
                Song.fromYouTube(
                    videoId = extractVideoId(item.url),
                    title = item.name ?: UNKNOWN_TITLE,
                    artist = item.uploaderName ?: UNKNOWN_ARTIST,
                    album = UNKNOWN_ALBUM,
                    duration = item.duration * 1000L,
                    thumbnailUrl = item.thumbnails?.firstOrNull()?.url
                )
            }
        } catch (e: Exception) {
            newPipeComplete = false
            emptyList()
        }

        if (newPipeComplete && newPipeSongs.isNotEmpty()) return@withContext newPipeSongs

        // Do not throw away useful rows if every complete path failed, but log
        // loudly that this is degraded rather than pretending the exact page
        // boundary is the playlist's real end.
        val partial = listOf(accountResult.songs, newPipeSongs)
            .maxByOrNull { it.size }
            .orEmpty()
        if (partial.isNotEmpty()) {
            KLog.w(
                YOUTUBE_TAG,
                "Returning incomplete playlist $playlistId (${partial.size} songs) after all full-load paths failed"
            )
        }
        partial
    }

    private data class PlaylistLoadResult(
        val songs: List<Song>,
        val complete: Boolean
    )

    /**
     * Every page of a playlist, for callers that need the whole list (a queue,
     * a download). Each step goes through [getPlaylistPage], so a full walk and
     * the detail screen's demand-paged walk agree on how a page is fetched and
     * parsed.
     *
     * Account cookies are attached by [MusicApi.browseMusic] and [MusicApi.fetchContinuation]
     * when there is a session and omitted when there is not, so this one walker
     * serves both: a public playlist pages to its end anonymously, and an owned
     * or private one needs the session that those two helpers already apply.
     */
    private suspend fun getBrowsePlaylistSongs(playlistId: String): PlaylistLoadResult {
        val isMix = playlistId.removePrefix("VL").startsWith("RD")
        val allSongs = mutableListOf<Song>()
        val seenTokens = mutableSetOf<String>()
        var continuation: String? = null

        while (true) {
            val page = getPlaylistPage(playlistId, continuation)
            if (page == null) {
                if (continuation != null) {
                    KLog.w(
                        YOUTUBE_TAG,
                        "Authenticated playlist continuation failed for $playlistId after ${allSongs.size} songs"
                    )
                }
                return PlaylistLoadResult(allSongs, complete = false)
            }
            if (isMix) {
                // A mix is topped up as it is read and repeats songs to do it;
                // a repeat there is filler, not a second occurrence.
                val seen = allSongs.mapTo(HashSet()) { it.id }
                page.songs.filterTo(allSongs) { seen.add(it.id) }
                // [verified October 2026] A mix's chain need not end: one
                // personal mix still offered a token after 110 pages and 2,600
                // songs, and the walk below would follow it for as long as the
                // screen waited. The ceiling still holds a Replay Mix whole
                // (412 songs, a chain that does end).
                if (allSongs.size >= MIX_SONG_CEILING) {
                    return PlaylistLoadResult(allSongs.take(MIX_SONG_CEILING), complete = true)
                }
            } else {
                allSongs += page.songs
            }
            if (seenTokens.size >= MAX_PLAYLIST_PAGES) {
                // Past YouTube's 5,000-item playlist limit at 100 rows a page:
                // whatever this is, it is not ending.
                KLog.w(YOUTUBE_TAG, "Stopped playlist $playlistId at ${allSongs.size} songs, page limit")
                return PlaylistLoadResult(allSongs, complete = true)
            }
            val token = page.continuation
                ?: return PlaylistLoadResult(allSongs, complete = true)
            if (!seenTokens.add(token)) {
                KLog.w(YOUTUBE_TAG, "Repeated playlist continuation for $playlistId")
                return PlaylistLoadResult(allSongs, complete = false)
            }
            continuation = token
        }
    }

    private companion object {
        /** `LM` (Liked Music) as a browse id; the playlist page is `VLLM`. */
        const val LIKED_MUSIC_BROWSE_ID = "VLLM"
    }
}
