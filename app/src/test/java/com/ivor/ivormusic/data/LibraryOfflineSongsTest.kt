package com.ivor.ivormusic.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOfflineSongsTest {

    @Test
    fun `album tracks match by browse id even when the names differ`() {
        val tracks = listOf(
            song("a", album = "Deluxe Edition", albumId = ALBUM_ID),
            song("b", album = "Standard", albumId = ALBUM_ID),
            song("c", album = "Something Else", albumId = "MPREb_other"),
        )

        assertEquals(
            listOf("a", "b"),
            offlineAlbumTracks(ALBUM_ID, "Standard", tracks).map { it.id }
        )
    }

    @Test
    fun `album tracks fall back to the name for downloads without a browse id`() {
        val tracks = listOf(
            song("a", album = "HIT ME HARD AND SOFT", albumId = null),
            song("b", album = "hit me hard and soft", albumId = null),
            song("c", album = "Other", albumId = null),
        )

        assertEquals(
            listOf("a", "b"),
            offlineAlbumTracks(ALBUM_ID, "Hit Me Hard and Soft", tracks).map { it.id }
        )
    }

    @Test
    fun `album tracks are distinct and follow album order`() {
        val tracks = listOf(
            song("third", album = "Album", albumId = ALBUM_ID, track = 3),
            song("first", album = "Album", albumId = ALBUM_ID, track = 1),
            song("first", album = "Album", albumId = ALBUM_ID, track = 1),
            song("second", album = "Album", albumId = ALBUM_ID, track = 2),
        )

        assertEquals(
            listOf("first", "second", "third"),
            offlineAlbumTracks(ALBUM_ID, null, tracks).map { it.id }
        )
    }

    private fun song(
        id: String,
        album: String,
        albumId: String?,
        track: Int? = null,
    ) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = album,
        duration = 0,
        source = SongSource.LOCAL,
        trackNumber = track,
        albumId = albumId,
    )

    private companion object {
        const val ALBUM_ID = "MPREb_Wp9Aj8HpTsB"
    }
}
