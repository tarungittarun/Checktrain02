package `in`.opentrack.india.v2.mirror

import android.content.Context
import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WimtCacheRequestTest {
    private val context = RuntimeEnvironment.getApplication()
    private val at = 1_791_961_500_000L
    private val day = RailTime.istEpochDay(at)
    private val json = """{"start_date":"14-10-2026","source_station":"YPR",
        "destination_station":"SMET","days_schedule":[]}"""

    private fun gate() = WebsiteRequestPacing(context, WimtCacheRequest.PAUSE_PREFS)

    private fun clear() {
        context.getSharedPreferences(WimtCacheRequest.PAUSE_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("org_website_request_pause", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun selectedRequestPassesStaticScheduleToParseWimtClockStringRows() {
        clear()
        val response = """{"start_date":"14-10-2026","source_station":"YPR",
            "destination_station":"SMET","days_schedule":[
            {"station_code":"YPR","departed":true,"actual_arrival_time":"09:18",
             "actual_departure_time":"09:23"}]}"""
        val result = WimtCacheRequest.fetch(
            number = "20689", epochDay = day, from = "YPR", to = "SMET",
            knownCodes = setOf("YPR", "SMET"), pacing = gate(), nowMillis = { at },
            knownTimes = mapOf("YPR" to (558 to 563)),
            get = { WimtCandidate.WireReply(response, 200, "ok") },
        )
        assertEquals(200, result.responseStatus)
        assertEquals(1, result.lines.size)
        assertTrue(result.lines["YPR"]!!.contains("arrival 09:18"))
        assertTrue(result.detail.contains("run date 14 Oct 2026"))
    }

    @Test fun refusalIsPersistedPerCacheHostAndStopsEveryTrainUntilDeadline() {
        clear()
        val gate = gate()
        var calls = 0
        val refused = WimtCacheRequest.fetch("20689", day, "YPR", "SMET", emptySet(),
            gate, { at }) {
            calls++
            WimtCandidate.WireReply(null, 403, "HTTP 403")
        }
        assertEquals(1, calls)
        assertEquals(403, refused.responseStatus)
        assertTrue(refused.responseReceived)
        assertTrue(gate.paused(at + 1_000L)!!.detail.contains("HTTP 403"))

        // A new pacing object and different train cannot erase or extend the host pause.
        val reopened = gate()
        val paused = WimtCacheRequest.fetch("20690", day, "YPR", "SMET", emptySet(),
            reopened, { at + 10_000L }) {
            calls++
            error("paused request must not reach the transport")
        }
        assertEquals(1, calls)
        assertTrue(paused.detail.contains("no new request"))
        assertNull(reopened.paused(at + WebsiteRequestPacing.PAUSE_MS))
        assertNull(reopened.paused(at + WebsiteRequestPacing.PAUSE_MS + 1))

        // `.org.in` and the independent cache have distinct courtesy gates.
        assertNull(WebsiteRequestPacing(context).paused(at + 1_000L))
    }

    @Test fun rateLimitPausesWithoutSlidingAndARealNonRefusalAnswerClearsIt() {
        clear()
        var calls = 0
        val gate = gate()
        val limited = WimtCacheRequest.fetch("20689", day, "YPR", "SMET", emptySet(),
            gate, { at }) {
            calls++
            WimtCandidate.WireReply(null, 429, "HTTP 429")
        }
        assertEquals(429, limited.responseStatus)
        assertTrue(gate.paused(at + 1L)!!.detail.contains("HTTP 429"))

        val paused = WimtCacheRequest.fetch("20689", day, "YPR", "SMET", emptySet(),
            gate, { at + 1_000L }) {
            calls++
            error("429 pause must suppress the second request")
        }
        assertTrue(paused.detail.contains("no new request"))
        assertEquals(1, calls)
        assertNull("pause expires exactly at 15 minutes",
            gate.paused(at + WebsiteRequestPacing.PAUSE_MS))

        val answer = WimtCacheRequest.fetch("20689", day, "YPR", "SMET", emptySet(),
            gate, { at + WebsiteRequestPacing.PAUSE_MS }) {
            calls++
            WimtCandidate.WireReply(json, 200, "ok")
        }
        assertEquals(2, calls)
        assertEquals(200, answer.responseStatus)
        assertTrue(answer.responseReceived)
        assertTrue(answer.lines.isEmpty())
        assertTrue("non-refusal response status is retained", answer.detail.contains("HTTP 200"))
        assertNull("a received non-refusal response clears the expired host pause",
            gate.paused(at + WebsiteRequestPacing.PAUSE_MS + 1))
    }

    @Test fun transportExceptionsAndInvalidInputsDoNotCreateRequestsOrRefusalPauses() {
        clear()
        val gate = gate()
        var calls = 0
        val failed = WimtCacheRequest.fetch("20689", day, "YPR", "SMET", emptySet(),
            gate, { at }) {
            calls++
            throw java.net.SocketTimeoutException("synthetic timeout")
        }
        assertEquals(1, calls)
        assertNull(failed.responseStatus)
        assertFalse(failed.responseReceived)
        assertNull(gate.paused(at + 1L))

        val invalidTrain = WimtCacheRequest.fetch("2068x", day, "YPR", "SMET", emptySet(),
            gate, { at }) {
            error("invalid train must not be requested")
        }
        assertTrue(invalidTrain.detail.contains("invalid train"))
        val invalidRun = WimtCacheRequest.fetch("20689", Long.MAX_VALUE, "YPR", "SMET", emptySet(),
            gate, { at }) {
            error("invalid epoch day must not be requested")
        }
        assertTrue(invalidRun.detail.contains("invalid origin run date"))
        assertEquals(1, calls)
        assertNull(gate.paused(at + 2L))
    }

    @Test fun corruptPauseAndBackwardsClockNeverBlockOrOverflow() {
        clear()
        val prefs = context.getSharedPreferences(WimtCacheRequest.PAUSE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong("until", at + WebsiteRequestPacing.PAUSE_MS + 1)
            .putInt("code", 403).commit()
        assertNull(gate().paused(at))
        prefs.edit().putLong("until", at + 1).putInt("code", 403).commit()
        assertNull(gate().paused(-1L))
        gate().observeHttpStatus(200, true, -1L)
        assertTrue("a backwards clock cannot clear an existing pause",
            gate().paused(at)!!.detail.contains("Retry in 1 min"))
        gate().observeHttpStatus(403, true, Long.MAX_VALUE)
        assertTrue("saturated deadline remains a short pause instead of wrapping",
            gate().paused(Long.MAX_VALUE - 1)!!.detail.contains("HTTP 403"))
        assertNull(gate().paused(Long.MAX_VALUE))
    }
}
