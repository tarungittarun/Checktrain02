package `in`.opentrack.india.ui

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
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

    private fun intermediate(code: String, name: String, arr: Int?, dep: Int?) = StopRow(
        0, code, name, null, null, null, arr, dep, 1, -1, false)

    @Test fun displaysOffRouteSourceEventsAndScheduleClocksWithSeparateLabels() {
        val text = WimtSupplementalRows.text(
            sourceEvents = linkedMapOf(
                "BDVT" to "BDVT · arr 05:39", // scheduled route row: never duplicate it
                "HHL" to "HHL · arr 05:53",
            ),
            wimtScheduleStops = listOf(
                intermediate("HHL", "Hirehalli", 5 * 60 + 30, 5 * 60 + 32),
                intermediate("KGI", "Kengeri", 6 * 60 + 2, null),
            ),
            route = route,
        ).orEmpty()

        assertTrue("HHL source event row missing from: $text",
            text.contains("HHL (HHL) · source event: arr 05:53"))
        assertTrue("HHL schedule clocks missing or conflated in: $text",
            text.contains("HHL (HHL) · source event: arr 05:53 · WIMT schedule: arr 05:30 / dep 05:32"))
        assertTrue(text.contains("Kengeri (KGI) · WIMT schedule: arr 06:02"))
        assertTrue(text.contains("Schedule clocks are not events"))
        assertTrue(text.contains("not GPS or verified position"))
        assertTrue("scheduled route rows should not be duplicated", !text.contains("BDVT"))
    }

    @Test fun sourceEventWithoutScheduleIsStillDisplayedAsAnEventClaim() {
        val text = WimtSupplementalRows.text(
            sourceEvents = mapOf("HHL" to "HHL · arr 05:53"),
            wimtScheduleStops = emptyList(),
            route = route,
        ).orEmpty()
        assertTrue(text.contains("HHL (HHL) · source event: arr 05:53"))
        assertTrue(text.contains("Schedule clocks are not events"))
    }

    @Test fun noSupplementalPanelWhenOnlyTimetableRouteRowsArePresent() {
        assertNull(WimtSupplementalRows.text(
            mapOf("BDVT" to "BDVT · arr 05:39"), emptyList(), route))
        assertNull(WimtSupplementalRows.text(emptyMap(), emptyList(), route))
    }

    @Test fun limitsVisibleRowsAndPointsToSourceDetailsForTheRemainder() {
        val rows = (1..6).map { intermediate("X%02d".format(it), "Station $it", it * 60, null) }
        val text = WimtSupplementalRows.text(emptyMap(), rows, route).orEmpty()

        assertTrue(text.contains("Station 1 (X01) · WIMT schedule: arr 01:00"))
        assertTrue(text.contains("Station 4 (X04)"))
        assertTrue(text.contains("2 more in Info"))
        assertTrue(!text.contains("Station 5 (X05)"))
    }
}
