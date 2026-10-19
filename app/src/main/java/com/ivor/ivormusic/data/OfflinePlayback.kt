package com.ivor.ivormusic.data

/**
 * Whether this song can be played with no connection.
 *
 * [offlineIds] is everything the player can resolve without the network:
 * permanent downloads and streams the playback cache holds in full ("Ready
 * offline"). A device file is playable on its own, so a local source with a
 * real URI qualifies even when it is in neither set. A liked YouTube song
 * that is in neither place is not playable offline, even though its metadata
 * is in the likes cache.
 */
fun Song.isPlayableOffline(offlineIds: Set<String>): Boolean =
    id in offlineIds || (source == SongSource.LOCAL && uri != null)
