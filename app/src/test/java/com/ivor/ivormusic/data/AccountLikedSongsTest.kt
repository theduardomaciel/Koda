package com.ivor.ivormusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the Liked Music cache's walk is built on: where an incremental
 * walk stops, how its progress is shown, what a whole walk replaces, when one
 * is due, and which account a file belongs to.
 */
class AccountLikedSongsTest {

    private fun song(id: String, dateAdded: Long? = null): Song = Song.fromYouTube(
        videoId = id,
        title = id,
        artist = "artist",
        album = "album",
        duration = 1_000L,
        thumbnailUrl = null,
    ).copy(dateAdded = dateAdded)

    @Test
    fun incrementalWalkStopsAtTheFirstKnownId() {
        val cached = setOf("c", "d")
        assertFalse(stopsLikedMusicWalk("a", cached))
        assertTrue(stopsLikedMusicWalk("c", cached))
    }

    @Test
    fun progressKeepsTheServedOrderAndNeverDuplicates() {
        val scanned = listOf(song("a"), song("b"))
        val cached = listOf(song("b"), song("c"))

        assertEquals(
            listOf("a", "b", "c"),
            mergeLikedMusicProgress(scanned, cached).map { it.id },
        )
    }

    @Test
    fun aWholeWalkReconcilesARemovalMadeElsewhere() {
        val walked = listOf(song("a"), song("c"), song("c"))

        assertEquals(
            listOf("a", "c"),
            reconcileLikedMusic(walked).map { it.id },
        )
    }

    @Test
    fun aFullSyncIsDueWhenForcedEmptyOrAWeekOld() {
        val now = 10L * LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS

        assertTrue(needsFullLikedMusicSync(now, now, cachedCount = 5, force = true))
        assertTrue(needsFullLikedMusicSync(now, now, cachedCount = 0, force = false))
        assertFalse(
            needsFullLikedMusicSync(
                lastFullSyncMs = now - 1,
                nowMs = now,
                cachedCount = 5,
                force = false,
            )
        )
        assertTrue(
            needsFullLikedMusicSync(
                lastFullSyncMs = now - LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS,
                nowMs = now,
                cachedCount = 5,
                force = false,
            )
        )
    }

    @Test
    fun aCacheBelongsToOnlyTheAccountItWasReadFrom() {
        assertTrue(likedMusicCacheBelongsToAccount("me@example.com", "me@example.com"))
        assertFalse(likedMusicCacheBelongsToAccount("me@example.com", "other@example.com"))
        assertFalse(likedMusicCacheBelongsToAccount("me@example.com", null))
        assertFalse(likedMusicCacheBelongsToAccount(null, "me@example.com"))
    }
}
