package com.ivor.ivormusic.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaylistPagerTest {

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = 1_000L
    )

    private fun page(ids: List<String>, continuation: String?) =
        MusicPlaylistPage(ids.map(::song), continuation)

    @Test
    fun loadsOnlyThePagesNeededForTheRequestedCount() = runBlocking {
        var calls = 0
        val pager = MusicPlaylistPager(isMix = false) { cursor ->
            calls++
            when (cursor) {
                null -> page(listOf("a", "b"), "t1")
                "t1" -> page(listOf("c"), null)
                else -> null
            }
        }

        assertEquals(listOf("a", "b"), pager.itemsThrough(1)?.map { it.id })
        assertEquals(1, calls)

        assertEquals(listOf("a", "b", "c"), pager.itemsThrough(3)?.map { it.id })
        assertEquals(2, calls)

        // Complete: a later page request reuses what is loaded.
        pager.itemsThrough(10)
        assertEquals(2, calls)
    }

    @Test
    fun aNormalPlaylistKeepsDuplicateOccurrences() = runBlocking {
        val pager = MusicPlaylistPager(isMix = false) { cursor ->
            when (cursor) {
                null -> page(listOf("a", "b"), "t1")
                "t1" -> page(listOf("b", "c"), null)
                else -> null
            }
        }

        assertEquals(listOf("a", "b", "b", "c"), pager.itemsThrough(4)?.map { it.id })
    }

    @Test
    fun aMixDropsRepeatedFiller() = runBlocking {
        val pager = MusicPlaylistPager(isMix = true) { cursor ->
            when (cursor) {
                null -> page(listOf("a", "b"), "t1")
                "t1" -> page(listOf("b", "c"), null)
                else -> null
            }
        }

        assertEquals(listOf("a", "b", "c"), pager.itemsThrough(3)?.map { it.id })
    }

    @Test
    fun aFailedFirstPageReturnsNull() = runBlocking {
        val pager = MusicPlaylistPager(isMix = false) { null }

        assertNull(pager.itemsThrough(10))
    }

    @Test
    fun aMixStopsAtTheCeiling() = runBlocking {
        val pager = MusicPlaylistPager(isMix = true) { cursor ->
            val start = cursor?.removePrefix("p")?.toInt() ?: 0
            val ids = (start until start + 100).map { "s$it" }
            page(ids, if (start + 100 < 600) "p${start + 100}" else null)
        }

        assertEquals(500, pager.itemsThrough(600)?.size)
    }
}
