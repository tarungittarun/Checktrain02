package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteReportDisplayTest {
    private val site = PublicPageChecks.ORG
    private val route = TrainRoute("20652", "TLGP SBC EXP", "TK", "Tumkur", "SBC", "Bengaluru",
        70, 110, 127, null, listOf(
            StopRow(1, "TK", "Tumkur", null, null, null, 608, 610, 1, 0, true),
            StopRow(2, "YPR", "Yesvantpur Jn", null, null, null, 670, 672, 1, 65, true),
            StopRow(3, "SBC", "Bengaluru", null, null, null, 720, null, 1, 70, true)
        ), emptyList())
    private val report = OrgWebsiteStatus.Report(mapOf(
        "TK" to LiveStopFields(actualArrivalMin = 633, actualDepartureMin = 635),
        "YPR" to LiveStopFields(actualArrivalMin = 686),
    ), "YPR", false, 686, 1_792_389_499_000L)

    @Test fun staleWebsiteBadgeRetainsInferredRunWarning() {
        assertTrue(WebsiteReportDisplay.STALE_BADGE.contains("stale"))
        assertTrue(WebsiteReportDisplay.STALE_BADGE.contains("run inferred"))
        assertFalse(WebsiteReportDisplay.STALE_BADGE.contains("GPS"))
    }

    @Test fun answeredSitePastCardsRemainSelectedButCacheAndUnparsedPagesFallBack() {
        assertTrue(WebsiteReportDisplay.selected(site))
        assertTrue(WebsiteReportDisplay.usable("comparison", true, report))
        assertFalse(WebsiteReportDisplay.usable("comparison", false, report))
        assertFalse(WebsiteReportDisplay.usable("comparison", true, null))
        assertFalse(WebsiteReportDisplay.usable("last-known", false, report))
        assertFalse(SourceEvidencePolicy.primaryActuals(site, true, true))
    }

    @Test fun selectedWebsitePaintsOnlyItsOwnPastClocksIncludingArrivalOnlyLastStop() {
        val position = TrainPosition(2, 3, 0f, 65f, StopStatus.ARRIVED,
            null, null, null, SourceKind.PROVIDER_OVERRIDE,
            TrainPosition.Confidence.MEDIUM, 120)
        val model = TrackModel.build(route, position, report.rows, 688, 1, 3, 20745L,
            "Website past-card report", positionOnly = true)
        val visible = WebsiteReportDisplay.rows(model, report)
        val tk = visible.rows.first { it.code == "TK" }
        val ypr = visible.rows.first { it.code == "YPR" }
        val future = visible.rows.first { it.code == "SBC" }
        assertEquals(633, tk.showArr)
        assertEquals(635, tk.showDep)
        assertEquals(686, ypr.showArr) // 11:26, not NTES's missing YPR arrival
        assertNull("do not turn a growing site departure into a fact", ypr.showDep)
        assertEquals(TrackModel.MinuteSource.SITE_REPORTED, ypr.source)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, future.source)
        assertEquals(720, future.showArr) // timetable, not an inferred website ETA
        // A newer page-update label with the same past card changes no row/event.
        assertEquals(visible.rows, WebsiteReportDisplay.rows(model,
            report.copy(pageUpdatedAt = report.pageUpdatedAt + 60_000L)).rows)
        assertEquals(16, ypr.showArr!! - ypr.schedArr!!)
    }
}
