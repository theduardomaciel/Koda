package com.ivor.ivormusic.data.youtube

import com.ivor.ivormusic.data.ArtistItem
import com.ivor.ivormusic.data.LikedMusicPage
import com.ivor.ivormusic.data.MusicMetadata
import com.ivor.ivormusic.data.PlaylistDisplayItem
import com.ivor.ivormusic.data.Song
import com.ivor.ivormusic.data.continuationItemsOrNull
import com.ivor.ivormusic.util.KLog
import org.json.JSONArray
import org.json.JSONObject

// WEB_REMIX responses read into songs and playlists: the account's library
// pages, playlist shelves and their continuation tokens. Pure; the row-level
// reading of a song's artists and album is in MusicMetadata.

internal fun parseSongsFromInternalJson(
    json: String,
    preserveDuplicates: Boolean = false
): List<Song> {
    val songs = mutableListOf<Song>()
    try {
        val root = JSONObject(json)

        // OPTIMIZED: Direct traversal instead of recursive search
        // Find the SectionListRenderer which contains the shelves
        val contentsArray = findRootContents(root) ?: return emptyList()

        // Iterate over shelves (musicCarouselShelfRenderer, musicShelfRenderer, etc.)
        for (i in 0 until contentsArray.length()) {
            val shelfWrapper = contentsArray.optJSONObject(i) ?: continue

            // Get the items array from the shelf
            val items = parseItemsFromShelf(shelfWrapper)

            // Process items
            items.forEach { item ->
                try {
                    // Strategy 1: musicResponsiveListItemRenderer (Flex Columns) - Standard Song/Video list
                    val responsiveItem = item.optJSONObject("musicResponsiveListItemRenderer")
                    if (responsiveItem != null) {
                        parseResponsiveListItem(responsiveItem)?.let { songs.add(it) }
                    }

                    // Strategy 2: musicTwoRowItemRenderer (Title/Subtitle) - Cards/Shelves
                    val twoRowItem = item.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRowItem != null) {
                         parseTwoRowItem(twoRowItem)?.let { songs.add(it) }
                    }
                } catch (e: Exception) {
                    // Skip malformed item
                }
            }
        }
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not parse music song shelf", e)
    }
    return if (preserveDuplicates) songs else songs.distinctBy { it.id }
}

/**
 * One FEmusic_home page, read once for both halves of Classic Home.
 *
 * Songs are split by how Home draws them: list rows first (Quick picks), cards
 * second (Listen again and similar). The cards only fill in after the rows.
 *
 * YouTube Music's own mixes live on Home rather than in the account's library:
 * `FEmusic_liked_playlists` returns only "Discover Mix" for an account, while
 * the rest arrive on "Fresh finds, old favorites" ("Replay Mix", "Archive
 * Mix", "New Release Mix") and under "Mixed for you" ("My Supermix" plus a
 * personal set whose names carry no "mix" word at all).
 *
 * Every generated mix is a radio playlist, so its browse id is `RD...` once
 * the `VL` prefix is gone. That id is the machine-made signal; the title is
 * only used to keep curated radios on other shelves ("Indie Lullaby",
 * "Alternative All Day") out of the rail. Inside "Mixed for you" every card
 * is taken regardless of title, because those names are generated per account
 * and are not matchable.
 */
internal data class HomePageContent(
    val rows: List<Song>,
    val cards: List<Song>,
    val mixes: List<PlaylistDisplayItem>,
    /** Whether this page carried the "Mixed for you" shelf, which ends the walk. */
    val hasMixedForYouShelf: Boolean,
)

internal fun parseHomeContent(json: String): HomePageContent {
    val rows = mutableListOf<Song>()
    val cards = mutableListOf<Song>()
    val mixes = mutableListOf<PlaylistDisplayItem>()
    var hasMixedForYouShelf = false
    try {
        val contents = findRootContents(JSONObject(json))
            ?: return HomePageContent(emptyList(), emptyList(), emptyList(), false)
        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i) ?: continue
            val shelf = shelfTitle(section).orEmpty()
            val mixedForYou = shelf.contains("mixed for you", ignoreCase = true)
            if (mixedForYou) hasMixedForYouShelf = true
            for (item in parseItemsFromShelf(section)) {
                item.optJSONObject("musicResponsiveListItemRenderer")
                    ?.let(::parseResponsiveListItem)?.let(rows::add)
                val card = item.optJSONObject("musicTwoRowItemRenderer") ?: continue
                parseTwoRowItem(card)?.let(cards::add)
                val playlist = twoRowPlaylist(card) ?: continue
                if (!playlist.id.startsWith("RD")) continue
                if (!mixedForYou && !isHomeMixName(playlist.name)) continue
                mixes += playlist
            }
        }
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not parse a music Home page", e)
    }
    return HomePageContent(rows, cards, mixes.distinctBy { it.id }, hasMixedForYouShelf)
}

/**
 * The token for the next block of Home shelves. Home's section list pages:
 * the first answer carries it on `sectionListRenderer`, each continuation on
 * `sectionListContinuation`. [verified October 2026: four pages signed in,
 * and Quick picks is the first shelf of the second, no longer the first]
 */
internal fun homeSectionContinuationToken(json: String): String? = try {
    val root = JSONObject(json)
    val sectionList = root.optJSONObject("contents")
        ?.optJSONObject("singleColumnBrowseResultsRenderer")
        ?.optJSONArray("tabs")?.optJSONObject(0)
        ?.optJSONObject("tabRenderer")
        ?.optJSONObject("content")
        ?.optJSONObject("sectionListRenderer")
        ?: root.optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
    sectionList?.optJSONArray("continuations")?.optJSONObject(0)
        ?.optJSONObject("nextContinuationData")
        ?.optString("continuation")
        ?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/**
 * One playlist card from a two-row renderer, or null when the row is not a
 * playlist (a song, an album, a menu command).
 */
private fun twoRowPlaylist(card: JSONObject): PlaylistDisplayItem? {
    val browseId = card.optJSONObject("navigationEndpoint")
        ?.optJSONObject("browseEndpoint")
        ?.optString("browseId")
        ?.takeIf { it.startsWith("VL") || it.startsWith("PL") }
        ?: return null
    val cleanId = browseId.removePrefix("VL")
    val title = getRunText(card.optJSONObject("title")).orEmpty().trim()
    if (title.isEmpty()) return null
    val subtitleObj = card.optJSONObject("subtitle")
    val thumbnails = card.optJSONObject("thumbnailRenderer")
        ?.optJSONObject("musicThumbnailRenderer")
        ?.optJSONObject("thumbnail")
        ?.optJSONArray("thumbnails")
    return PlaylistDisplayItem(
        name = title,
        url = "https://music.youtube.com/playlist?list=$cleanId",
        uploaderName = getRunText(subtitleObj).orEmpty(),
        itemCount = extractItemCountFromSubtitle(subtitleObj),
        thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
    )
}

internal fun parsePlaylistsFromInternalJson(json: String): List<PlaylistDisplayItem> {
    val playlists = mutableListOf<PlaylistDisplayItem>()
    try {
        val root = JSONObject(json)

        // OPTIMIZED: Use direct traversal
        val contentsArray = findRootContents(root) ?: return emptyList()

        // Iterate over shelves and items
        for (i in 0 until contentsArray.length()) {
            val shelfWrapper = contentsArray.optJSONObject(i) ?: continue
            parseItemsFromShelf(shelfWrapper).forEach { item ->
                item.optJSONObject("musicTwoRowItemRenderer")
                    ?.let(::twoRowPlaylist)
                    ?.let(playlists::add)
            }
        }
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not parse music playlist shelf", e)
    }
    return playlists 
}

/** Generated mix names that say so: "Replay Mix", "My Mix 3", "My Supermix". */
private val HOME_MIX_NAME = Regex("""(^|\s)mix(\s+\d+)?$|supermix$""", RegexOption.IGNORE_CASE)

private fun isHomeMixName(name: String): Boolean = HOME_MIX_NAME.containsMatchIn(name.trim())

private fun shelfTitle(section: JSONObject): String? {
    val header = section.optJSONObject("musicCarouselShelfRenderer")?.optJSONObject("header")
        ?: section.optJSONObject("musicShelfRenderer")?.optJSONObject("header")
        ?: return null
    val renderer = header.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
        ?: header.optJSONObject("musicShelfBasicHeaderRenderer")
        ?: return null
    return getRunText(renderer.optJSONObject("title"))
}

/**
 * Locates the 'contents' array within sectionListRenderer by traversing standard paths.
 * Handles: Home (Browse), Search Results, and Playlist Details.
 */
private fun findRootContents(root: JSONObject): JSONArray? {
    // Path 1: Standard Browse/Home/Playlist (contents -> singleColumn... -> tabs -> tab -> content -> sectionList)
    root.optJSONObject("contents")
        ?.optJSONObject("singleColumnBrowseResultsRenderer")
        ?.optJSONArray("tabs")?.optJSONObject(0)
        ?.optJSONObject("tabRenderer")
        ?.optJSONObject("content")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?.let { return it }

    // Path 1b: Two-column Browse (newer playlist/album layout:
    // contents -> twoColumnBrowseResultsRenderer -> secondaryContents -> sectionList)
    root.optJSONObject("contents")
        ?.optJSONObject("twoColumnBrowseResultsRenderer")
        ?.optJSONObject("secondaryContents")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?.let { return it }

    // Path 2: Search Results (contents -> tabbedSearchResultsRenderer -> tabs -> tab -> content -> sectionList)
    root.optJSONObject("contents")
        ?.optJSONObject("tabbedSearchResultsRenderer")
        ?.optJSONArray("tabs")?.optJSONObject(0)
        ?.optJSONObject("tabRenderer")
        ?.optJSONObject("content")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?.let { return it }

    // Path 3: Direct SectionList (sometimes used in continuation responses)
    root.optJSONObject("continuationContents")
        ?.optJSONObject("musicPlaylistShelfContinuation")
        ?.optJSONArray("contents")
        ?.let { 
            // Wrap items in a synthetic shelf structure to match loop expectation or return directly
            // For continuation, it's usually a list of items directly.
            // To keep logic consistent, we'll return this directly and handle it if the caller expects shelves.
            // Actually, continuations usually return items directly, not shelves.
            // Let's handle generic continuation structure:
            return it
        }



    root.optJSONObject("continuationContents")
        ?.optJSONObject("musicShelfContinuation")
        ?.optJSONArray("contents")
        ?.let { return it }

    root.optJSONObject("continuationContents")
        ?.optJSONObject("sectionListContinuation")
        ?.optJSONArray("contents")
        ?.let { return it }

    // Grid continuation (library playlist/album pages)
    root.optJSONObject("continuationContents")
        ?.optJSONObject("gridContinuation")
        ?.optJSONArray("items")
        ?.let { return it }

    // Modern continuation shape - every branch above is the legacy form.
    // See continuationItemsOrNull for what changed and what it cost.
    continuationItemsOrNull(root)?.let { return it }

    return null
}

/**
 * Extracts the list of items from a Shelf wrapper (Carousel, Shelf, or direct list).
 */
internal fun parseItemsFromShelf(shelfWrapper: JSONObject): List<JSONObject> {
    val items = mutableListOf<JSONObject>()

    // 1. musicCarouselShelfRenderer (Horizontal Scroll)
    val carousel = shelfWrapper.optJSONObject("musicCarouselShelfRenderer")
    if (carousel != null) {
        val contents = carousel.optJSONArray("contents")
        if (contents != null) {
            for (j in 0 until contents.length()) {
                contents.optJSONObject(j)?.let { items.add(it) }
            }
        }
        return items
    }

    // 2. musicShelfRenderer (Vertical List)
    val shelf = shelfWrapper.optJSONObject("musicShelfRenderer")
    if (shelf != null) {
        val contents = shelf.optJSONArray("contents")
        if (contents != null) {
            for (j in 0 until contents.length()) {
                contents.optJSONObject(j)?.let { items.add(it) }
            }
        }
        return items
    }

    // 3. musicPlaylistShelfRenderer (Playlist Detail List)
    val playlistShelf = shelfWrapper.optJSONObject("musicPlaylistShelfRenderer")
    if (playlistShelf != null) {
        val contents = playlistShelf.optJSONArray("contents")
        if (contents != null) {
            for (j in 0 until contents.length()) {
                contents.optJSONObject(j)?.let { items.add(it) }
            }
        }
        return items
    }

    // 4. gridRenderer (Library pages: grid of playlists/albums)
    val grid = shelfWrapper.optJSONObject("gridRenderer")
    if (grid != null) {
        val gridItems = grid.optJSONArray("items")
        if (gridItems != null) {
            for (j in 0 until gridItems.length()) {
                gridItems.optJSONObject(j)?.let { items.add(it) }
            }
        }
        return items
    }

    // 5. itemSectionRenderer wraps another shelf (e.g. the library grid)
    val itemSection = shelfWrapper.optJSONObject("itemSectionRenderer")
    if (itemSection != null) {
        val contents = itemSection.optJSONArray("contents")
        if (contents != null) {
            for (j in 0 until contents.length()) {
                contents.optJSONObject(j)?.let { items.addAll(parseItemsFromShelf(it)) }
            }
        }
        return items
    }

    // 6. Direct Item (if the "shelf" is actually just an item in a continuation list)
    if (shelfWrapper.has("musicResponsiveListItemRenderer") || shelfWrapper.has("musicTwoRowItemRenderer")) {
        items.add(shelfWrapper)
    }

    return items
}

internal fun parseResponsiveListItem(item: JSONObject): Song? = MusicMetadata.song(item)

private fun parseTwoRowItem(item: JSONObject): Song? = MusicMetadata.song(item)

/**
 * Extract item count from playlist subtitle.
 * The subtitle typically contains patterns like "100 songs", "50 videos", etc.
 */
private fun extractItemCountFromSubtitle(subtitleObj: JSONObject?): Int {
    if (subtitleObj == null) return -1

    try {
        // Try to find count in runs array
        val runs = subtitleObj.optJSONArray("runs")
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val runText = runs.optJSONObject(i)?.optString("text") ?: continue
                // Look for patterns like "100 songs", "50 videos", "25 tracks"
                val countMatch = Regex("""(\d+)\s*(songs?|videos?|tracks?)""", RegexOption.IGNORE_CASE).find(runText)
                if (countMatch != null) {
                    return countMatch.groupValues[1].toIntOrNull() ?: -1
                }
                // Also check for just numbers that might represent count
                val numberMatch = Regex("""^(\d+)$""").find(runText.trim())
                if (numberMatch != null) {
                    return numberMatch.groupValues[1].toIntOrNull() ?: -1
                }
            }
        }

        // Try from simpleText
        val simpleText = subtitleObj.optString("simpleText", "")
        val countMatch = Regex("""(\d+)\s*(songs?|videos?|tracks?)""", RegexOption.IGNORE_CASE).find(simpleText)
        if (countMatch != null) {
            return countMatch.groupValues[1].toIntOrNull() ?: -1
        }
    } catch (e: Exception) {
        // Ignore
    }

    return -1
}

/**
 * Read only the continuation belonging to the playlist shelf. A generic
 * recursive "first continuation" can pick an unrelated carousel token.
 *
 * That scoping is load-bearing rather than tidiness [verified August 2026]:
 * a playlist page also carries
 * `twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.continuations[0]`,
 * and following that token returns a `musicCarouselShelfRenderer` of
 * related playlists, not more tracks. Do not widen this to a bare
 * findContinuationTokens over the whole response.
 *
 * Page one puts the token in a `continuationItemRenderer` at the end of the
 * shelf's own contents, which the shelf scopes already cover. Later pages
 * arrive under `appendContinuationItemsAction`, where there is no shelf at
 * all - without that scope the chain stopped after page two even once the
 * items themselves parsed.
 */
internal fun extractPlaylistContinuationToken(json: String): String? = try {
    val root = JSONObject(json)
    val scopes = mutableListOf<JSONObject>()
    findObjectsByKey(root, "musicPlaylistShelfRenderer", scopes)
    findObjectsByKey(root, "musicPlaylistShelfContinuation", scopes)
    findObjectsByKey(root, "appendContinuationItemsAction", scopes)
    findObjectsByKey(root, "reloadContinuationItemsCommand", scopes)
    scopes.asSequence().mapNotNull { scope ->
        val tokens = mutableListOf<String>()
        findContinuationTokens(scope, tokens)
        tokens.firstOrNull()
    }.firstOrNull()
} catch (e: Exception) {
    null
}

/**
 * One page of the account's Liked Music (`VLLM`), the list `like/like`
 * writes.
 *
 * `playlistItemData.voteSortValue` is the entry's add time in seconds and is
 * non-increasing down page one [verified October 2026: 100 rows a page,
 * liking a song prepends it and unliking it leaves the rest in order]. It
 * becomes [Song.dateAdded], and the ordering is what makes the incremental
 * sync's early stop sound; [LikedMusicPage.recentFirst] is false when a page
 * breaks it, which makes the caller read the list whole instead.
 *
 * Returns null when the response is not a page at all - a sign-in shell, an
 * error object, a malformed body - so a failed request is never mistaken for
 * the end of the list.
 */
internal fun parseLikedMusicPage(json: String): LikedMusicPage? {
    val root = try {
        JSONObject(json)
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not read a Liked Music page", e)
        return null
    }
    val contents = findRootContents(root) ?: return null
    val songs = mutableListOf<Song>()
    val votes = mutableListOf<Long>()
    for (i in 0 until contents.length()) {
        val wrapper = contents.optJSONObject(i) ?: continue
        for (item in parseItemsFromShelf(wrapper)) {
            val row = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            val data = row.optJSONObject("playlistItemData") ?: continue
            if (data.optString("videoId").isBlank()) continue
            val song = MusicMetadata.song(row) ?: continue
            val vote = data.optLong("voteSortValue", 0L)
            if (vote > 0L) {
                votes += vote
                songs += song.copy(dateAdded = vote * 1000L)
            } else {
                songs += song
            }
        }
    }
    return LikedMusicPage(
        songs = songs.distinctBy { it.id },
        nextCursor = extractPlaylistContinuationToken(json),
        // Page one's header states how many songs the account holds ("N songs");
        // a page with no header - a later page, or a sign-in shell - carries
        // no total. An empty first page is an account with no likes,
        // reported as zero so the sync can believe it rather than treat it as a
        // page that failed to arrive.
        totalCount = parseLikedMusicTotalCount(root, songs.isEmpty()),
        // The walk's early stop is only sound if the page is served newest
        // first. voteSortValue is the add time in seconds; a tie is expected
        // for songs liked in one burst, so non-increasing rather than strictly.
        // A page with no add times carries no evidence, and a single row is
        // trivially in order; otherwise the add times must not go up.
        recentFirst = votes.isNotEmpty() && votes.zipWithNext().all { (a, b) -> a >= b },
    )
}

/**
 * The account's own count of Liked Music, from page one's header ("N songs").
 * A page with no header - a later page, or a sign-in shell - carries
 * no total, so the answer is null rather than a guess. An empty first page is
 * reported as zero, the one reading of an account with no likes.
 */
internal fun parseLikedMusicTotalCount(root: JSONObject, emptyList: Boolean): Int? {
    val headers = mutableListOf<JSONObject>()
    findObjectsByKey(root, "musicResponsiveHeaderRenderer", headers)
    val countRegex = Regex("""(\d[\d.,]*)\s+songs?\b""", RegexOption.IGNORE_CASE)
    for (header in headers) {
        for (key in listOf("secondSubtitle", "subtitle", "title")) {
            val text = getRunText(header.optJSONObject(key)) ?: continue
            val match = countRegex.find(text) ?: continue
            val digits = match.groupValues[1].replace(Regex("[.,]"), "")
            val value = digits.toIntOrNull() ?: continue
            return value
        }
    }
    return if (emptyList) 0 else null
}

// ---------------------------------------------------------------------------
// The account's own library pages: saved albums and followed artists.
//
// Both are account browse ids, so signed out they answer a sign-in shell rather
// than an error and the caller gate is `isLoggedIn()`. Every shape here was
// probed signed in on music.youtube.com (WEB_REMIX), October 2026.
// ---------------------------------------------------------------------------

/**
 * The albums saved to the account's YouTube Music library, from
 * `FEmusic_liked_albums` (`GetBrowseCorpusReleasesPage`).
 *
 * Page one is a `gridRenderer`; later pages answer under
 * `continuationContents.gridContinuation.items`. Only the grid is read: the
 * page carries other two-row cards beside it, and an album's identity is the
 * `MUSIC_PAGE_TYPE_ALBUM` endpoint [MusicMetadata.release] requires, never a
 * subtitle position.
 */
internal fun parseSavedAlbums(json: String): List<PlaylistDisplayItem> {
    val rows = mutableListOf<JSONObject>()
    try {
        val root = JSONObject(json)
        val grids = mutableListOf<JSONObject>()
        findObjectsByKey(root, "gridRenderer", grids)
        for (grid in grids) collectRendererItems(grid.optJSONArray("items"), rows)
        collectRendererItems(
            root.optJSONObject("continuationContents")
                ?.optJSONObject("gridContinuation")?.optJSONArray("items"),
            rows
        )
        return rows.mapNotNull { item ->
            item.optJSONObject("musicTwoRowItemRenderer")?.let { row -> MusicMetadata.release(row) }
        }.distinctBy { it.id }
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not parse saved albums", e)
        return emptyList()
    }
}

/**
 * The artists followed in YouTube (`FEmusic_library_corpus_artists`,
 * `GetBrowseSubscriptionsPage`) - the Library's Subscriptions chip, which is
 * distinct from its Artists chip: that one
 * (`FEmusic_library_corpus_track_artists`, `GetBrowseCorpusArtistsPage`) lists
 * the artists of songs already in the library and answers with
 * `MUSIC_PAGE_TYPE_LIBRARY_ARTIST` rows whose ids are `MPLAUC...` rather than
 * the `UC...` channel ids an artist page opens on.
 *
 * Page one is a `musicShelfRenderer`; later pages answer under
 * `continuationContents.musicShelfContinuation.contents`. The name is the
 * first flex column's run text (it leads with a space), the audience is the
 * second, and the id comes from the row's own browse endpoint.
 */
internal fun parseFollowedArtists(json: String): List<ArtistItem> {
    val rows = mutableListOf<JSONObject>()
    try {
        val root = JSONObject(json)
        val shelves = mutableListOf<JSONObject>()
        findObjectsByKey(root, "musicShelfRenderer", shelves)
        for (shelf in shelves) collectRendererItems(shelf.optJSONArray("contents"), rows)
        collectRendererItems(
            root.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")?.optJSONArray("contents"),
            rows
        )
        return rows.mapNotNull { item ->
            val row = item.optJSONObject("musicResponsiveListItemRenderer")
                ?: return@mapNotNull null
            val id = row.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            val name = getRunText(flexColumnText(row, 0))?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val subscribers = getRunText(flexColumnText(row, 1))?.takeIf { it.isNotBlank() }
            val thumbnails = MusicMetadata.objects(row, "musicThumbnailRenderer").firstOrNull()
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ArtistItem(
                id = id,
                name = name,
                thumbnailUrl = bestThumbnail(thumbnails),
                subscriberCount = subscribers,
            )
        }.distinctBy { it.id }
    } catch (e: Exception) {
        KLog.e(YOUTUBE_TAG, "Could not parse followed artists", e)
        return emptyList()
    }
}

private fun collectRendererItems(array: JSONArray?, into: MutableList<JSONObject>) {
    if (array == null) return
    for (i in 0 until array.length()) array.optJSONObject(i)?.let(into::add)
}

private fun flexColumnText(row: JSONObject, index: Int): JSONObject? =
    row.optJSONArray("flexColumns")?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")

/**
 * The next token of a grid list (the saved-albums page). Scoped to the grid so
 * the section-list continuations the other tabs of the same response carry are
 * never followed instead.
 */
internal fun extractGridContinuationToken(json: String): String? = try {
    val root = JSONObject(json)
    val scopes = mutableListOf<JSONObject>()
    findObjectsByKey(root, "gridRenderer", scopes)
    findObjectsByKey(root, "gridContinuation", scopes)
    scopes.asSequence().mapNotNull { scope ->
        val tokens = mutableListOf<String>()
        findContinuationTokens(scope, tokens)
        tokens.firstOrNull()
    }.firstOrNull()
} catch (e: Exception) {
    null
}

/**
 * The next token of a music shelf (the followed-artists page). Scoped to the
 * shelf for the same reason as [extractGridContinuationToken].
 */
internal fun extractShelfContinuationToken(json: String): String? = try {
    val root = JSONObject(json)
    val scopes = mutableListOf<JSONObject>()
    findObjectsByKey(root, "musicShelfRenderer", scopes)
    findObjectsByKey(root, "musicShelfContinuation", scopes)
    scopes.asSequence().mapNotNull { scope ->
        val tokens = mutableListOf<String>()
        findContinuationTokens(scope, tokens)
        tokens.firstOrNull()
    }.firstOrNull()
} catch (e: Exception) {
    null
}
