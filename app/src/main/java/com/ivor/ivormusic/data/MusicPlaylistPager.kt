package com.ivor.ivormusic.data

import com.ivor.ivormusic.data.youtube.MIX_SONG_CEILING

/**
 * A whole playlist read forward on demand.
 *
 * Android Auto browses by page number and has no continuation of its own, so
 * the service keeps this cursor per playlist: page N asks for at least
 * `(N + 1) * pageSize` rows, and the pager fetches only the pages needed to
 * reach that, reusing everything already read.
 *
 * A normal playlist preserves duplicate occurrences; a generated mix repeats
 * songs to top itself up, so its repeats are dropped and the list stops at
 * [MIX_SONG_CEILING], the same rule the whole-list walk uses.
 */
internal class MusicPlaylistPager(
    private val isMix: Boolean,
    private val loadPage: suspend (continuation: String?) -> MusicPlaylistPage?,
) {
    private val songs = mutableListOf<Song>()
    private val seen = HashSet<String>()
    private var continuation: String? = null
    private var started = false
    private var complete = false
    private var pages = 0

    /**
     * The rows loaded so far, grown until it holds at least [count] or the
     * chain ends. Null when the first page failed and nothing is loaded.
     */
    suspend fun itemsThrough(count: Int): List<Song>? {
        while (!complete && songs.size < count) {
            if (pages >= MAX_PAGES) {
                complete = true
                break
            }
            val page = loadPage(if (started) continuation else null)
            if (page == null) return songs.ifEmpty { null }
            started = true
            pages++
            val accepted = if (isMix) page.songs.filter { seen.add(it.id) } else page.songs
            songs += accepted
            continuation = page.continuation
            complete = continuation == null
            if (isMix && songs.size >= MIX_SONG_CEILING) {
                if (songs.size > MIX_SONG_CEILING) {
                    songs.subList(MIX_SONG_CEILING, songs.size).clear()
                }
                complete = true
            }
        }
        return songs
    }

    private companion object {
        /** Past YouTube's 5,000-item limit at 100 rows a page. */
        const val MAX_PAGES = 80
    }
}
