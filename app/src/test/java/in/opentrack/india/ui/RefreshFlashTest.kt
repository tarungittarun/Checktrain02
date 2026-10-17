package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshFlashTest {
    @Test fun unchangedWebsiteEventIsNotAnErrorButFailedConnectionIs() {
        val source = PublicPageChecks.ORG
        assertNull(RefreshFlash.connectionError(source, "candidate" to
            "same last site-reported stop; checked in 650 ms", null, true))
        assertEquals("Connection failed", RefreshFlash.connectionError(source,
            "last-known" to "Request failed: connection reset", null, false))
        assertEquals("Source blocked request", RefreshFlash.connectionError(source,
            "unverified" to "HTTP 403", null, false))
        assertEquals("Source timed out", RefreshFlash.connectionError(source,
            "unverified" to "timed out", null, false))
    }

    @Test fun officialErrorIsSeparateFromAUsableEarlierStop() {
        assertEquals("Connection failed", RefreshFlash.connectionError(TrackingSources.OFFICIAL,
            "last-known" to "saved stop", OfficialRequestReceipt(
                OfficialRequestReceipt.Kind.REQUEST_FAILED), null))
    }
}
