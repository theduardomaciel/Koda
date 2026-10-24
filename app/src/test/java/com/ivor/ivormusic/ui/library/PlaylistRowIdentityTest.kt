package com.ivor.ivormusic.ui.library

import com.ivor.ivormusic.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistRowIdentityTest {

    @Test
    fun `duplicate videos receive unique stable row identities`() {
        val first = song("same", "First occurrence")
        val second = song("same", "Second occurrence")
        val rows = playlistSongRows(listOf(first, second), "playlist_test")

        assertNotEquals(rows[0].key, rows[1].key)

        val reordered = listOf(rows[1], rows[0])
        assertEquals(rows[1].key, reordered[0].key)
        assertEquals(rows[0].key, reordered[1].key)
    }

    @Test
    fun `duplicate videos keep their own set video ids`() {
        val rows = playlistSongRows(
            listOf(song("same", "One"), song("other", "Other"), song("same", "Two")),
            "playlist_test"
        )

        val attached = attachPlaylistSetVideoIds(
            rows,
            mapOf("same" to listOf("set-1", "set-2"), "other" to listOf("set-other"))
        )

        assertEquals(listOf("set-1", "set-other", "set-2"), attached.map { it.setVideoId })
    }

    @Test
    fun `appending a page keys repeats against the rows already loaded`() {
        val firstPage = playlistSongRows(listOf(song("same", "One")), "playlist_test")
        val attached = firstPage.map { it.copy(setVideoId = "set-1") }

        val appended = appendPlaylistSongRows(
            attached,
            listOf(song("same", "Two"), song("other", "Other")),
            "playlist_test"
        )

        assertEquals(3, appended.size)
        assertNotEquals(appended[0].key, appended[1].key)
        assertEquals("set-1", appended[0].setVideoId)
        assertEquals(
            playlistSongRows(
                listOf(song("same", "One"), song("same", "Two"), song("other", "Other")),
                "playlist_test"
            ).map { it.key },
            appended.map { it.key },
        )
    }

    private fun song(id: String, title: String) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 1_000L
    )
}
