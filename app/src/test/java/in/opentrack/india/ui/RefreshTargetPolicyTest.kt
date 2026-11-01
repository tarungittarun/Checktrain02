package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshTargetPolicyTest {
    private val site = PublicPageChecks.ORG
    private val official = TrackingSources.OFFICIAL

    @Test fun manualRefreshRetriesPreferredWebsiteAfterOfficialFallback() {
        assertEquals(site, RefreshTargetPolicy.target(official, site, auto = false))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = false))
    }

    @Test fun automaticRefreshDoesNotHammerARefusingWebsiteAfterFallback() {
        assertEquals(official, RefreshTargetPolicy.target(official, site, auto = true))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = true))
    }

    @Test fun explicitOfficialChoiceStaysOfficialAndDoesNotRetryWebsite() {
        assertEquals(official, RefreshTargetPolicy.target(official, official, auto = false))
        assertEquals(official, RefreshTargetPolicy.target(null, null, auto = false))
    }
}
