package com.ivor.ivormusic.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parseLikedMusicPage` against the response shapes the probe recorded
 * (`.probe/lm1_shape.py`, `lm2_order.py`, `lm4_page2.py`): page one a
 * `musicPlaylistShelfRenderer`, later pages under
 * `appendContinuationItemsAction`, and the add time in `voteSortValue`.
 */
class LikedMusicPageTest {
    private fun text(name: String): String =
        javaClass.getResourceAsStream("/youtube/$name.json")!!.bufferedReader().use { it.readText() }

    @Test
    fun pageOneReadsRowsTheirAddTimesAndTheNextCursor() {
        val page = checkNotNull(parseLikedMusicPage(text("liked_music_page1")))

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), page.songs.map { it.id })
        assertEquals(listOf("Newest song", "Older song"), page.songs.map { it.title })
        assertEquals(
            listOf(1792160033000L, 1792049921000L),
            page.songs.map { it.dateAdded },
        )
        assertEquals("page-two", page.nextCursor)
        assertTrue(page.recentFirst)
        assertNull(page.totalCount)
    }

    @Test
    fun laterPagesArriveUnderAppendContinuationItems() {
        val page = checkNotNull(parseLikedMusicPage(text("liked_music_page2")))

        assertEquals(listOf("ccccccccccc"), page.songs.map { it.id })
        assertEquals(1791958792000L, page.songs.single().dateAdded)
        assertEquals("page-three", page.nextCursor)
        assertTrue(page.recentFirst)
    }

    @Test
    fun anOrderThatIsNotNewestFirstIsReported() {
        val unordered = text("liked_music_page1")
            .replace("1792160033", "1")
            .replace("1792049921", "2")

        assertFalse(checkNotNull(parseLikedMusicPage(unordered)).recentFirst)
    }

    @Test
    fun anEmptyShelfIsAnEmptyListRatherThanAFailedPage() {
        val empty = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":
            {"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":
            {"playlistId":"LM","contents":[]}}]}}}}}
        """.trimIndent()

        val page = checkNotNull(parseLikedMusicPage(empty))
        assertTrue(page.songs.isEmpty())
        assertNull(page.nextCursor)
        assertEquals(0, page.totalCount)
    }

    @Test
    fun aShellOrAnErrorIsNotAPage() {
        assertNull(parseLikedMusicPage(""))
        assertNull(parseLikedMusicPage("{}"))
        assertNull(parseLikedMusicPage("""{"error":{"code":401}}"""))
    }
}
