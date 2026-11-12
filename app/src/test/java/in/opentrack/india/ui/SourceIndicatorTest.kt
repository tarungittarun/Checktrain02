package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndicatorTest {
    @Test fun liveApiWebsiteAndCacheAreThreeDistinctSourceChoices() {
        assertEquals(listOf(TrackingSources.OFFICIAL, TrackingSources.WIMT,
            PublicPageChecks.ORG, WimtCandidate.SOURCE), TrackingSources.options)
        assertEquals("WIMT .in live-status feed", TrackingSources.label(TrackingSources.WIMT))
        assertEquals("Where Is My Train · website", TrackingSources.label(PublicPageChecks.ORG))
        assertEquals("WIMT web cache · experimental",
            TrackingSources.label(WimtCandidate.SOURCE))
        assertEquals(TrackingSources.WIMT, TrackingSources.valid(TrackingSources.WIMT))
        assertEquals(WimtCandidate.SOURCE, TrackingSources.valid(WimtCandidate.SOURCE))
        assertEquals(TrackingSources.OFFICIAL, TrackingSources.valid("WIMT phone app"))
    }

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
        assertEquals("3.8 s", SourceIndicator.seconds("HTTP 403 · checked in 3810 ms"))
        assertEquals(null, SourceIndicator.seconds("not checked yet"))
        assertEquals(0, SourceIndicator.priority(SourceIndicator.Tone.READY))
        assertEquals(4, SourceIndicator.priority(SourceIndicator.Tone.UNAVAILABLE))
        assertEquals("run day unclear", SourceIndicator.of(PublicPageChecks.ORG,
            "unverified", "selected origin run is not unambiguously active").label)
        assertEquals("site update too old", SourceIndicator.of(PublicPageChecks.ORG,
            "unverified", "website update clock missing or over five minutes old").label)
    }

    @Test fun liveApiStateIsNamedAsAProviderReportAndStaleFeedIsNotGreen() {
        assertEquals("WIMT .in source report", SourceIndicator.of(
            TrackingSources.WIMT, "live", "fresh source update and parsed stop times").label)
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of(TrackingSources.WIMT, "live", "source-reported times").tone)
        assertEquals(SourceIndicator.Tone.OLD,
            SourceIndicator.of(TrackingSources.WIMT, "last-known", "update stamp is stale").tone)
        assertEquals(SourceIndicator.Tone.CANDIDATE,
            SourceIndicator.of(TrackingSources.WIMT, "unverified", "no source update clock").tone)
    }

    @Test fun siteClaimsAreAmberAndNeverLabelledAsVerifiedActuals() {
        val site = SourceIndicator.of(PublicPageChecks.ORG, "comparison", "13 past-card rows")
        assertEquals(SourceIndicator.Tone.CANDIDATE, site.tone)
        assertEquals(true, site.label.contains("site past-card times"))
        assertEquals(false, site.label.contains("verified"))
        val cache = SourceIndicator.of(WimtCandidate.SOURCE, "comparison", "dated web rows")
        assertEquals(SourceIndicator.Tone.CANDIDATE, cache.tone)
        assertEquals("web cache · unverified", cache.label)
        assertEquals("a cache can never become a green live source",
            SourceIndicator.Tone.CANDIDATE,
            SourceIndicator.of(WimtCandidate.SOURCE, "live", "claimed actuals").tone)
        assertEquals(SourceIndicator.Tone.CANDIDATE,
            SourceIndicator.of(PublicPageChecks.RAIL_YATRI, "comparison", "no passage minute").tone)
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of("NTES (official)", "live", "11 actual stops").tone)
    }
}
