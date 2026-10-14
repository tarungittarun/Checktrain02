package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WimtCandidateTest {
    private val day = RailTime.istEpochDay(1791949713_000L)
    private val now = 1791961500_000L
    // Minimal dated capture from the 20689 trial. Some actual_* values are ETA or were
    // generated before the claimed departure: a key name is NOT evidence of an event.
    private val capture = """{
      "start_date":"14-10-2026","source_station":"YPR","destination_station":"SMET",
      "days_schedule":[
        {"station_code":"YPR","departed":true,"actual_arrival_tm":1791949713,
         "actual_departure_tm":1791950020,"updated_at":"2026-10-14T09:25:49.710676+05:30"},
        {"station_code":"BAW","departed":true,"actual_arrival_tm":1791950499,
         "actual_departure_tm":1791950633,"updated_at":"2026-10-14T09:32:24.542168+05:30"},
        {"station_code":"VNR","actual_arrival_tm":1791960456},
        {"station_code":"RRB","source":"eta","departed":true,"actual_arrival_tm":1791959520,
         "updated_at":"2026-10-14T12:30:00.000000+05:30"},
        {"station_code":"SMET","departed":true,"actual_arrival_tm":1791966900,
         "updated_at":"2026-10-14T12:30:00.000000+05:30"}
      ]} """

    @Test fun `accepts only event times preceded by a separate later update`() {
        val r = WimtCandidate.parse(capture, day, "YPR", "SMET", now,
            setOf("YPR", "BAW", "VNR", "RRB", "SMET"))
        assertEquals(2, r.lines.size)
        assertTrue(r.lines["YPR"]!!.contains("arrival 09:18"))
        assertTrue(r.lines["YPR"]!!.contains("departure 09:23"))
        assertTrue(r.lines["BAW"]!!.contains("arrival 09:31"))
        assertFalse(r.lines["BAW"]!!.contains("departure"))
        assertFalse("a future ETA is not an arrival", r.lines.containsKey("RRB"))
        assertFalse("current station without departed flag is not an arrival", r.lines.containsKey("VNR"))
        assertFalse(r.lines.containsKey("SMET"))
        assertTrue(r.detail.contains("1 ETA rows"))
    }

    @Test fun `wrong run route and unknown codes never yield candidate minutes`() {
        assertTrue(WimtCandidate.parse(capture, day - 1, "YPR", "SMET", now).lines.isEmpty())
        assertTrue(WimtCandidate.parse(capture, day, "TLGP", "SBC", now).lines.isEmpty())
        assertTrue(WimtCandidate.parse(capture, day, "YPR", "SMET", now, setOf("ASK"))
            .lines.isEmpty())
    }

    @Test fun `manual fetch is exactly one GET and transports its run date`() {
        var calls = 0
        val r = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) { url ->
            calls++
            assertTrue(url.contains("train_no=20689"))
            assertTrue(url.contains("date=14-10-2026"))
            capture to "ok"
        }
        assertEquals(1, calls)
        assertEquals(setOf("YPR"), r.lines.keys)
    }
}
