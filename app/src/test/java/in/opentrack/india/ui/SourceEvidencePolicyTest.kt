package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.mirror.WimtCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source admission is a rule, not a dropdown-order or HTTP-200 side effect. */
class SourceEvidencePolicyTest {
    @Test fun undatedWebsiteAndCacheCannotBecomePrimaryEvenIfPageClaimsFourNewStops() {
        for (source in PublicPageChecks.names + WimtCandidate.SOURCE) {
            assertTrue("$source should be visibly comparison-only", SourceEvidencePolicy.comparisonOnly(source))
            assertFalse("$source must not drive ACTUAL, marker or delay",
                SourceEvidencePolicy.primaryActuals(source, true, true))
        }
        assertFalse(SourceEvidencePolicy.primaryActuals("unknown website", true, true))
        assertFalse(SourceEvidencePolicy.comparisonOnly("NTES (official)"))
        assertFalse(SourceEvidencePolicy.comparisonOnly(TrackingSources.WIMT))
        assertFalse(SourceEvidencePolicy.primaryActuals(TrackingSources.WIMT, false, true))
        assertFalse(SourceEvidencePolicy.primaryActuals(TrackingSources.WIMT, true, false))
        assertTrue(SourceEvidencePolicy.primaryActuals(TrackingSources.WIMT, true, true))
    }

    @Test fun onlyDatedSourceWithAdmittedMinutesCanDrivePrimaryAndFallback() {
        for (name in listOf("NTES (official)", "erail", RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "last known")) {
            assertTrue(name, SourceEvidencePolicy.primaryActuals(name, true, true))
            assertFalse(name, SourceEvidencePolicy.primaryActuals(name, false, true))
            assertFalse(name, SourceEvidencePolicy.primaryActuals(name, true, false))
        }
    }

    @Test fun detachedWebsiteClaimReceiptDoesNotInventMappedRowsOrSameRunIdentity() {
        val detached = OrgWebsiteStatus.DetachedPageClaim(
            trainNo = "16227", websiteFromCode = "MYS", websiteToCode = "TLGP",
            selectedRouteFromCode = "SBC", selectedRouteToCode = "SMET",
            identitySource = "erail.in", originDay = 20_745L,
            pageUpdatedAt = 1_792_369_260_000L, pageClockHasSeconds = true,
            latestPastCard = null,
            event = OrgWebsiteStatus.SiteEventClaim("ARU", "Arakkonam",
                OrgWebsiteStatus.SiteClaimKind.ARRIVAL, 342, 332), siteCards = emptyList())
        val result = PublicPageChecks.Result("detached site claim", 1_792_369_300_000L,
            pageAnswered = true, orgContentParsed = true,
            orgDetachedPageClaim = detached, orgSitePageUpdatedAt = detached.pageUpdatedAt,
            responseBytes = 1000, responseDigest = "0123456789abcdef")
        val receipt = SourceEvidenceReceipt.publicPage(PublicPageChecks.ORG, "16227", 20_745L, result)
        assertTrue(receipt.contains("parsed=DETACHED_SITE_EVENT_CLAIM"))
        assertTrue(receipt.contains("runIdentity=LIVE_ROUTE_MATCH_RUN_DATE_UNVERIFIED"))
        assertTrue(receipt.contains("primaryActuals=0 primaryMarker=none"))
        assertTrue(receipt.contains("siteRows=0 detachedSiteClaims=1"))
        assertTrue(receipt.contains("siteClaimStation=ARU siteClaim=ARU:342 siteClaimKind=ARRIVAL"))
        assertTrue(receipt.contains("siteRoute=MYS>TLGP"))
        assertTrue(receipt.contains("selectedRoute=SBC>SMET"))
        assertTrue(receipt.contains("liveIdentityRouteMatch=true"))
        assertTrue(receipt.contains("page_clock_aligned;selected_origin_day_unverified"))
    }

    @Test fun receiptSeparatesNetworkAnswerFromSiteClaimAndAdmittedAct() {
        val site = OrgWebsiteStatus.Report(emptyMap(), "SRF", false, 342,
            1_792_369_260_000L)
        val public = PublicPageChecks.Result("site answered", 1_792_369_300_000L, true,
            orgReport = site, orgContentParsed = true,
            responseBytes = 106_698, responseDigest = "deadbeefdeadbeef")
        val text = SourceEvidenceReceipt.publicPage(PublicPageChecks.ORG, "20652", 20_745L, public)
        assertTrue(text.contains("transport=NETWORK_ANSWER"))
        assertTrue(text.contains("parsed=SITE_COMPARISON"))
        assertTrue(text.contains("orgContentParsed=true"))
        assertTrue(text.contains("runIdentity=INFERRED"))
        assertTrue(text.contains("primaryActuals=0 primaryMarker=none"))
        assertTrue(text.contains("siteRows=0 detachedSiteClaims=0 siteCards=0 siteDisplayStops=0 " +
            "siteClaimStation=SRF siteClaim=SRF:342"))
        assertTrue(text.contains("siteClaim=SRF:342"))
        assertTrue(text.contains("sha256_16=deadbeefdeadbeef"))
        val cache = SourceEvidenceReceipt.publicPage(PublicPageChecks.ORG, "20652", 20_745L,
            public.copy(fromHttpCache = true, pageAnswered = false, orgReport = null))
        assertTrue(cache.contains("transport=CACHED_BODY"))
        assertTrue(cache.contains("primaryActuals=0"))
        val failure = SourceEvidenceReceipt.publicPage(PublicPageChecks.ORG, "20652", 20_745L,
            PublicPageChecks.Result("Request failed", 1_792_369_300_000L, false))
        assertTrue(failure.contains("transport=FAILED_OR_UNUSABLE"))
        assertEquals(false, failure.contains("NETWORK_ANSWER"))
        val ntes = SourceEvidenceReceipt.official("20652", 20_745L,
            OfficialRequestReceipt(OfficialRequestReceipt.Kind.ANSWERED_WITH_ACTUALS),
            2, "SRF", 342, 1_792_369_020_000L)
        assertTrue(ntes.contains("parsedActualStops=2 primaryActuals=2 lastEvent=SRF:342"))
        val ntesFailed = SourceEvidenceReceipt.official("20652", 20_745L,
            OfficialRequestReceipt(OfficialRequestReceipt.Kind.REQUEST_FAILED),
            2, "SRF", 342, 1_792_369_020_000L)
        assertTrue(ntesFailed.contains("primaryActuals=0 lastEvent=none"))
    }
}
