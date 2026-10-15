package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndicatorTest {
    @Test fun aQuickHttpPageDoesNotBecomeGreen() {
        assertEquals(SourceIndicator.Tone.UNAVAILABLE,
            SourceIndicator.of(PublicPageChecks.IXIGO, "unverified",
                "HTTP 200; 1 undated excerpt; no event-level observation").tone)
        assertEquals(SourceIndicator.Tone.UNAVAILABLE,
            SourceIndicator.of("confirmtkt", "none", "11 rows; 0 confirmed actuals").tone)
        assertEquals(SourceIndicator.Tone.PENDING,
            SourceIndicator.of(PublicPageChecks.ORG, "none", "checking on first load").tone)
    }

    @Test fun secondsAndFailureReasonsAreSeparateFromDataFreshness() {
        val failure = SourceIndicator.of("runningstatus.in", "none", "HTTP 403 · checked in 3810 ms")
        assertEquals(SourceIndicator.Tone.UNAVAILABLE, failure.tone)
        assertEquals("site blocks request", failure.label)
        assertEquals("3.81 s", SourceIndicator.seconds("HTTP 403 · checked in 3810 ms"))
        assertEquals(null, SourceIndicator.seconds("not checked yet"))
        assertEquals(0, SourceIndicator.priority(SourceIndicator.Tone.READY))
        assertEquals(3, SourceIndicator.priority(SourceIndicator.Tone.UNAVAILABLE))
    }

    @Test fun siteClaimsAreGreenButNeverLabelledAsVerifiedActuals() {
        val site = SourceIndicator.of(PublicPageChecks.ORG, "candidate", "13 past-card rows")
        assertEquals(SourceIndicator.Tone.READY, site.tone)
        assertEquals(true, site.label.contains("unverified"))
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of(WimtCandidate.SOURCE, "candidate", "dated km point").tone)
        assertEquals(SourceIndicator.Tone.UNAVAILABLE,
            SourceIndicator.of(PublicPageChecks.RAIL_YATRI, "candidate", "no passage minute").tone)
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of("NTES (official)", "live", "11 actual stops").tone)
    }
}
