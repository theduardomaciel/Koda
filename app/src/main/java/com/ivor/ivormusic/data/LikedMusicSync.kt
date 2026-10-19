package com.ivor.ivormusic.data

/**
 * How a [walkLikedMusic] call reached its answer.
 *
 * [INCREMENTAL] read the new prefix only. [FULL_EARLY_EXIT] walked the list
 * whole until the count balanced before the last page. [FULL_COMPLETE] reached
 * the end of the list. [FULL_PARTIAL] could not: a page failed, or the walk hit
 * [LIKED_MUSIC_MAX_PAGES], so its result is a prefix rather than the list.
 */
internal enum class LikedMusicSyncPath {
    INCREMENTAL,
    FULL_EARLY_EXIT,
    FULL_COMPLETE,
    FULL_PARTIAL,
}

/**
 * What one walk decided.
 *
 * [removedIds] are the cached ids the walk proved are no longer in `LM`. They
 * are the ids the caller must remove from every other store as well, or a
 * merged list puts them back on screen.
 *
 * [songs] is the account cache after the walk, before any tap that landed
 * while it was in flight is merged (the repository does that, because those
 * taps are process state). [totalCount] and [lastFullSyncMs] are the values to
 * persist for the path taken.
 */
internal data class LikedMusicSyncOutcome(
    val songs: List<Song>,
    val removedIds: Set<String>,
    val totalCount: Int,
    val path: LikedMusicSyncPath,
    val pagesRead: Int,
    val newCount: Int,
    val stoppedEarly: Boolean,
    val believe: Boolean,
    val lastFullSyncMs: Long,
    val serverCount: Int?,
    val lastPageMs: Long,
)

/**
 * The walk behind [AccountLikedSongsRepository.sync], with no Android or
 * process state in it: it reads pages through [loadPage], publishes progress
 * through [onProgress], and returns what the caller should store.
 *
 * It is deliberately separate from the repository so the three removal paths -
 * incremental, full-walk early exit, and full walk to the end - can be tested
 * against the same code the app runs. The pending-like and device-store
 * reconciliation lives in the repository because it is process state, not a
 * property of the list.
 */
internal suspend fun walkLikedMusic(
    cached: StoredAccountLikes,
    forceFull: Boolean,
    nowMs: Long,
    loadPage: suspend (cursor: String?) -> LikedMusicPage?,
    onProgress: (List<Song>) -> Unit = {},
): LikedMusicSyncOutcome {
    val cachedIds = cached.songs.mapTo(HashSet(cached.songs.size)) { it.id }

    // Phase 1: the incremental walk. Read page 1 onwards and stop at the first
    // id the cache already holds. `newItems` holds the items found before that
    // point; N is their count. `fullItems` holds everything read so far (all
    // of pages 1..k), so a later full walk only fetches from page k+1 instead
    // of re-reading page 1.
    val newItems = LinkedHashMap<String, Song>()
    val fullItems = LinkedHashMap<String, Song>()
    var cursor: String? = null
    var complete = false
    var stopped = false
    var totalCount: Int? = null
    var recentFirst = true
    val countedForN = mutableSetOf<String>()
    var incrementalN = 0
    var pages = 0
    var lastPageMs = 0L
    var page1Ids: List<String> = emptyList()
    var lastKnownPage1CacheIndex = -1

    while (pages < LIKED_MUSIC_MAX_PAGES) {
        val pageStart = System.currentTimeMillis()
        val page = loadPage(cursor) ?: break
        lastPageMs = System.currentTimeMillis() - pageStart
        pages++
        if (pages == 1) {
            totalCount = page.totalCount
            recentFirst = page.recentFirst
            page1Ids = page.songs.map { it.id }
            // Last cached id in page 1 (by page-1 position). Everything above
            // its cache position is the window R is counted in.
            val knownInPage1 = page1Ids.filter { it in cachedIds }
            val lastKnownId = knownInPage1.lastOrNull()
            lastKnownPage1CacheIndex = if (lastKnownId != null) {
                cached.songs.indexOfFirst { it.id == lastKnownId }
            } else {
                -1
            }
        }
        var hitKnown = false
        for (song in page.songs) {
            fullItems.putIfAbsent(song.id, song)
            if (hitKnown) continue
            if (stopsLikedMusicWalk(song.id, cachedIds)) {
                hitKnown = true
            } else {
                newItems.putIfAbsent(song.id, song)
                if (countedForN.add(song.id)) {
                    incrementalN++
                }
            }
        }
        onProgress(mergeLikedMusicProgress(newItems.values.toList(), cached.songs))
        val next = page.nextCursor
        cursor = next
        if (hitKnown) {
            stopped = true
            break
        }
        if (next == null) {
            complete = true
            break
        }
    }
    // Stopping at a known id on the last page still means every row was read:
    // there is no unseen tail. Treat that as a complete walk, so a removal on
    // that page is reconciled instead of being kept by a partial walk.
    if (stopped && cursor == null) complete = true

    val N = incrementalN
    // R = cached ids positioned above the last page-1 id that is known in the
    // cache, that are absent from page 1. These are removals visible in the
    // scanned window.
    val incrementalRemovedIds = if (lastKnownPage1CacheIndex > 0) {
        cached.songs.take(lastKnownPage1CacheIndex)
            .map { it.id }
            .filter { it !in page1Ids }
            .toSet()
    } else {
        emptySet()
    }
    val needFull = planLikedMusicSync(
        forceFull = forceFull,
        cacheEmpty = cached.songs.isEmpty(),
        readWholeList = complete,
        recentFirst = recentFirst,
        dueForFullResync = needsFullLikedMusicSync(
            lastFullSyncMs = cached.lastFullSyncMs,
            nowMs = nowMs,
            cachedCount = cached.songs.size,
            force = false,
        ),
        storedCount = cached.totalCount,
        serverCount = totalCount,
        newCount = N,
        removedCount = incrementalRemovedIds.size,
    )

    if (!needFull) {
        val afterRemovals = cached.songs.filterNot { it.id in incrementalRemovedIds }
        val result = mergeLikedMusicProgress(newItems.values.toList(), afterRemovals)
        return LikedMusicSyncOutcome(
            songs = result,
            removedIds = incrementalRemovedIds,
            totalCount = totalCount ?: cached.totalCount,
            path = LikedMusicSyncPath.INCREMENTAL,
            pagesRead = pages,
            newCount = N,
            stoppedEarly = false,
            believe = false,
            lastFullSyncMs = cached.lastFullSyncMs,
            serverCount = totalCount,
            lastPageMs = lastPageMs,
        )
    }

    // Phase 2: the whole list with early-exit. fullItems already holds pages
    // 1..k (everything the incremental walk read); continue from the cursor of
    // the last page fetched, so no page is re-read.
    val walked = LinkedHashMap<String, Song>(fullItems)
    while (pages < LIKED_MUSIC_MAX_PAGES && cursor != null) {
        val page = loadPage(cursor) ?: break
        pages++
        for (song in page.songs) {
            walked.putIfAbsent(song.id, song)
        }
        onProgress(mergeLikedMusicProgress(walked.values.toList(), cached.songs))
        // After each page, recompute N and R over pages read so far. N = items
        // in walked but not cached (additions); R = cached items not yet
        // walked. If the count balances, the remaining pages hold only cached
        // items, so the removals are already known.
        val walkedIds = walked.keys
        val newIds = walkedIds - cachedIds
        val removedIds = cachedIds - walkedIds
        val N = newIds.size
        val R = removedIds.size
        val server = totalCount ?: (cached.totalCount + N - R)
        if (shouldStopFullWalkEarly(
                storedCount = cached.totalCount,
                serverCount = totalCount,
                newCount = N,
                removedCount = R,
            )
        ) {
            val added = newIds.mapNotNull { walked[it] }
            val afterRemovals = cached.songs.filterNot { it.id in removedIds }
            val result = mergeLikedMusicProgress(newItems.values.toList() + added, afterRemovals)
            return LikedMusicSyncOutcome(
                songs = result,
                removedIds = removedIds,
                totalCount = server,
                path = LikedMusicSyncPath.FULL_EARLY_EXIT,
                pagesRead = pages,
                newCount = N,
                stoppedEarly = true,
                believe = true,
                lastFullSyncMs = nowMs,
                serverCount = totalCount,
                lastPageMs = lastPageMs,
            )
        }
        val next = page.nextCursor
        if (next == null) {
            complete = true
            break
        }
        cursor = next
    }

    val list = walked.values.toList()
    // An end reached with nothing read is either an account with no likes -
    // which the header states as a total of zero - or a page that answered a
    // shell. Only the first is a list to believe, so an empty walk with no
    // total leaves the cache alone.
    val believe = complete && (list.isNotEmpty() || totalCount == 0)
    // A partial walk is not evidence about the unread tail: only ids the walk
    // actually passed can be called removals. Otherwise a failed page would
    // delete every unread cached like from the device store.
    val removedIds = if (believe) cachedIds - walked.keys else emptySet()
    val result = if (believe) {
        reconcileLikedMusic(list)
    } else {
        mergeLikedMusicProgress(list, cached.songs)
    }
    return LikedMusicSyncOutcome(
        songs = result,
        removedIds = removedIds,
        totalCount = totalCount ?: cached.totalCount,
        path = if (complete) LikedMusicSyncPath.FULL_COMPLETE else LikedMusicSyncPath.FULL_PARTIAL,
        pagesRead = pages,
        newCount = (walked.keys - cachedIds).size,
        stoppedEarly = false,
        believe = believe,
        lastFullSyncMs = if (complete) nowMs else cached.lastFullSyncMs,
        serverCount = totalCount,
        lastPageMs = lastPageMs,
    )
}

/**
 * Likes and unlikes tapped while a walk is in flight.
 *
 * Only taps that land **during** a walk belong here. A tap made before the
 * walk is already in the cache snapshot the walk started from, so it needs no
 * protection: if `LM` no longer holds it, the walk is right to remove it. This
 * distinction is load-bearing. Keeping every like in the map until the next
 * apply made a song liked earlier in the session reappear at the top of the
 * cache after a sync that had just removed it - the sync removed it from the
 * walk result, then `apply` put it back from the map.
 *
 * Callers hold `AccountLikedSongsRepository`'s lock.
 */
internal class LikedMusicPendingTaps {
    private val liked = LinkedHashMap<String, Song>()
    private val unliked = HashSet<String>()
    private var walkInProgress = false

    /** Start a walk: clear anything left over and begin capturing taps. */
    fun beginWalk() {
        liked.clear()
        unliked.clear()
        walkInProgress = true
    }

    /** End a walk: taps after this are ordinary state writes, not protection. */
    fun finishWalk() {
        walkInProgress = false
        liked.clear()
        unliked.clear()
    }

    fun markLiked(song: Song) {
        unliked.remove(song.id)
        if (walkInProgress) liked[song.id] = song
    }

    fun markUnliked(songId: String) {
        liked.remove(songId)
        if (walkInProgress) unliked += songId
    }

    /**
     * The list the caller should publish or persist: pending likes in front,
     * pending unlikes removed, and each id once.
     */
    fun merge(songs: List<Song>): List<Song> =
        (liked.values.toList() + songs)
            .filterNot { it.id in unliked }
            .distinctBy { it.id }
}
