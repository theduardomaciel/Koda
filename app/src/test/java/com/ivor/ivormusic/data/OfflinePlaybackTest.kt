package com.ivor.ivormusic.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackTest {

    @Test
    fun `an offline id is playable even without a local uri`() {
        val song = song("cached", SongSource.YOUTUBE)

        assertTrue(song.isPlayableOffline(setOf("cached")))
    }

    @Test
    fun `an undownloaded youtube song is not playable offline`() {
        val song = song("stream", SongSource.YOUTUBE)

        assertFalse(song.isPlayableOffline(emptySet()))
    }

    @Test
    fun `a local song without a uri is not playable offline`() {
        val song = song("orphan", SongSource.LOCAL)

        assertFalse(song.isPlayableOffline(emptySet()))
    }

    private fun song(id: String, source: SongSource) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = 0,
        uri = null,
        source = source,
    )
}
