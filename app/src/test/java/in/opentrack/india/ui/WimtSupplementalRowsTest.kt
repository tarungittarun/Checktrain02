package `in`.opentrack.india.ui

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WimtSupplementalRowsTest {
    private val routeStops = listOf("SBC", "YPR", "TK", "BDVT", "TLGP").mapIndexed { i, code ->
        StopRow(i + 1, code, code, null, null, null, null, null, 1, i * 50, true)
    }
    private val route = TrainRoute("20652", "TEST", "SBC", "Bengaluru", "TLGP", "Talguppa",
        200, 600, 0x7f, null, routeStops,
        routeStops.zipWithNext().mapIndexed { i, (from, to) ->
            SegmentRow(i + 1, from.code, to.code, to.schedKm - from.schedKm, 60)
        })

    @Test fun displaysSourceEventRowsNotPresentInTheSelectedTimetable() {
        val text = WimtSupplementalRows.text(linkedMapOf(
            "BDVT" to "BDVT · arr 05:39", // already a scheduled route row
            "HHL" to "HHL · arr 05:53",
            "KGI" to "KGI · dep 06:02",
        ), route).orEmpty()

        assertTrue(text.contains("WIMT .in source-reported station events outside this timetable"))
        assertTrue(text.contains("HHL · arr 05:53"))
        assertTrue(text.contains("KGI · dep 06:02"))
        assertTrue(text.contains("not scheduled route stops, GPS, or verified position"))
        assertTrue("scheduled route rows should not be duplicated", !text.contains("BDVT · arr"))
    }

    @Test fun noSupplementalPanelWhenAllSourceRowsMapToTheTimetable() {
        assertNull(WimtSupplementalRows.text(mapOf("BDVT" to "BDVT · arr 05:39"), route))
        assertNull(WimtSupplementalRows.text(emptyMap(), route))
    }

    @Test fun limitsVisibleRowsAndPointsToSourceDetailsForTheRemainder() {
        val rows = (1..6).associate { "X%02d".format(it) to "X%02d · arr 05:%02d".format(it, it) }
        val text = WimtSupplementalRows.text(rows, route).orEmpty()

        assertTrue(text.contains("X01 · arr 05:01"))
        assertTrue(text.contains("X04 · arr 05:04"))
        assertTrue(text.contains("2 more in Info"))
        assertTrue(!text.contains("X05 · arr"))
    }
}
