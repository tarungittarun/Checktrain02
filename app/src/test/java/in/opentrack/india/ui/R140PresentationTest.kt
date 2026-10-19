package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New display formatting must not change train-event identity or turn a page check into movement. */
class R140PresentationTest {
    @Test fun dayTwoAndThreeAppearOnlyAtRunDayTransitions() {
        assertEquals("01:38", RouteClockUi.clock(1538))
        assertEquals("day2", RouteClockUi.dayMarker(1440, 1439))
        assertEquals(null, RouteClockUi.dayMarker(1500, 1450))
        assertEquals("day3", RouteClockUi.dayMarker(2880, 2879))
        assertEquals(null, RouteClockUi.dayMarker(60, null))
        assertEquals("—", RouteClockUi.clock(null))
    }

    @Test fun oneLineReceiptDoesNotRepeatTheWebsiteStationOrCallItPosition() {
        val short = RefreshReceiptUi.short("website", "01:43:27",
            RefreshEvidence.Change.UNCHANGED,
            "Website answered at 01:43:27 · same last site stop, no newer event · position unknown",
            false)
        assertEquals("Checked 01:43:27 · website · no newer stop", short)
        assertFalse(short.contains("position"))
        assertEquals("Checked 01:43:27 · website · cached reply · no new event",
            RefreshReceiptUi.short("website", "01:43:27", RefreshEvidence.Change.NEW_STOP,
                "Cached HTTP body; previous report retained", true))
    }

    @Test fun firstIntermediateGapIsExactlyFifthShorterWithoutMovingOtherPoints() {
        val points = listOf(
            `in`.opentrack.india.domain.RouteBetween.LegPoint("A", "A", 12, .2f),
            `in`.opentrack.india.domain.RouteBetween.LegPoint("B", "B", 18, .6f),
        )
        val positions = LegGeometry.pointPositionsDp(points, 40)
        val bonus = 40 * 1.2f
        assertEquals(23f + (34f + bonus * .2f) * .8f, positions[0], .02f)
        assertEquals(34f + bonus * .4f, positions[1] - positions[0], .02f)
        assertTrue(positions[0] < positions[1])
    }
}
