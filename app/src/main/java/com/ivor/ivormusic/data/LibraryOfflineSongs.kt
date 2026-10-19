package com.ivor.ivormusic.data

/**
 * The downloaded tracks that belong to a YouTube album.
 *
 * Album downloads are stored as individual [Song] rows, not as a
 * [DownloadedPlaylist] snapshot, so the album page has nothing to look up by
 * playlist id when it is opened offline. The browse id on each downloaded
 * song is the identity that connects them back to the album; the name is the
 * fallback for downloads written before that field existed.
 */
fun offlineAlbumTracks(
    playlistId: String,
    albumName: String?,
    downloads: List<Song>,
): List<Song> {
    val name = albumName?.trim()?.takeIf { it.isNotEmpty() }
    return downloads
        .asSequence()
        .filter { song ->
            song.albumId == playlistId ||
                (name != null && song.album.trim().equals(name, ignoreCase = true))
        }
        .distinctBy { it.id }
        .toList()
        .sortedInAlbumOrder()
}
