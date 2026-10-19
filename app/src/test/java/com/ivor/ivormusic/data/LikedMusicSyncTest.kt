package com.ivor.ivormusic.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The removal regression: a song that is gone from `LM` after a successful
 * sync must leave the account cache, the device store and the merged list on
 * every path the walk can take.
 */
class LikedMusicSyncTest {

    private val now = 10L * LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS

    private fun song(id: String): Song = Song.fromYouTube(
        videoId = id,
        title = id,
        artist = "artist",
        album = "album",
        duration = 1_000L,
        thumbnailUrl = null,
    )

    private fun cached(vararg ids: String, total: Int = ids.size): StoredAccountLikes =
        StoredAccountLikes(
            accountKey = "account",
            lastFullSyncMs = now,
            totalCount = total,
            songs = ids.map(::song),
        )

    private fun page(ids: List<String>, next: String? = null, total: Int? = null): LikedMusicPage =
        LikedMusicPage(
            songs = ids.map(::song),
            nextCursor = next,
            totalCount = total,
            recentFirst = true,
        )

    private suspend fun walk(
        cached: StoredAccountLikes,
        pages: Map<String?, LikedMusicPage?>,
    ): LikedMusicSyncOutcome = walkLikedMusic(
        cached = cached,
        forceFull = false,
        nowMs = now,
        loadPage = { cursor -> pages[cursor] },
    )

    private class FakeDeviceStore(vararg ids: String) : DeviceLikedSongsStore {
        val ids = ids.toMutableSet()
        override fun isLiked(songId: String): Boolean = songId in ids
        override fun remove(songId: String) {
            ids.remove(songId)
        }
    }

    /**
     * The same assertions for every path: the id is in `removedIds`, absent
     * from the account cache, gone from the device store after the removal,
     * and absent from the list the screens draw.
     */
    private fun assertGoneEverywhere(
        outcome: LikedMusicSyncOutcome,
        id: String,
        device: FakeDeviceStore,
    ) {
        assertTrue("$id was not in removedIds: ${outcome.removedIds}", id in outcome.removedIds)
        assertFalse("$id is still in the account cache", outcome.songs.any { it.id == id })

        val removal = removeLikedSongsFromDevice(device, outcome.removedIds)
        assertFalse("$id is still in the device store", device.isLiked(id))
        assertFalse("the device store still reports $id", id in removal.stillLiked)

        val merged = mergeLikedSongsForDisplay(
            accountLiked = outcome.songs,
            manuallyLikedSongs = listOf(song(id)),
            manuallyLikedIds = device.ids,
            localSongs = emptyList(),
        )
        assertFalse("$id is still in the merged list", merged.any { it.id == id })
    }

    @Test
    fun `a Koda like removed on the web leaves every store on the incremental path`() = runBlocking {
        val hz = "hz5JWVIj7c8"
        val device = FakeDeviceStore(hz)

        val outcome = walk(
            cached = cached(hz, "a", "b", "c", "d", total = 5),
            pages = mapOf(
                null to page(listOf("a", "b", "c"), next = "p2", total = 4),
                "p2" to page(listOf("d")),
            ),
        )

        assertEquals(LikedMusicSyncPath.INCREMENTAL, outcome.path)
        assertGoneEverywhere(outcome, hz, device)
    }

    @Test
    fun `a web-only like removed on the web is not added to the device store`() = runBlocking {
        val hz = "hz5JWVIj7c8"
        // The device never held it: the like existed only in the account.
        val device = FakeDeviceStore()

        val outcome = walk(
            cached = cached(hz, "a", "b", "c", "d", total = 5),
            pages = mapOf(
                null to page(listOf("a", "b", "c"), next = "p2", total = 4),
                "p2" to page(listOf("d")),
            ),
        )

        assertEquals(LikedMusicSyncPath.INCREMENTAL, outcome.path)
        assertGoneEverywhere(outcome, hz, device)
        assertTrue("removing an absent id added it", device.ids.isEmpty())
    }

    @Test
    fun `the early-exit path removes the same ids`() = runBlocking {
        val hz = "hz5JWVIj7c8"
        val device = FakeDeviceStore(hz, "e")

        val outcome = walk(
            cached = cached("a", "b", "c", hz, "d", "e", total = 6),
            pages = mapOf(
                null to page(listOf("x", "a", "b"), next = "p2", total = 6),
                "p2" to page(listOf("c", "d"), next = "p3"),
                "p3" to page(listOf("f")),
            ),
        )

        assertEquals(LikedMusicSyncPath.FULL_EARLY_EXIT, outcome.path)
        assertTrue(outcome.stoppedEarly)
        assertGoneEverywhere(outcome, hz, device)
        assertFalse("e is still in the account cache", outcome.songs.any { it.id == "e" })
        assertFalse("e is still in the device store", device.isLiked("e"))
    }

    @Test
    fun `the full-walk completion path removes the same ids`() = runBlocking {
        val hz = "hz5JWVIj7c8"
        val device = FakeDeviceStore(hz)

        val outcome = walk(
            cached = cached("a", "b", "c", hz, total = 4),
            // No header count: the early-exit check cannot balance, so the
            // walk reaches the end and reconciles there.
            pages = mapOf(
                null to page(listOf("x", "a", "b"), next = "p2", total = null),
                "p2" to page(listOf("c")),
            ),
        )

        assertEquals(LikedMusicSyncPath.FULL_COMPLETE, outcome.path)
        assertGoneEverywhere(outcome, hz, device)
    }

    @Test
    fun `a partial full walk does not remove unread cached ids from the device store`() = runBlocking {
        val device = FakeDeviceStore("hz5JWVIj7c8", "unread")

        val outcome = walk(
            cached = cached("a", "b", "unread", "hz5JWVIj7c8", total = 4),
            pages = mapOf(
                null to page(listOf("x", "a", "b"), next = "p2", total = 4),
                "p2" to null,
            ),
        )

        assertEquals(LikedMusicSyncPath.FULL_PARTIAL, outcome.path)
        assertTrue("a partial walk must not claim removals", outcome.removedIds.isEmpty())
        assertTrue("an unread cached like was removed", device.isLiked("unread"))
        assertTrue("an unread cached like was removed", device.isLiked("hz5JWVIj7c8"))
    }

    @Test
    fun `a like from before a walk is not resurrected by that walk's result`() {
        val pending = LikedMusicPendingTaps()
        pending.markLiked(song("hz5JWVIj7c8"))

        val result = pending.merge(listOf(song("a")))

        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `a like during a walk survives it`() {
        val pending = LikedMusicPendingTaps()
        pending.beginWalk()
        pending.markLiked(song("hz5JWVIj7c8"))

        val result = pending.merge(listOf(song("a")))

        assertEquals(listOf("hz5JWVIj7c8", "a"), result.map { it.id })
    }

    @Test
    fun `an unlike during a walk wins over the pages`() {
        val pending = LikedMusicPendingTaps()
        pending.beginWalk()
        pending.markUnliked("a")

        val result = pending.merge(listOf(song("a"), song("b")))

        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `removing an absent device like is a no-op`() {
        val device = FakeDeviceStore()

        val first = removeLikedSongsFromDevice(device, setOf("hz5JWVIj7c8"))
        assertTrue(first.hadBefore.isEmpty())
        assertTrue(first.stillLiked.isEmpty())
        assertTrue(device.ids.isEmpty())

        val second = removeLikedSongsFromDevice(device, setOf("hz5JWVIj7c8"))
        assertTrue(second.hadBefore.isEmpty())
        assertTrue(device.ids.isEmpty())
    }

    @Test
    fun `orphaned metadata is not drawn without a liked id`() {
        val merged = mergeLikedSongsForDisplay(
            accountLiked = emptyList(),
            manuallyLikedSongs = listOf(song("hz5JWVIj7c8")),
            manuallyLikedIds = emptySet(),
            localSongs = emptyList(),
        )

        assertTrue(merged.isEmpty())
    }
}
