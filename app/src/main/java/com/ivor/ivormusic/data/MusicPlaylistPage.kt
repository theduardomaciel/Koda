package com.ivor.ivormusic.data

/**
 * Exactly one page of a YouTube Music playlist, with the cursor that reaches
 * the next one.
 *
 * Duplicate songs are occurrences, not noise: a playlist may list the same
 * video twice, so a normal playlist's rows must not be deduplicated by id.
 */
data class MusicPlaylistPage(
    val songs: List<Song>,
    val continuation: String?,
)
