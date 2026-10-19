package com.ivor.ivormusic.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCacheSnapshotTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `snapshot round-trips the account lists`() {
        val snapshot = LibraryCacheSnapshot(
            savedAtMs = 1_700_000_000_000L,
            playlists = listOf(
                PlaylistDisplayItem(
                    name = "Late night",
                    url = "https://music.youtube.com/playlist?list=PL123",
                    uploaderName = "Me",
                    itemCount = 12,
                    thumbnailUrl = "https://example.invalid/playlist.jpg",
                )
            ),
            albums = listOf(
                PlaylistDisplayItem(
                    name = "HIT ME HARD AND SOFT",
                    url = "https://music.youtube.com/browse/MPREb_Wp9Aj8HpTsB",
                    uploaderName = "Billie Eilish",
                    releaseType = MusicReleaseType.ALBUM,
                    releaseYear = 2024,
                )
            ),
            artists = listOf(
                ArtistItem(
                    id = "UCvWU1_2",
                    name = "Billie Eilish",
                    thumbnailUrl = "https://example.invalid/artist.jpg",
                    subscriberCount = "100M subscribers",
                    isVerified = true,
                )
            ),
        )

        assertEquals(snapshot, json.decodeFromString<LibraryCacheSnapshot>(json.encodeToString(snapshot)))
    }

    @Test
    fun `unknown fields are ignored when a newer build wrote the file`() {
        val decoded = json.decodeFromString<LibraryCacheSnapshot>(
            """
            {
              "savedAtMs": 7,
              "playlists": [],
              "albums": [],
              "artists": [],
              "futureField": { "unused": true }
            }
            """.trimIndent()
        )

        assertEquals(LibraryCacheSnapshot(savedAtMs = 7), decoded)
    }
}
