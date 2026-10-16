package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshReceiptTextTest {
    private val unchanged = RefreshEvidence.Change.UNCHANGED
    @Test fun officialAnsweredButSameOldYesvantpurStopIsNotAConnectionFailureOrMovement() {
        val receipt = RefreshReceiptText.format("NTES (official)", "NTES · official",
            "NTES · official", "00:35:01", "last reported stop 53 min ago",
            "live" to "13 actuals", OfficialRequestReceipt.from(
                listOf("PROVIDER_OVERRIDE=miss", "PROVIDER_NTES=hit"), emptyList()),
            null, false, unchanged, false)
        assertTrue(receipt.contains("NTES answered at 00:35:01"))
        assertTrue(receipt.contains("same last reported stop"))
        assertTrue(receipt.contains("53 min ago"))
        assertFalse(receipt.contains("request failed"))
    }

    @Test fun failedOfficialRequestKeepsOldDataAndNamesWorkingFallback() {
        val outcome = OfficialRequestReceipt.from(listOf("PROVIDER_NTES=miss", "CACHED=hit"),
            listOf("LIVE · running status: The live feed did not answer within the time limit."))
        assertEquals(OfficialRequestReceipt.Kind.REQUEST_FAILED, outcome.kind)
        val receipt = RefreshReceiptText.format("NTES (official)", "NTES · official", "website",
            "00:36:00", "site-reported stop 54 min ago", "last-known" to "saved",
            outcome, null, false, unchanged, true)
        assertTrue(receipt.contains("NTES request failed"))
        assertTrue(receipt.contains("showing website"))
        assertTrue(receipt.contains("54 min ago"))
    }

    @Test fun websiteAnsweredWithSameStopIsDifferentFromFailureAndCache() {
        val website = PublicPageChecks.ORG
        val answer = RefreshReceiptText.format(website, "website", "website",
            "00:35:02", "site-reported stop 53 min ago", "candidate" to "past card",
            null, true, false, unchanged, false)
        assertTrue(answer.contains("Website answered"))
        assertTrue(answer.contains("no newer event"))
        val failure = RefreshReceiptText.format(website, "website", "NTES · official",
            "00:36:00", "last reported stop 54 min ago",
            "last-known" to "Request failed: TIMEOUT; previous website report retained",
            null, false, false, unchanged, true)
        assertTrue(failure.contains("website request failed"))
        assertTrue(failure.contains("showing NTES"))
        assertFalse(failure.contains("Website answered"))
    }

    @Test fun answeredWithoutActualsIsNotMislabelledNetworkFailure() {
        val outcome = OfficialRequestReceipt.from(listOf("PROVIDER_NTES=miss"),
            listOf("LIVE · running status: NTES answered but supplied no observed minute."))
        assertEquals(OfficialRequestReceipt.Kind.ANSWERED_UNUSABLE, outcome.kind)
    }
}
