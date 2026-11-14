package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshTargetPolicyTest {
    private val site = PublicPageChecks.ORG
    private val official = TrackingSources.OFFICIAL

    @Test fun firstSurveyAsksSiteOnlyWhenItIsSelectedAndNotYetChecked() {
        assertEquals(false, RefreshTargetPolicy.surveyWebsite(official, false))
        assertEquals(true, RefreshTargetPolicy.surveyWebsite(site, false))
        assertEquals(false, RefreshTargetPolicy.surveyWebsite(site, true))
    }

    @Test fun firstSurveyRefusalDisplaysAlreadyAvailableOfficialTimesWithoutChangingManualTarget() {
        assertEquals(official, RefreshTargetPolicy.afterWebsiteSurvey(site, false, true))
        assertEquals(site, RefreshTargetPolicy.target(official, site, auto = false))
        assertEquals(official, RefreshTargetPolicy.target(official, site, auto = true))
        assertEquals(site, RefreshTargetPolicy.afterWebsiteSurvey(site, false, false))
        assertEquals(site, RefreshTargetPolicy.afterWebsiteSurvey(site, true, true))
        assertEquals(official, RefreshTargetPolicy.afterWebsiteSurvey(official, false, true))
    }

    @Test fun manualRefreshNormalizesLegacyWimtDefaultsToTheWebsiteAfterOfficialFallback() {
        assertEquals(site, RefreshTargetPolicy.target(official, site, auto = false))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = false))
        assertEquals(site, RefreshTargetPolicy.target(
            official, TrackingSources.WIMT, auto = false))
        assertEquals(site, RefreshTargetPolicy.target(
            official, WimtCandidate.SOURCE, auto = false))
    }

    @Test fun automaticRefreshDoesNotHammerARefusingWebsiteAfterFallback() {
        assertEquals(official, RefreshTargetPolicy.target(official, site, auto = true))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = true))
    }

    @Test fun explicitOfficialChoiceStaysOfficialAndDoesNotRetryWebsite() {
        assertEquals(official, RefreshTargetPolicy.target(official, official, auto = false))
        assertEquals(official, RefreshTargetPolicy.target(null, null, auto = false))
    }

    @Test fun comparisonHintSwitchesOnlyToTheOtherNamedSource() {
        assertEquals(official, RefreshTargetPolicy.alternateSourceTarget(site))
        assertEquals(site, RefreshTargetPolicy.alternateSourceTarget(official))
        assertNull(RefreshTargetPolicy.alternateSourceTarget(TrackingSources.WIMT))
    }

    @Test fun websiteRefreshFallsBackOnlyWhenItsEventIsStaleAndNtesReportsALaterEvent() {
        assertTrue(RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
            siteEventAgeMillis = 55 * 60_000L,
            officialEventAgeMillis = 3 * 60_000L))
        assertFalse("page fetch time cannot substitute for a station-event age",
            RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
                siteEventAgeMillis = null, officialEventAgeMillis = 3 * 60_000L))
        assertFalse("a recent site event remains the selected site's report",
            RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
                siteEventAgeMillis = 20 * 60_000L, officialEventAgeMillis = 3 * 60_000L))
        assertFalse("a 16:52 site event must not yield to the older 16:51 NTES event",
            RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
                siteEventAgeMillis = 2 * 60_000L, officialEventAgeMillis = 3 * 60_000L))
        assertFalse("a same/older NTES event is not a fallback win",
            RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
                siteEventAgeMillis = 55 * 60_000L,
                officialEventAgeMillis = 54 * 60_000L))
        assertFalse("unavailable/future NTES event age cannot win",
            RefreshTargetPolicy.websiteEventNeedsOfficialFallback(
                siteEventAgeMillis = 55 * 60_000L, officialEventAgeMillis = null))
    }
}
