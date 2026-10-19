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

    /**
     * The decision a sync makes after its incremental walk: the server count
     * against what the stored count plus the new items predicts.
     */
    private fun plan(
        storedCount: Int,
        serverCount: Int?,
        newCount: Int,
        removedCount: Int = 0,
        forceFull: Boolean = false,
        cacheEmpty: Boolean = false,
        readWholeList: Boolean = false,
        recentFirst: Boolean = true,
        dueForFullResync: Boolean = false,
    ): Boolean = planLikedMusicSync(
        forceFull = forceFull,
        cacheEmpty = cacheEmpty,
        readWholeList = readWholeList,
        recentFirst = recentFirst,
        dueForFullResync = dueForFullResync,
        storedCount = storedCount,
        serverCount = serverCount,
        newCount = newCount,
        removedCount = removedCount,
    )

    @Test
    fun aLikeMadeInKodaIsOneRequestTheNextTime() {
        // The like is written to LM by the app, so the stored count already
        // includes it and the server's count matches with nothing new found.
        assertFalse(plan(storedCount = 1666, serverCount = 1666, newCount = 0))
    }

    @Test
    fun aLikeMadeOnTheWebIsOneRequestToo() {
        // One new item found, and the server's count is one higher.
        assertFalse(plan(storedCount = 1665, serverCount = 1666, newCount = 1))
    }

    @Test
    fun anUnlikeMadeOnTheWebNeedsTheWholeList() {
        // Nothing new found, and the server's count is one lower: a removal.
        assertTrue(plan(storedCount = 1665, serverCount = 1664, newCount = 0))
    }

    @Test
    fun webUnlikeInsidePage1IsIncremental() {
        // R=1 removal visible in page 1, N=0 new items, server = stored-1.
        // expected = 1665 + 0 - 1 = 1664 = server -> incremental.
        assertFalse(plan(storedCount = 1665, serverCount = 1664, newCount = 0, removedCount = 1))
    }

    @Test
    fun likeAndUnlikeBothInsidePage1IsIncremental() {
        // N=1 addition, R=1 removal, server = stored. expected = 1665 + 1 - 1 = 1665 = server -> incremental.
        assertFalse(plan(storedCount = 1665, serverCount = 1665, newCount = 1, removedCount = 1))
    }

    @Test
    fun unlikeDeeperThanPage1NeedsTheWholeList() {
        // R=0 (removal not visible in page 1), server = stored-1. expected = 1665 + 0 - 0 = 1665 != 1664 -> full walk.
        assertTrue(plan(storedCount = 1665, serverCount = 1664, newCount = 0, removedCount = 0))
    }

    @Test
    fun unlikeOfTopItemIsIncremental() {
        // N=0, R=1 (top removal visible in page 1), server = stored-1.
        // expected = 1665 + 0 - 1 = 1664 = server -> incremental.
        assertFalse(plan(storedCount = 1665, serverCount = 1664, newCount = 0, removedCount = 1))
    }

    @Test
    fun unlikeOfTopItemPlusAnotherLikeIsIncremental() {
        // N=1, R=1, server = stored. expected = 1665 + 1 - 1 = 1665 = server -> incremental.
        assertFalse(plan(storedCount = 1665, serverCount = 1665, newCount = 1, removedCount = 1))
    }

    @Test
    fun offByOneAtPage1BoundaryAfterRemovalNeedsTheWholeList() {
        // Removal at the page-1/page-2 boundary: R=0 (not visible in page 1),
        // server = stored-1. Full walk needed.
        assertTrue(plan(storedCount = 1665, serverCount = 1664, newCount = 0, removedCount = 0))
    }

    @Test
    fun aLikeAndAnUnlikeBetweenTwoSyncsNeedsTheWholeList() {
        // One new item found, but the server's count is unchanged: something
        // was removed as well, and only the whole list shows what.
        assertTrue(plan(storedCount = 1665, serverCount = 1665, newCount = 1))
    }

    @Test
    fun theOtherTriggersStillReadTheWholeList() {
        assertTrue(plan(storedCount = 1665, serverCount = 1665, newCount = 0, forceFull = true))
        assertTrue(plan(storedCount = 0, serverCount = 1665, newCount = 0, cacheEmpty = true))
        assertTrue(plan(storedCount = 1665, serverCount = 1665, newCount = 0, readWholeList = true))
        assertTrue(plan(storedCount = 1665, serverCount = 1665, newCount = 0, recentFirst = false))
        assertTrue(plan(storedCount = 1665, serverCount = 1665, newCount = 0, dueForFullResync = true))
    }

    @Test
    fun anUnknownServerCountMeansTheWholeList() {
        // A page that did not arrive says nothing about what was removed.
        assertTrue(plan(storedCount = 1665, serverCount = null, newCount = 0))
    }
}
