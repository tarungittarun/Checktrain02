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
        {"station_code":"YPR","source":"live","departed":true,"actual_arrival_tm":1791949713,
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
        assertTrue(r.lines["YPR"]!!.contains("[field actual_arrival_tm]"))
        assertTrue(r.lines["YPR"]!!.contains("departure 09:23"))
        assertTrue(r.lines["YPR"]!!.contains("[field actual_departure_tm]"))
        assertTrue(r.lines["YPR"]!!.contains("cache source tag live"))
        assertTrue(r.lines["YPR"]!!.contains("row update 14 Oct 2026 09:25:49 IST"))
        assertTrue(r.detail.contains("cache-reported route YPR→SMET matched requested route"))
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

    @Test fun `web point requires its own recent event and independent route brackets`() {
        val event = 1792014679L // 15 Oct, 03:21 IST: BLKR's own arrival field
        val at = event * 1000 + 120_000L
        val sample = """{
          "start_date":"14-10-2026","source_station":"MYS","destination_station":"TLGP",
          "lastUpdateIsoDate":"2026-10-15T03:22:00.000000+05:30",
          "curStn":"BLKR","distance":338.285,
          "days_schedule":[
            {"station_code":"ASK","distance":302.9,"departed":true},
            {"station_code":"BLKR","curStn":true,"stops":false,
             "actual_arrival_tm":1792014679,"actual_departure_tm":1792014709},
            {"station_code":"RRB","source":"eta","distance":348.8}
          ]
        }""".trimIndent()
        val kms = linkedMapOf("ASK" to 304, "RRB" to 349)
        val point = WimtCandidate.parse(sample, day, "MYS", "TLGP", at,
            knownKms = kms).webPoint!!
        assertEquals("BLKR", point.code)
        assertEquals("ASK", point.precedingHalt)
        assertEquals("RRB", point.followingHalt)
        assertEquals(event * 1000, point.eventAtMillis)
        assertTrue("contradictory departure-before-arrival cannot locate a train",
            WimtCandidate.parse(sample.replace("1792014709", "1792011780"),
                day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue("a cache timestamp cannot revive an old event",
            WimtCandidate.parse(sample.replace("03:22:00", "03:33:00"),
                day, "MYS", "TLGP", at + 10 * 60_000, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"distance\":338.285", "\"distance\":400.0"),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"curStn\":true", "\"curStn\":false"),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"stops\":false", "\"source\":\"eta\""),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample, day, "MYS", "TLGP", at).webPoint == null)
        assertTrue(WimtCandidate.parse(sample, day - 1, "MYS", "TLGP", at,
            knownKms = kms).webPoint == null)
    }

    @Test fun `fresh cache clock cannot revive old contradictory 20689 station`() {
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
            .parse("2026-11-02 15:13:05 +0530")!!.time
        val run = RailTime.istEpochDay(at)
        // Public web-cache shape observed on 2 Nov, reduced to fields needed for this gate.
        // BDVT arrival was 13:35 while its 'actual departure' was schedule 12:52;
        // a fresh root timestamp is not proof the train is still there at 15:13.
        val sample = """{
          "start_date":"02-11-2026","source_station":"YPR","destination_station":"SMET",
          "lastUpdateIsoDate":"2026-11-02T15:13:02.000000+05:30",
          "curStn":"BDVT","distance":258.5,
          "days_schedule":[
            {"station_code":"BDVT","curStn":true,"stops":true,
             "actual_arrival_tm":1793606741,"actual_departure_tm":1793604120},
            {"station_code":"SMET","source":"eta","actual_arrival_tm":1793608500}
          ]
        }""".trimIndent()
        val kms = linkedMapOf("BDVT" to 246, "SMET" to 269)
        val result = WimtCandidate.parse(sample, run, "YPR", "SMET", at,
            setOf("BDVT", "SMET"), kms)
        assertTrue(result.lines.isEmpty())
        assertTrue(result.webPoint == null)
        assertTrue(result.detail.contains("no fresh, route-bracketed web position event"))
        val claimedDeparted = sample.replace("\"curStn\":true,\"stops\":true,",
            "\"curStn\":true,\"stops\":true,\"departed\":true," )
            .replace("\"actual_arrival_tm\":1793606741,",
                "\"updated_at\":\"2026-11-02T13:40:00.000000+05:30\"," +
                "\"actual_arrival_tm\":1793606741,")
        val past = WimtCandidate.parse(claimedDeparted, run, "YPR", "SMET", at,
            setOf("BDVT", "SMET"), kms)
        assertTrue(past.lines["BDVT"]?.contains("arrival 13:35") == true)
        assertFalse(past.lines["BDVT"]!!.contains("departure"))
        assertTrue(past.webPoint == null)
    }

    @Test fun `past next day station belongs to origin run not another day`() {
        val event = 1792014679L // 15 Oct 03:21 IST; origin run 14 Oct
        val sample = """{"start_date":"14-10-2026","source_station":"MYS",
          "destination_station":"TLGP","days_schedule":[
          {"station_code":"BLKR","departed":true,
           "actual_arrival_tm":1792014679,"actual_departure_tm":1792014739,
           "updated_at":"2026-10-15T03:23:30.000000+05:30"}]}"""
        val r = WimtCandidate.parse(sample, day, "MYS", "TLGP", event * 1000 + 180_000,
            setOf("BLKR"))
        assertTrue(r.lines["BLKR"]?.contains("arrival") == true)
        assertTrue(r.lines["BLKR"]?.contains("departure") == true)
        assertTrue(WimtCandidate.parse(sample, day - 1, "MYS", "TLGP", event * 1000 + 180_000)
            .lines.isEmpty())
    }

    @Test fun `manual fetch is exactly one GET and transports its run date`() {
        var calls = 0
        val r = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) { url ->
            calls++
            assertEquals("https://whereismytrain.in/cache/live_status?" +
                "train_no=20689&date=14-10-2026&lang=en", url)
            assertFalse("no copied phone-app version", url.contains("appVersion"))
            assertFalse("no assumed first-day key", url.contains("from_day"))
            WimtCandidate.WireReply(capture, 200, "ok")
        }
        assertEquals(1, calls)
        assertEquals(200, r.responseStatus)
        assertTrue(r.responseReceived)
        assertEquals(setOf("YPR"), r.lines.keys)
        assertEquals(null, r.trainNumberMatched)
        assertTrue("the endpoint did not echo a train number in this fixture", !r.trainNumberEchoed)
        assertTrue(r.detail.contains("response train number not echoed; request used train 20689"))
        val fourDigit = WimtCandidate.fetch("1202", day, "YPR", "SMET", setOf("YPR"), now) { url ->
            assertTrue("legacy four-digit train numbers follow the same endpoint",
                url.contains("train_no=1202&date=14-10-2026&lang=en"))
            WimtCandidate.WireReply(capture, 200, "ok")
        }
        assertEquals(200, fourDigit.responseStatus)
        assertEquals(setOf("YPR"), fourDigit.lines.keys)
        val invalid = WimtCandidate.fetch("2068x", day, "YPR", "SMET",
            setOf("YPR"), now) { error("invalid train must not request the web cache") }
        assertTrue(invalid.lines.isEmpty())
        val invalidDate = WimtCandidate.fetch("20689", -1, "YPR", "SMET",
            setOf("YPR"), now) { error("invalid date must not request the web cache") }
        assertTrue(invalidDate.lines.isEmpty())
        val invalidFrom = WimtCandidate.fetch("20689", day, "YPR1", "SMET",
            setOf("YPR"), now) { error("invalid source code must not request the web cache") }
        assertTrue(invalidFrom.lines.isEmpty())
        val invalidTo = WimtCandidate.fetch("20689", day, "YPR", "S",
            setOf("YPR"), now) { error("invalid destination code must not request the web cache") }
        assertTrue(invalidTo.lines.isEmpty())
    }

    @Test fun `web-cache fetch rejects an echoed different train number`() {
        val wrongTrain = capture.replace("\"start_date\":", "\"train_no\":\"12951\",\"start_date\":")
        val result = WimtCandidate.fetch("20689", day, "YPR", "SMET",
            setOf("YPR", "BAW", "VNR", "RRB", "SMET"), now) { url ->
            assertTrue(url.contains("train_no=20689"))
            WimtCandidate.WireReply(wrongTrain, 200, "ok")
        }
        assertEquals(200, result.responseStatus)
        assertTrue(result.responseReceived)
        assertEquals(false, result.trainNumberMatched)
        assertTrue(result.trainNumberEchoed)
        assertTrue(result.lines.isEmpty())
        assertTrue(result.detail.contains("response train number did not match requested train"))
    }

    @Test fun `Gravity01 clock-string rows are schedule-dated and never promoted to actuals`() {
        val sample = """{
          "start_date":"14-10-2026","source_station":"YPR","destination_station":"SMET",
          "days_schedule":[
            {"station_code":"YPR","departed":true,"actual_arrival_time":"09:18",
             "actual_departure_time":"09:23"},
            {"station_code":"BAW","departed":true,"actual_arrival_time":"09:31",
             "actual_departure_time":"09:32"},
            {"station_code":"VNR","curStn":true,"departed":false,
             "actual_arrival_time":"10:35","actual_departure_time":"10:38"},
            {"station_code":"RRB","source":"eta","departed":true,
             "actual_arrival_time":"11:30","actual_departure_time":"11:32"},
            {"station_code":"SMET","departed":true,"actual_arrival_time":"14:50",
             "actual_departure_time":"14:45"},
            {"station_code":"ASK","departed":true,"actual_arrival_tm":1791969000,
             "actual_arrival_time":"09:10","updated_at":"2026-10-14T12:35:00.000000+05:30"}
          ]
        }""".trimIndent()
        val times = mapOf(
            "YPR" to (558 to 563),
            "BAW" to (571 to 572),
            "VNR" to (635 to 638),
            "RRB" to (690 to 692),
            "SMET" to (890 to 895),
            "ASK" to (550 to 555),
        )
        val result = WimtCandidate.parse(sample, day, "YPR", "SMET", now,
            setOf("YPR", "BAW", "VNR", "RRB", "SMET", "ASK"), knownTimes = times)
        assertEquals(3, result.lines.size)
        assertTrue(result.lines["YPR"]!!.contains("run date 14 Oct 2026"))
        assertTrue(result.lines["YPR"]!!.contains("arrival 09:18"))
        assertTrue(result.lines["YPR"]!!.contains("[field actual_arrival_time]"))
        assertTrue(result.lines["YPR"]!!.contains("departure 09:23"))
        assertTrue(result.lines["YPR"]!!.contains("cache source tag unavailable"))
        assertTrue(result.lines["YPR"]!!.contains("row update unavailable"))
        assertTrue("the current reported arrival can appear without inventing its departure",
            result.lines["VNR"]!!.contains("arrival 10:35") &&
                !result.lines["VNR"]!!.contains("departure"))
        assertFalse("an ETA row is not live data", result.lines.containsKey("RRB"))
        assertFalse("future clock strings must not appear as past events", result.lines.containsKey("SMET"))
        assertFalse("a rejected future epoch value must not fall back to its clock string",
            result.lines.containsKey("ASK"))
        assertTrue(result.detail.contains("run date 14 Oct 2026"))
        assertTrue("time-only rows have no supported event-age metadata",
            result.detail.contains("age unknown"))
    }

    @Test fun `clock strings align overnight to the origin run and reject reversed departures`() {
        val at = RailTime.railMinuteToEpochMillis(day, 1_520)
        val sample = """{"start_date":"14-10-2026","source_station":"MYS",
          "destination_station":"TLGP","days_schedule":[
          {"station_code":"BLKR","departed":true,"actual_arrival_time":"01:10",
           "actual_departure_time":"01:05"}]}"""
        val times = mapOf("BLKR" to (1_510 to 1_515))
        val result = WimtCandidate.parse(sample, day, "MYS", "TLGP", at,
            setOf("BLKR"), knownTimes = times)
        assertTrue(result.lines["BLKR"]!!.contains("arrival 01:10 (+1d)"))
        assertFalse("departure before arrival must be rejected",
            result.lines["BLKR"]!!.contains("departure"))
    }

    @Test fun `refusals redirects malformed and oversized bodies never qualify as data`() {
        var calls = 0
        for (status in listOf(302, 403, 429, 503)) {
            val refused = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) {
                calls++
                WimtCandidate.WireReply(null, status, "HTTP $status")
            }
            assertTrue("HTTP $status must not yield rows", refused.lines.isEmpty())
            assertEquals(status, refused.responseStatus)
            assertTrue("an HTTP status proves a response was received", refused.responseReceived)
        }
        assertEquals("one transport call per explicit fetch", 4, calls)

        val html = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) {
            WimtCandidate.WireReply("<html>Checking your browser</html>", 200, "ok")
        }
        assertTrue(html.lines.isEmpty())
        assertTrue(html.responseReceived)
        assertTrue(html.detail.contains("unreadable JSON"))

        val large = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) {
            WimtCandidate.WireReply("x".repeat(WimtCandidate.MAX_RESPONSE_BYTES + 1), 200, "ok")
        }
        assertTrue(large.lines.isEmpty())
        assertEquals(200, large.responseStatus)
        assertTrue(large.responseReceived)
        assertTrue(large.detail.contains("exceeded"))

        // The real bounded stream returns a null body with an explanatory detail;
        // do not misreport that HTTP-200 oversized response as an empty body.
        val streamedLarge = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) {
            WimtCandidate.WireReply(null, 200, "response exceeded 150 kB")
        }
        assertEquals(200, streamedLarge.responseStatus)
        assertTrue(streamedLarge.responseReceived)
        assertTrue(streamedLarge.detail.contains("HTTP 200"))
        assertTrue(streamedLarge.detail.contains("exceeded"))
    }

    @Test fun `stale update and future event rows are excluded independently`() {
        val stale = capture.replace("2026-10-14T09:32:24.542168+05:30",
            "2026-10-14T09:00:00.000000+05:30")
        val result = WimtCandidate.parse(stale, day, "YPR", "SMET", now,
            setOf("YPR", "BAW", "VNR", "RRB", "SMET"))
        assertTrue("update predating event must not qualify the row", "BAW" !in result.lines)
        val future = capture.replace("1791950499", "1791969000")
            .replace("2026-10-14T09:32:24.542168+05:30",
                "2026-10-14T09:34:00.000000+05:30")
        val futureResult = WimtCandidate.parse(future, day, "YPR", "SMET", now,
            setOf("YPR", "BAW", "VNR", "RRB", "SMET"))
        assertTrue("the separately valid earlier departure remains a candidate",
            futureResult.lines["BAW"]?.contains("departure") == true)
        assertFalse("future arrival timestamp must not be accepted",
            futureResult.lines["BAW"]?.contains("arrival") == true)
    }
}
