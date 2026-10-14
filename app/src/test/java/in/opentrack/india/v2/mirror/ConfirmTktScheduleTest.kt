package `in`.opentrack.india.v2.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field names, day, and the nested placement are from the 14-Oct-2026 CI 20689 capture. */
class ConfirmTktScheduleTest {
    private val day = "14-Oct-2026"
    private val rows = """[{"TrainNo":20689,"StationCode":"YPR","StationName":"Yesvantpur Jn",
        "ArrivalTime":"","DepartureTime":"09:15","Distance":"0.0","Day":1,
        "intermediateStations":[]},
        {"TrainNo":20689,"StationCode":"BAW","StationName":"CHIK BANAVAR",
        "ArrivalTime":"09:25","DepartureTime":"09:26","Distance":"9.0","Day":1,
        "intermediateStations":[{"StationName":"SOLDEVANAHALLI","StationCode":"SDVL",
            "ArrivalTime":"09:28","DepartureTime":"09:28","HaltMinutes":"","Day":1,"Distance":"11.0"}]},
        {"TrainNo":20689,"StationCode":"SMET","StationName":"Shivamogga Town",
        "ArrivalTime":"14:05","DepartureTime":"","Distance":"269.0","Day":1,
        "intermediateStations":[]}]"""
    private fun html(schedule: String = rows) = """<html><title>20689 Train Running Status</title>
        <div>Last Updated: 14 Oct 2026 10:34</div><div>Day 1 14-Oct</div>
        <script>var data={"TrainNo":20689,"Schedule":$schedule};</script></html>"""

    @Test fun capturedPageYieldsScheduledHaltsAndUntimedIntermediatePoints() {
        val result = ConfirmTktSchedule.parse(html(), "20689", day, "YPR", "SMET")!!
        assertEquals(listOf("YPR", "BAW", "SMET"), result.halts.stops.map { it.code })
        assertEquals(9, result.halts.stops[1].schedKm)
        assertEquals(listOf("YPR", "BAW", "SDVL", "SMET"),
            result.allStations.stops.map { it.code })
        val point = result.allStations.stops[2]
        assertFalse(point.isHalt)
        assertNull("embedded passage times are not published halt times", point.arrivalMin)
        assertNull(point.departureMin)
        assertEquals("SOLDEVANAHALLI", point.name)
    }

    @Test fun mismatchedTrainDayCorridorAndDuplicatePointsNeverBecomeARoute() {
        assertNull(ConfirmTktSchedule.parse(html(), "20652", day, "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", "13-Oct-2026", "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", "14-Oct-2025", "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", day, "SMET", "YPR"))
        assertNull(ConfirmTktSchedule.parse(html(rows.replace("\"SDVL\"", "\"YPR\"")),
            "20689", day, "YPR", "SMET"))
        assertTrue(ConfirmTktSchedule.parse(html(), "20689", day, "YPR", "SDVL") != null)
    }
}
