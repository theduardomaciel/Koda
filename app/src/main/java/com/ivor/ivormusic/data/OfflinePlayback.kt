package com.ivor.ivormusic.data

/**
 * Whether this song can be played with no connection.
 *
 * A download and a device file are both stored as [SongSource.LOCAL] with a
 * real URI. A liked YouTube song that was never downloaded is not playable
 * offline, even though its metadata is in the likes cache. The downloaded id
 * set is checked as well because it is the authoritative record of what Koda
 * downloaded, including legacy rows whose source was not rewritten.
 */
fun Song.isPlayableOffline(downloadedIds: Set<String>): Boolean =
    id in downloadedIds || (source == SongSource.LOCAL && uri != null)
