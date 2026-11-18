package `in`.opentrack.india.v2.mirror

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebsiteRequestPacingTest {
    @Test fun refusalPausesAcrossScreensAndTrainNumbersButAnAnswerClearsIt() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("org_website_request_pause", Context.MODE_PRIVATE).edit().clear().commit()
        val at = 1_800_000_000_000L
        val gate = WebsiteRequestPacing(context)
        assertNull(gate.paused(at))
        gate.observe(PublicPageChecks.Result("Request failed: HTTP · HTTP 403", at, false), at)
        val reopened = WebsiteRequestPacing(context)
        assertEquals(false, reopened.paused(at + 1_000)!!.pageAnswered)
        assertTrue(reopened.paused(at + 1_000)!!.detail.contains("no new request"))
        // Repeated UI attempts must never slide the deadline forward indefinitely.
        reopened.observe(reopened.paused(at + 10_000)!!, at + 10_000)
        assertNull(reopened.paused(at + WebsiteRequestPacing.PAUSE_MS))
        gate.observe(PublicPageChecks.Result("Request failed: HTTP · HTTP 429", at, false), at)
        assertTrue(reopened.paused(at + 60_000)!!.detail.contains("HTTP 429"))
        gate.observe(PublicPageChecks.Result("parser-only claim; no transport evidence",
            at + 60_000, true), at + 60_000)
        assertNotNull("parse flags alone do not clear a host refusal", reopened.paused(at + 60_001))
        gate.observe(PublicPageChecks.Result("HTTP 200, but incomplete station-card parse",
            at + 70_000, true, orgContentParsed = false,
            responseDigest = "0123456789abcdef"), at + 70_000)
        assertNull("fresh transport clears refusal even when source population is incomplete",
            reopened.paused(at + 70_001))
        gate.observe(PublicPageChecks.Result("Request failed: HTTP · HTTP 429",
            at + 80_000, false), at + 80_000)
        gate.observe(PublicPageChecks.Result("cached success is not a new network response",
            at + 90_000, false, responseDigest = "0123456789abcdef", fromHttpCache = true),
            at + 90_000)
        assertNotNull("cached content does not clear the pause", reopened.paused(at + 90_001))
    }
}
