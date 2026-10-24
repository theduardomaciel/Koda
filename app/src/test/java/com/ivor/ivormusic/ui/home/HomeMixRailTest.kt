package com.ivor.ivormusic.ui.home

import com.ivor.ivormusic.data.PlaylistDisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMixRailTest {

    private fun item(name: String, id: String) = PlaylistDisplayItem(
        name = name,
        url = "https://music.youtube.com/playlist?list=$id",
        uploaderName = "",
    )

    @Test
    fun libraryMixAndHomeMixesShareOneRailWithoutRepeatingDiscover() {
        val library = listOf(
            item("Discover Mix", "RDTMAK5uy_discover"),
            item("Late night mixtape", "PLmine"),
        )
        val home = listOf(
            item("Discover Mix", "RDTMAK5uy_discover"),
            item("Replay Mix", "RDTMAK5uy_replay"),
            item("Morning Focus", "RDTMAK5uy_melancolia"),
        )

        assertEquals(
            listOf("Discover Mix", "Replay Mix", "Morning Focus"),
            homeMixRail(library, home).map { it.name },
        )
    }

    @Test
    fun personalizedHomeMixNamesNeedNoMixWord() {
        val rail = homeMixRail(emptyList(), listOf(item("Deep Focus", "RDTMAK5uy_reflexoes")))

        assertEquals(listOf("Deep Focus"), rail.map { it.name })
    }

    @Test
    fun archiveMixIsAnAutoMixAndAUserMixtapeIsNot() {
        assertTrue(isAutoMix("Archive Mix"))
        assertTrue(isAutoMix("My Mix 3"))
        assertTrue(isAutoMix("My Supermix"))
        assertFalse(isAutoMix("Late night mixtape"))
        assertFalse(isAutoMix("Remix"))
    }
}
