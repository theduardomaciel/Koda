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
    /**
     * The track count the page header prints ("23 tracks", "100 songs"), on
     * the first page only. Null when the header does not name one, or for a
     * generated mix whose length is open-ended.
     */
    val totalCount: Int? = null,
)
