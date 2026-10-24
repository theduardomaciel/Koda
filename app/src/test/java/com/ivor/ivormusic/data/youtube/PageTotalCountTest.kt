package com.ivor.ivormusic.data.youtube

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTotalCountTest {

    private fun runs(value: String) = JSONObject().put(
        "runs",
        JSONArray().put(JSONObject().put("text", value)),
    )

    private fun header(secondSubtitle: String? = null, subtitle: String? = null): JSONObject {
        val header = JSONObject()
        if (secondSubtitle != null) header.put("secondSubtitle", runs(secondSubtitle))
        if (subtitle != null) header.put("subtitle", runs(subtitle))
        return JSONObject().put("musicResponsiveHeaderRenderer", header)
    }

    @Test
    fun readsTheTrackCountFromTheFullSecondSubtitle() {
        assertEquals(
            23,
            parsePageTotalCount(
                header(secondSubtitle = "347K views • 23 tracks • 1 hour, 7 minutes"),
            ),
        )
    }

    @Test
    fun readsThousandsAndSingleUnits() {
        assertEquals(1454, parsePageTotalCount(header(secondSubtitle = "1,454 tracks")))
        assertEquals(7, parsePageTotalCount(header(secondSubtitle = "7 songs")))
        assertEquals(1, parsePageTotalCount(header(secondSubtitle = "1 song")))
    }

    @Test
    fun fallsBackToTheSubtitle() {
        assertEquals(12, parsePageTotalCount(header(subtitle = "12 tracks")))
    }

    @Test
    fun ignoresHeadersWithoutACount() {
        assertNull(parsePageTotalCount(header(secondSubtitle = "Playlist • Public • 2026")))
        assertNull(parsePageTotalCount(JSONObject()))
    }
}
