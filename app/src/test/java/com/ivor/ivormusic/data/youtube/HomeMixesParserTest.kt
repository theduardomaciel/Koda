package com.ivor.ivormusic.data.youtube

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's generated mixes are radio playlists (`RD...` ids) on two shelves:
 * "Fresh finds, old favorites" names them, "Mixed for you" does not.
 */
class HomeMixesParserTest {

    private fun text(value: String) = JSONObject().put(
        "runs",
        JSONArray().put(JSONObject().put("text", value)),
    )

    private fun card(title: String, browseId: String) = JSONObject().put(
        "musicTwoRowItemRenderer",
        JSONObject()
            .put("title", text(title))
            .put("subtitle", JSONObject().put("simpleText", "50 songs"))
            .put(
                "navigationEndpoint",
                JSONObject().put("browseEndpoint", JSONObject().put("browseId", browseId)),
            ),
    )

    private fun section(title: String, vararg items: JSONObject) = JSONObject().put(
        "musicCarouselShelfRenderer",
        JSONObject()
            .put(
                "header",
                JSONObject().put(
                    "musicCarouselShelfBasicHeaderRenderer",
                    JSONObject().put("title", text(title)),
                ),
            )
            .put("contents", JSONArray().apply { items.forEach { put(it) } }),
    )

    private fun songRow(title: String, videoId: String) = JSONObject().put(
        "musicResponsiveListItemRenderer",
        JSONObject()
            .put(
                "flexColumns",
                JSONArray().put(
                    JSONObject().put(
                        "musicResponsiveListItemFlexColumnRenderer",
                        JSONObject().put("text", text(title)),
                    ),
                ),
            )
            .put(
                "navigationEndpoint",
                JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId)),
            ),
    )

    private fun songCard(title: String, videoId: String) = JSONObject().put(
        "musicTwoRowItemRenderer",
        JSONObject()
            .put("title", text(title))
            .put(
                "navigationEndpoint",
                JSONObject().put("watchEndpoint", JSONObject().put("videoId", videoId)),
            ),
    )

    private fun home(vararg sections: JSONObject) = JSONObject()
        .put(
            "contents",
            JSONObject().put(
                "singleColumnBrowseResultsRenderer",
                JSONObject().put(
                    "tabs",
                    JSONArray().put(
                        JSONObject().put(
                            "tabRenderer",
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "sectionListRenderer",
                                    JSONObject().put(
                                        "contents",
                                        JSONArray().apply { sections.forEach { put(it) } },
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        .toString()

    @Test
    fun readsTheNamedMixShelfAndThePersonalMixedForYouShelf() {
        val page = parseHomeContent(
            home(
                section(
                    "Fresh finds, old favorites",
                    card("Discover Mix", "VLRDTMAK5uy_discover"),
                    card("Replay Mix", "VLRDTMAK5uy_replay"),
                    card("Archive Mix", "VLRDTMAK5uy_archive"),
                    card("New Release Mix", "VLRDTMAK5uy_newrelease"),
                    card("Liked Music", "VLLM"),
                    card("brasil.", "VLPLFgjsOp1Aqc"),
                ),
                section(
                    "Mixed for you",
                    card("My Supermix", "VLRDTMAK5uy_supermix"),
                    card("Morning Focus", "VLRDTMAK5uy_melancolia"),
                ),
                section(
                    "Bons sonhos",
                    card("Indie Lullaby", "VLRDCLAK5uy_lullaby"),
                ),
            ),
        )

        assertEquals(
            listOf(
                "Discover Mix",
                "Replay Mix",
                "Archive Mix",
                "New Release Mix",
                "My Supermix",
                "Morning Focus",
            ),
            page.mixes.map { it.name },
        )
        assertEquals(
            listOf(
                "RDTMAK5uy_discover",
                "RDTMAK5uy_replay",
                "RDTMAK5uy_archive",
                "RDTMAK5uy_newrelease",
                "RDTMAK5uy_supermix",
                "RDTMAK5uy_melancolia",
            ),
            page.mixes.map { it.id },
        )
        assertTrue(page.hasMixedForYouShelf)
    }

    @Test
    fun aPageWithoutTheCanonicalShelfKeepsOnlyNamesThatSayMix() {
        val page = parseHomeContent(
            home(
                section(
                    "Fresh finds, old favorites",
                    card("Replay Mix", "VLRDTMAK5uy_replay"),
                    card("A playlist somebody made", "VLPLmadebyuser"),
                ),
            ),
        )

        assertEquals(listOf("Replay Mix"), page.mixes.map { it.name })
        assertFalse(page.hasMixedForYouShelf)
    }

    @Test
    fun radioCardsOutsideTheMixShelvesAreNotMixesForYou() {
        val page = parseHomeContent(
            home(
                section(
                    "Indie & alternative",
                    card("Alternative All Day", "VLRDCLAK5uy_alternative"),
                ),
                section(
                    "Bons sonhos",
                    card("Wind & Rain", "VLRDCLAK5uy_rain"),
                ),
            ),
        )

        assertTrue(page.mixes.isEmpty())
    }

    @Test
    fun duplicateMixIdsAreKeptOnce() {
        val page = parseHomeContent(
            home(
                section("Fresh finds, old favorites", card("Discover Mix", "VLRDTMAK5uy_discover")),
                section("Mixed for you", card("Discover Mix", "VLRDTMAK5uy_discover")),
            ),
        )

        assertEquals(listOf("Discover Mix"), page.mixes.map { it.name })
    }

    @Test
    fun onePageYieldsRowsCardsAndMixesTogether() {
        val page = parseHomeContent(
            home(
                section("Quick picks", songRow("Pick", "pick1")),
                section("Listen again", songCard("Card", "card1")),
                section("Mixed for you", card("Morning Focus", "VLRDTMAK5uy_melancolia")),
            ),
        )

        assertEquals(listOf("Pick"), page.rows.map { it.title })
        assertEquals(listOf("Card"), page.cards.map { it.title })
        assertEquals(listOf("Morning Focus"), page.mixes.map { it.name })
        assertTrue(page.hasMixedForYouShelf)
    }
}
