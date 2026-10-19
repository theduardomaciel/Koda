package com.ivor.ivormusic.data

import android.content.Context
import com.ivor.ivormusic.util.KLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One page of the signed-in account's Liked Music - `LM`, which the music host
 * answers as the browse id `VLLM`.
 *
 * [songs] are the rows in the order YouTube served them. [recentFirst] reports
 * whether that order is the account's own "recently added" one, worked out from
 * the rows' add times rather than from a sort label, because it is what makes
 * the incremental sync's early stop sound (see [stopsLikedMusicWalk]).
 *
 * [nextCursor] is null at the end of the list. A page that *failed* is a null
 * page rather than an empty one, so the walk can tell "the list ends here" from
 * "this page did not arrive" - the difference between a cache that is complete
 * and one that must not be trusted as complete.
 */
data class LikedMusicPage(
    val songs: List<Song>,
    val nextCursor: String?,
    val totalCount: Int? = null,
    val recentFirst: Boolean = true,
)

/**
 * The Liked Music cache as it sits on disk, so a launch shows the account's
 * likes before any request is made.
 *
 * [accountKey] is the account the list was read from. Profiles already separate
 * one account's file from another's, but a profile can be reconnected to a
 * different Google account, and serving the previous one's likes as the new
 * one's would then be wrong in a way nothing would correct: the incremental
 * sync only ever adds. A key that does not match drops the cache and the next
 * sync reads the list whole.
 */
@Serializable
internal data class StoredAccountLikes(
    val accountKey: String? = null,
    val lastFullSyncMs: Long = 0L,
    val totalCount: Int = 0,
    val songs: List<Song> = emptyList(),
)

/**
 * How long the cache goes before a sync reads the list whole, and therefore
 * before a song unliked on another device leaves it. A week is the price of the
 * only mechanism that sees a removal, and one walk a week is affordable at 17
 * requests; anything tighter trades rate limit for a staleness measured in a
 * handful of rows.
 */
internal const val LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * How many pages one walk may read. 100 rows a page, so this is 2,500 songs -
 * above the 1,612 this was built against, and a walk that hits it is treated as
 * incomplete rather than as the end of the list.
 */
internal const val LIKED_MUSIC_MAX_PAGES = 25

/**
 * A walk of Liked Music stops at this row: it is one the cache already holds,
 * so everything above it is what has been added since the last sync.
 *
 * Sound because an entry is *prepended* when a song is liked, and liking a song
 * already in the list moves it back to the top rather than reviving it where it
 * was [verified October 2026: a like on a song absent from `LM` put it at
 * position 0 of page one and left the rest in order; unliking it left the page
 * byte-for-byte as it was]. Only a lazily loaded list breaks that, which is why
 * the walk falls back to reading it whole when [LikedMusicPage.recentFirst] is
 * false.
 */
internal fun stopsLikedMusicWalk(videoId: String, cachedIds: Set<String>): Boolean =
    videoId in cachedIds

/**
 * What to show while a walk is in flight: the rows read so far, newest first,
 * in front of what the cache already had.
 *
 * Deduping by id is what keeps that list stable rather than merely additive -
 * on a full walk the pages read so far are the *newest* entries, so putting
 * them in front of the remaining cached ones reproduces the real order while
 * not yet re-reading the tail. Every page publishes this, so the list grows
 * downwards and nothing on screen is ever taken away and put back.
 */
internal fun mergeLikedMusicProgress(scanned: List<Song>, cached: List<Song>): List<Song> =
    (scanned + cached).distinctBy { it.id }

/**
 * The list after a walk that read the whole of it: the served order, and
 * nothing else.
 *
 * This is where a song unliked **elsewhere** leaves the cache - an incremental
 * walk cannot see a removal, since it stops before the tail, so a removal is
 * reconciled only by a walk that reached the end.
 */
internal fun reconcileLikedMusic(walked: List<Song>): List<Song> =
    walked.distinctBy { it.id }

/**
 * Whether this sync reads the whole list rather than just the new prefix: the
 * first one, one that has been due for a week, or one the user forced.
 *
 * The periodic full walk is the only thing that notices songs unliked on
 * another device, so it is not an optimisation to skip - it is the removal
 * reconciliation, priced at one walk a week.
 */
internal fun needsFullLikedMusicSync(
    lastFullSyncMs: Long,
    nowMs: Long,
    cachedCount: Int,
    force: Boolean,
): Boolean = when {
    force -> true
    cachedCount <= 0 -> true
    else -> nowMs - lastFullSyncMs >= LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS
}

/**
 * Whether the cache on disk belongs to the account signed in now. A null
 * [currentKey] is signed out, and nothing on the device is that account's to
 * show, so the answer is no.
 */
internal fun likedMusicCacheBelongsToAccount(storedKey: String?, currentKey: String?): Boolean =
    !currentKey.isNullOrBlank() && storedKey == currentKey

/**
 * The account's Liked Music, cached on the device.
 *
 * Koda reads `LM` for liked songs, which is the list a like both reads and
 * writes, and it is the account's own: read whole it is 17 pages on the account
 * this was built against (1,612 songs, about 20 MB, one request per page), which
 * is why it is cached rather than fetched. What is on screen comes from the
 * cache at every moment - the first frame of a cold start included - and the
 * network only ever fills the cache in.
 *
 * **Signed in, this is the source of truth for what the account has liked.** It
 * is not the same thing as [LikedSongsRepository], which is this device's likes
 * and works without a session at all; the two are merged for the Liked Songs
 * list. Unlike that store, nothing here is written by anything but a sync or a
 * like made in this app, so a song can leave it in exactly two ways: unliked
 * here, or a walk that reached the end of the account's list and did not find
 * it.
 *
 * **Not backed up**, like [HomeRecommendationCache] and [ArtistPhotoCache]: the
 * source of truth is the account, so a cache lost in a restore is rebuilt by
 * the next sync rather than carried. Profile copies and profile switches are
 * handled, though - the file travels with the profile, and a mismatched
 * account key on load drops it.
 *
 * **Process-wide, like [LikedSongsRepository] and for the same reason**: a like
 * tapped in the player is written through a `SongLikeActions` built by
 * `MusicService` or a `PlayerViewModel`, and has to be visible to the
 * `HomeViewModel` that draws it, each holding its own repository. The state and
 * the file are per profile, and the file records which account it belongs to.
 */
class AccountLikedSongsRepository(context: Context) {

    private val appContext = context.applicationContext

    /** The account's liked songs, newest first. Empty signed out. */
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    /**
     * Whether the account's list holds this song. A set lookup rather than a
     * scan: the player asks this each time the song changes, not per frame.
     */
    fun isLiked(songId: String): Boolean = songId in likedIds

    init {
        ensureLoaded()
    }

    /**
     * Bring the cache up to date with the account.
     *
     * One walk at a time: a second caller while one is running returns
     * immediately rather than queueing a duplicate, because the walk in flight
     * is already reading what the second one would ask for.
     *
     * The walk stops at the first row the cache already holds, so the ordinary
     * case - nothing, or a handful of new likes, since the last one - is a
     * single request. Everything it read before stopping goes in front of the
     * cache, and each page is published as it arrives; the list on screen is
     * never empty while the walk runs and never taken away and put back.
     *
     * It reads the list whole when the cache is empty, when the last full walk
     * is [LIKED_MUSIC_FULL_RESYNC_INTERVAL_MS] old, when [forceFull] is set, or
     * when page one did not come back newest-first. A whole walk that reached
     * the end *replaces* the cache, which is what reconciles a song unliked
     * elsewhere; one that failed part way through adds what it read and leaves
     * the rest, since a prefix of the list is not evidence about its tail.
     *
     * @param loader one page, or null when the page could not be read.
     */
    suspend fun sync(
        forceFull: Boolean = false,
        loader: suspend (cursor: String?) -> LikedMusicPage?,
    ) {
        if (!syncLock.tryLock()) return
        var pages = 0
        var full = false
        try {
            // The merges, dedupes and the snapshot are per-song work over up
            // to 2,500 rows. The loader already requests on IO; this keeps the
            // rest off the caller's dispatcher, which is the main one.
            withContext(Dispatchers.IO) {
                val profileId = ensureLoaded()
                // The walk's result belongs to the identity it started under.
                // A profile switch or a sign-out while it runs reloads the
                // state, and publishing into that would hand one account's
                // list to another - so both halves are checked again before
                // every publish.
                val accountKey = synchronized(LOCK) {
                    if (loadedFor == profileId) loadedAccountKey else null
                }
                // A tap that lands while this walk is in flight must win over
                // the page it was read from, which was fetched before it.
                synchronized(LOCK) {
                    likedDuringWalk.clear()
                    unlikedDuringWalk.clear()
                }
                val cached = snapshot()
                val cachedIds = cached.songs.mapTo(HashSet(cached.songs.size)) { it.id }
                full = needsFullLikedMusicSync(
                    lastFullSyncMs = cached.lastFullSyncMs,
                    nowMs = System.currentTimeMillis(),
                    cachedCount = cached.songs.size,
                    force = forceFull,
                )

                val scanned = LinkedHashMap<String, Song>()
                var cursor: String? = null
                var complete = false
                var stopped = false
                var totalCount: Int? = null

                while (pages < LIKED_MUSIC_MAX_PAGES) {
                    val page = loader(cursor) ?: break
                    pages++
                    if (pages == 1) {
                        totalCount = page.totalCount
                        if (!page.recentFirst && !full) {
                            KLog.w(TAG, "Liked Music did not come back newest-first; walking it whole")
                            full = true
                        }
                        // A removal on another device lowers the account's count
                        // before it lowers ours, so a drop means something left
                        // that an incremental walk (which only ever adds) cannot
                        // see. Read the list whole to reconcile it.
                        val firstCachedId = cached.songs.firstOrNull()?.id
                        val topChanged = firstCachedId != null && firstCachedId !in page.songs.map { it.id }
                        val countDropped = totalCount != null && totalCount < cached.songs.size
                        val cacheIncomplete = totalCount != null && totalCount > cached.songs.size + 5
                        if ((countDropped || topChanged || cacheIncomplete) && !full) {
                            KLog.w(
                                TAG,
                                "Liked Music changed (cached=${cached.songs.size}, server=$totalCount, " +
                                    "topChanged=$topChanged); walking it whole",
                            )
                            full = true
                        }
                    }
                    for (song in page.songs) {
                        if (!full && stopsLikedMusicWalk(song.id, cachedIds)) {
                            stopped = true
                            break
                        }
                        scanned.putIfAbsent(song.id, song)
                    }
                    publish(
                        profileId,
                        accountKey,
                        mergeLikedMusicProgress(scanned.values.toList(), cached.songs),
                    )
                    if (stopped) break
                    val next = page.nextCursor
                    if (next == null) {
                        complete = true
                        break
                    }
                    cursor = next
                }

                val walked = scanned.values.toList()
                val readWholeList = complete && (full || !stopped)
                // An end reached with nothing read is either an account with no
                // likes - which the header states as a total of zero - or a
                // page that answered a shell. Only the first is a list to
                // believe, so an empty walk with no total leaves the cache
                // alone.
                val believe = readWholeList && (walked.isNotEmpty() || totalCount == 0)
                val result = if (believe) reconcileLikedMusic(walked)
                else mergeLikedMusicProgress(walked, cached.songs)
                apply(
                    profileId = profileId,
                    accountKey = accountKey,
                    songs = result,
                    totalCount = totalCount ?: cached.totalCount,
                    lastFullSyncMs = if (readWholeList) System.currentTimeMillis()
                    else cached.lastFullSyncMs,
                )
                KLog.d(
                    TAG,
                    "Liked Music sync: pages=$pages full=$full complete=$complete stopped=$stopped " +
                        "cached=${cached.songs.size} -> ${result.size} of ${totalCount ?: "unknown"}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KLog.e(TAG, "Liked Music sync failed after $pages pages", e)
        } finally {
            syncLock.unlock()
        }
    }

    /**
     * A song liked in this app: the same thing the account's `like/like` is
     * adding, so the cache takes it at the top straight away rather than
     * waiting for a walk to find it.
     */
    fun markLiked(song: Song) {
        val profileId = ensureLoaded()
        synchronized(LOCK) {
            val entry = song.copy(dateAdded = System.currentTimeMillis())
            likedDuringWalk[song.id] = entry
            unlikedDuringWalk.remove(song.id)
            publishLocked(mergeLikedMusicProgress(listOf(entry), state.songs))
        }
        persist(profileId)
    }

    /**
     * A song unliked in this app. An incremental walk cannot see a removal, so
     * without this the entry would sit in the cache until the next full walk.
     */
    fun markUnliked(songId: String) {
        val profileId = ensureLoaded()
        val changed = synchronized(LOCK) {
            likedDuringWalk.remove(songId)
            unlikedDuringWalk += songId
            if (state.songs.none { it.id == songId }) return@synchronized false
            publishLocked(state.songs.filterNot { it.id == songId })
            true
        }
        if (changed) persist(profileId)
    }

    /**
     * Signing out: the account's list is not this device's to keep showing. The
     * file is left as it is, so signing the same account back in finds it again
     * and the next sync is the incremental one.
     */
    fun clear() {
        synchronized(LOCK) {
            likedDuringWalk.clear()
            unlikedDuringWalk.clear()
            state = StoredAccountLikes()
            publishLocked(emptyList())
            // Reload on the way back in: the file still holds this account's
            // list, and the next ensureLoaded should find it rather than treat
            // a filled memory as current.
            loadedAccountKey = null
        }
    }

    // --- Loading, saving and the profile every write lands on ---

    /**
     * The active profile, with the state reloaded first if it is another
     * profile's or another account's. Every read of [state] and every write
     * goes through here, so a path that changes the profile without going
     * through `AccountSwitcher` can show stale likes for a moment but can never
     * write them into the wrong profile.
     */
    private fun ensureLoaded(): String {
        val active = ProfileManager.requireActiveProfileId(appContext)
        val key = currentAccountKey()
        if (loadedFor == active && loadedAccountKey == key) return active
        synchronized(LOCK) {
            if (loadedFor != active || loadedAccountKey != key) loadLocked(active, key)
        }
        return active
    }

    /** Caller holds [LOCK]. */
    private fun loadLocked(profileId: String, accountKey: String?) {
        val stored = readStored(profileId)
        state = if (likedMusicCacheBelongsToAccount(stored.accountKey, accountKey)) stored
        else StoredAccountLikes(accountKey = accountKey)
        publishLocked(state.songs)
        loadedFor = profileId
        loadedAccountKey = accountKey
    }

    private fun readStored(profileId: String): StoredAccountLikes {
        val file = fileFor(appContext, profileId)
        if (!file.isFile) return StoredAccountLikes()
        return try {
            json.decodeFromString<StoredAccountLikes>(file.readText())
        } catch (e: Exception) {
            KLog.e(TAG, "Could not read the Liked Music cache", e)
            StoredAccountLikes()
        }
    }

    private fun snapshot(): StoredAccountLikes = synchronized(LOCK) { state }

    /** Caller holds [LOCK]. */
    private fun publishLocked(songs: List<Song>) {
        state = state.copy(songs = songs)
        _songs.value = songs
        likedIds = songs.mapTo(HashSet(songs.size)) { it.id }
    }

    /**
     * A page read so far goes on screen, unless the identity it was read for
     * is no longer the one loaded. A tap that landed meanwhile is put in front
     * of it, or kept out of it, because it describes the list as it is now and
     * the page was fetched before it.
     */
    private fun publish(profileId: String, accountKey: String?, songs: List<Song>) {
        synchronized(LOCK) {
            if (loadedFor != profileId || loadedAccountKey != accountKey) return
            publishLocked(
                (likedDuringWalk.values.toList() + songs)
                    .filterNot { it.id in unlikedDuringWalk }
                    .distinctBy { it.id }
            )
        }
    }

    private fun apply(
        profileId: String,
        accountKey: String?,
        songs: List<Song>,
        totalCount: Int,
        lastFullSyncMs: Long,
    ) {
        var applied = false
        synchronized(LOCK) {
            if (loadedFor != profileId || loadedAccountKey != accountKey) return@synchronized
            val result = (likedDuringWalk.values.toList() + songs)
                .filterNot { it.id in unlikedDuringWalk }
                .distinctBy { it.id }
            state = StoredAccountLikes(
                accountKey = state.accountKey,
                lastFullSyncMs = lastFullSyncMs,
                totalCount = totalCount,
                songs = result,
            )
            publishLocked(result)
            likedDuringWalk.clear()
            unlikedDuringWalk.clear()
            applied = true
        }
        if (applied) persist(profileId)
    }

    /**
     * Written off the caller's thread: [markLiked] is a tap in the player, and
     * a store this size must not be encoded and written on the frame it lands
     * on. The state is read inside the write, so two writes landing out of
     * order still leave the newest one on disk.
     */
    private fun persist(profileId: String) {
        writerScope.launch {
            writeLock.withLock {
                val current = snapshot()
                try {
                    fileFor(appContext, profileId).writeText(json.encodeToString(current))
                } catch (e: Exception) {
                    KLog.e(TAG, "Could not write the Liked Music cache", e)
                }
            }
        }
    }

    /**
     * The account the session belongs to, or null signed out. The profile id is
     * already in the file name; this is the second half of the identity, for a
     * profile reconnected to another Google account. A renamed account reads as
     * a new one, which costs a walk and cannot be wrong.
     */
    private fun currentAccountKey(): String? =
        SessionManager(appContext).getUserName()?.trim()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "AccountLikedSongs"

        /** Consumers see this file as [fileFor]; the backup copies it by name. */
        internal const val FILE_BASE = "account_liked_songs"

        private val json = Json { ignoreUnknownKeys = true }

        // --- Process-wide state, like LikedSongsRepository's: a like tapped in
        // the service must be visible to the ViewModel drawing the list. ---
        private val _songs = MutableStateFlow<List<Song>>(emptyList())
        private val LOCK = Any()

        /** Ids in [state], so a heart check is a set lookup. Guarded by [LOCK]. */
        @Volatile
        private var likedIds: Set<String> = emptySet()

        /** A song liked while a walk runs; its result cannot drop it. [LOCK]. */
        private val likedDuringWalk = LinkedHashMap<String, Song>()

        /** A song unliked while a walk runs; its result cannot revive it. [LOCK]. */
        private val unlikedDuringWalk = HashSet<String>()

        /** One walk at a time. */
        private val syncLock = Mutex()

        /** Serialises the file writes; the newest state wins. */
        private val writeLock = Mutex()
        private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The profile whose list is in [state], or null before the first load. */
        @Volatile
        private var loadedFor: String? = null

        /** The account [state] was read from, for the same question. */
        @Volatile
        private var loadedAccountKey: String? = null

        /** The in-memory truth; [_songs] mirrors its `songs`. */
        @Volatile
        private var state = StoredAccountLikes()

        /** [profileId]'s cache file, for the backup and a new profile's copy. */
        internal fun fileFor(context: Context, profileId: String): File = File(
            context.applicationContext.filesDir,
            ProfileManager.historyScopedFileName(FILE_BASE, "json", profileId, context)
        )

        /** Re-seed the shared state from the newly active profile's cache. */
        fun reloadForActiveProfile(context: Context) {
            AccountLikedSongsRepository(context).ensureLoaded()
        }

        /** Give [toProfileId] a copy of [fromProfileId]'s cache, if it has none. */
        internal fun copyProfileData(context: Context, fromProfileId: String, toProfileId: String) {
            ProfileManager.copyScopedFile(
                fileFor(context, fromProfileId),
                fileFor(context, toProfileId),
            )
        }
    }
}
