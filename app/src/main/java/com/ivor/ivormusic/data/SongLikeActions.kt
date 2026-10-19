package com.ivor.ivormusic.data

import android.content.Context
import com.ivor.ivormusic.util.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one place that decides what a Like tap on a song means.
 *
 * A like lives in two stores - this device and the Google account - and the
 * heart in the player, the song options sheet and the notification are a single
 * toggle over both. Three surfaces drive that toggle, so the rules live here
 * rather than being written out three times and drifting, the same way
 * [SubscriptionActions] holds the Subscribe ones.
 *
 * The rules:
 *
 * - **The device store is always written first**, and its verdict is what the
 *   caller draws. It is the store the Liked Songs list reads, and it is what
 *   fills the heart on the frame it was tapped; making the UI wait for a round
 *   trip would make every like feel dropped.
 * - **Signed in, the same tap is mirrored to the account's Liked Music.** The
 *   cached copy of that list takes it immediately, so the list and the counts
 *   agree with the heart before the request lands. That write is best-effort
 *   and off the UI's path: a like that did not reach YouTube is a like that is
 *   still on the device, which is the honest failure the user can see
 *   reversed, and a network error must never undo a local like.
 * - **Signed out, nothing is mirrored.** There is no account to write to.
 */
class SongLikeActions(
    context: Context,
    private val youtubeRepository: YouTubeRepository,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val likedSongs = LikedSongsRepository(appContext)
    private val accountLikedSongs = AccountLikedSongsRepository(appContext)
    private val sessionManager = SessionManager(appContext)

    /**
     * Liked on this device, or on the account the cache has read. The second
     * half is what keeps a song liked on another device from drawing an empty
     * heart once the sync has found it.
     */
    fun isLiked(songId: String): Boolean =
        likedSongs.isLiked(songId) || accountLikedSongs.isLiked(songId)

    /**
     * Toggle the song's like, returning whether it is liked now.
     *
     * The local verdict is returned rather than the account's, because the
     * account write has not happened yet and its outcome does not change what
     * the device knows.
     */
    fun toggleLike(song: Song): Boolean {
        val isNowLiked = likedSongs.toggleLike(song)
        if (sessionManager.isLoggedIn() && song.source == SongSource.YOUTUBE) {
            // The account's cache is what the Liked Songs list draws from, so
            // the tap lands there before the request does; the write below
            // only confirms it. A device file has nothing to add to the
            // account's list.
            if (isNowLiked) accountLikedSongs.markLiked(song)
            else accountLikedSongs.markUnliked(song.id)
        }
        mirrorToAccount(song.id, isNowLiked)
        return isNowLiked
    }

    private fun mirrorToAccount(songId: String, liked: Boolean) {
        if (!sessionManager.isLoggedIn()) return
        scope.launch {
            try {
                youtubeRepository.setSongLiked(songId, liked)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                KLog.w(TAG, "Could not mirror the like for $songId to the account", e)
            }
        }
    }

    private companion object {
        const val TAG = "SongLikeActions"
    }
}
