package `in`.opentrack.india.ui

import android.view.View
import android.widget.FrameLayout
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebsiteClaimPresentationTest {
    @Test fun answeredPastCardsAreUsableComparisonNotFallbackOrPrimaryActuals() {
        val website = PublicPageChecks.ORG
        assertTrue(WebsiteClaimPresentation.selectedSourceAnswered(website,
            "comparison", websiteAnswered = true, hasWebsiteReport = true, hasWebPoint = false))
        assertFalse(SourceEvidencePolicy.primaryActuals(website, true, true))
        assertFalse(WebsiteClaimPresentation.selectedSourceAnswered(website,
            "comparison", websiteAnswered = false, hasWebsiteReport = true, hasWebPoint = false))
        assertFalse(WebsiteClaimPresentation.selectedSourceAnswered(website,
            "last-known", websiteAnswered = false, hasWebsiteReport = true, hasWebPoint = false))
        assertFalse(WebsiteClaimPresentation.selectedSourceAnswered(website,
            "unverified", websiteAnswered = true, hasWebsiteReport = false, hasWebPoint = false))
    }

    @Test fun websitePastCardsAppearInTheirStationRowWithoutEnteringActualOrMarker() {
        val adapter = StopAdapter()
        val row = TrackModel.Stop(1, "HVL", "Honavalli", 562, 563,
            562, 563, null, TrackModel.MinuteSource.SCHEDULE, null,
            TrackModel.State.LATER, false, 206, 0, 20, false, false, false, 0)
        adapter.submitList(listOf(row))
        adapter.websiteClaims = mapOf("HVL" to LiveStopFields(actualArrivalMin = 571))
        val holder = adapter.onCreateViewHolder(FrameLayout(RuntimeEnvironment.getApplication()), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.report.visibility)
        assertEquals("Web claim ↘09:31 · run date inferred", holder.report.text.toString())
        assertTrue(holder.report.contentDescription.contains("Not an actual train observation"))
        assertEquals("—", holder.liveArr.text.toString())
        assertEquals("—", holder.liveDep.text.toString())
        assertEquals(View.GONE, holder.trainMarker.visibility)
        adapter.websiteClaims = emptyMap()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.report.visibility)
    }
}
