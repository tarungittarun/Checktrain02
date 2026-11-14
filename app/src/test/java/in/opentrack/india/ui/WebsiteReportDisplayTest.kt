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

    @Test fun futureStationEstimatesAndLaterThanPageDepartureDoNotLookLikeArrivals() {
        // Owner's 23:11 screenshot: last past card SBC 22:58, page 23:10:44;
        // SBC 23:15 is future even if printed on a green card. White MWM/YPR
        // are not reached. Neither an arrival clock nor accessibility may imply so.
        val day = 20753L // run of 27 Oct 2026
        val stamp = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1390) + 44_000L
        val screenshot = OrgWebsiteStatus.Report(mapOf(
            "SBC" to LiveStopFields(actualArrivalMin = 1378)
        ), "SBC", false, 1378, stamp, siteClocks = mapOf(
            "SBC" to OrgWebsiteStatus.SiteClocks(1378, 1395, false),
            "MWM" to OrgWebsiteStatus.SiteClocks(1396, 1401, true),
            "YPR" to OrgWebsiteStatus.SiteClocks(1401, 1408, true)))
        val stops = listOf("MYS", "SBC", "MWM", "YPR", "TLGP")
        val visible = WebsiteReportDisplay.displayedClocks(screenshot, day, stops,
            stamp + 25_000L)
        assertEquals(3, visible.size)
        assertEquals(1378, visible["SBC"]?.arrivalMin)
        assertNull(visible["SBC"]?.departureMin)
        assertTrue(visible["MWM"]?.forecast == true)
        assertTrue(visible["YPR"]?.forecast == true)
        val forecast = WebsiteReportDisplay.displayedClocks(screenshot, day, stops,
            stamp + 600_000L)["MWM"]
        assertTrue("a website white-card forecast remains visible as a forecast", forecast?.forecast == true)
        assertEquals(1396, forecast?.arrivalMin)
        val pastButAfterPage = WebsiteReportDisplay.displayedClocks(screenshot, day, stops,
            stamp + 600_000L)["SBC"]
        assertEquals("once device time passes it, retain the clock the site printed",
            1395, pastButAfterPage?.departureMin)
        assertTrue("the ~ flag distinguishes a clock later than the page's own update label",
            pastButAfterPage?.departureAfterPageUpdate == true)
        assertNull("a displayed site departure still cannot enter the event ledger",
            screenshot.rows["SBC"]?.actualDepartureMin)
        assertEquals(emptyMap<String, OrgWebsiteStatus.SiteClocks>(),
            WebsiteReportDisplay.displayedClocks(screenshot, day, listOf("MYS", "MWM"), stamp))
        // An actual new page published after 23:15 can show the last site's
        // departure without promoting that clock into a new station event.
        val later = screenshot.copy(pageUpdatedAt =
            `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1397))
        assertEquals(1395, WebsiteReportDisplay.displayedClocks(later, day, stops,
            later.pageUpdatedAt)["SBC"]?.departureMin)
    }

    @Test fun legacyReportWithBackwardsDepartureStillPaintsOnlyArrival() {
        val day = 20754L
        val at = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 594)
        val legacy = OrgWebsiteStatus.Report(
            mapOf("SMET" to LiveStopFields(actualArrivalMin = 577)),
            "SMET", false, 577, at, siteClocks = mapOf(
                "SMET" to OrgWebsiteStatus.SiteClocks(577, 430, false)))
        val visible = WebsiteReportDisplay.displayedClocks(legacy, day,
            listOf("TLGP", "SMET", "SME"), at + 30_000L)
        assertEquals(577, visible["SMET"]?.arrivalMin)
        assertNull("07:10 before 09:37 is not a website departure", visible["SMET"]?.departureMin)
    }

    @Test fun olderPastCardClockAndWhiteForecastStayDistinctInWebsiteColumn() {
        val displayed = report.copy(siteClocks = mapOf(
            "YPR" to OrgWebsiteStatus.SiteClocks(686, 690, false),
            "SBC" to OrgWebsiteStatus.SiteClocks(740, 743, true)))
        val visible = WebsiteReportDisplay.displayedClocks(displayed, 20745L,
            route.stops.map { it.code }, displayed.pageUpdatedAt + 120_000L)
        assertEquals(2, visible.size)
        assertEquals(686, visible["YPR"]?.arrivalMin)
        assertEquals("a page clock later than its update label stays visible as a qualified website claim",
            690, visible["YPR"]?.departureMin)
        assertTrue(visible["YPR"]?.departureAfterPageUpdate == true)
        assertNull("a displayed website departure is not added to its event ledger",
            displayed.rows["YPR"]?.actualDepartureMin)
        assertTrue(visible["SBC"]?.forecast == true)
        assertEquals(740, visible["SBC"]?.arrivalMin)
        assertNull("a white-card forecast never enters event rows", displayed.rows["SBC"])
    }

    @Test fun eventlessParsedWebsiteClocksStillPopulateTheSelectedWebsiteColumn() {
        val day = 20770L
        val pageAt = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1401) + 31_000L
        val now = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1402) + 46_000L
        val cards = mapOf("MWM" to OrgWebsiteStatus.SiteClocks(
            arrivalMin = 1401,
            departureMin = 1402,
            forecast = false,
            mark = OrgWebsiteStatus.SiteCardMark.PAST,
            arrivalAfterPageUpdate = false,
            departureAfterPageUpdate = true,
        ))
        val visible = WebsiteReportDisplay.selectedWebsiteClocks(null, cards, day,
            listOf("SBC", "MWM", "YPR"), pageAt, now)
        assertEquals(1401, visible["MWM"]?.arrivalMin)
        assertEquals(1402, visible["MWM"]?.departureMin)
        assertFalse(visible["MWM"]?.arrivalAfterPageUpdate == true)
        assertTrue("a late printed clock is visible but remains '~' qualified",
            visible["MWM"]?.departureAfterPageUpdate == true)
        assertTrue("a clock-only parsed page remains website comparison content",
            WebsiteReportDisplay.usable("comparison", true, null, cards))
    }

    @Test fun lastWebsiteEventLabelUsesTheReportedArrivalDepartureOrPassage() {
        val arrival = report.copy(rows = mapOf(
            "YPR" to LiveStopFields(actualArrivalMin = 686)),
            anchorCode = "YPR", departed = false, eventMinute = 686)
        assertEquals(WebsiteReportDisplay.SiteEventKind.ARRIVAL,
            WebsiteReportDisplay.eventKind(arrival))
        assertEquals("Site arrival", WebsiteReportDisplay.eventKind(arrival).summaryLabel)

        val departure = report.copy(rows = mapOf(
            "YPR" to LiveStopFields(actualDepartureMin = 1412)),
            anchorCode = "YPR", departed = true, eventMinute = 1412)
        assertEquals(WebsiteReportDisplay.SiteEventKind.DEPARTURE,
            WebsiteReportDisplay.eventKind(departure))
        assertEquals("Site departure", WebsiteReportDisplay.eventKind(departure).summaryLabel)

        val both = departure.copy(rows = mapOf(
            "YPR" to LiveStopFields(actualArrivalMin = 1406, actualDepartureMin = 1412)))
        assertEquals(WebsiteReportDisplay.SiteEventKind.DEPARTURE,
            WebsiteReportDisplay.eventKind(both))
        val passage = departure.copy(intermediate = OrgWebsiteStatus.Intermediate(
            "MID", "Mapped point", "SBC", "YPR", 10, 1412))
        assertEquals(WebsiteReportDisplay.SiteEventKind.PASSAGE,
            WebsiteReportDisplay.eventKind(passage))
    }

    @Test fun siteCardSummaryShowsExactVisibleRunDateWithoutPromotingSiteClocks() {
        val card = OrgWebsiteStatus.SiteCardClaim(
            code = "ARU", mark = OrgWebsiteStatus.SiteCardMark.PAST,
            arrival = OrgWebsiteStatus.SiteCardTime(1_790, 1_783, "05:50 AM", "05:43 AM"),
            departure = null)
        val summary = WebsiteReportDisplay.allSiteCardsSummary(
            listOf(card), runDateMatched = true, pageRunDate = "2026-10-14")!!
        assertTrue(summary.contains("Visible page run date: 2026-10-14"))
        assertTrue(summary.contains("Visible page date matches the selected origin run"))
        assertTrue(summary.contains("website claims, not independently verified actuals"))
        assertFalse(summary.contains("NTES actuals")) // remains an explicit display-only page
    }

    @Test fun staleWebsiteBadgeRetainsInferredRunWarningAndAvoidsAssumingArrival() {
        assertTrue(WebsiteReportDisplay.STALE_BADGE.contains("stale"))
        assertTrue(WebsiteReportDisplay.STALE_BADGE.contains("run inferred"))
        assertTrue(WebsiteReportDisplay.STALE_BADGE.contains("mapped event"))
        assertFalse(WebsiteReportDisplay.STALE_BADGE.contains("GPS"))
        assertEquals("WEBSITE · site claim · stale · page date matched",
            WebsiteReportDisplay.staleBadge(report.copy(originInferred = false)))
    }

    @Test fun detachedSiteClaimKeepsIdentityMismatchPublicationAndEventClocksSeparate() {
        val day = 20753L
        val pageAt = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1370) + 12_000L
        val detached = OrgWebsiteStatus.DetachedPageClaim(
            trainNo = "16227", websiteFromCode = "MYS", websiteToCode = "TLGP",
            selectedRouteFromCode = "SBC", selectedRouteToCode = "SMET",
            identitySource = "erail.in", originDay = day, pageUpdatedAt = pageAt,
            pageClockHasSeconds = true, latestPastCard = null,
            event = OrgWebsiteStatus.SiteEventClaim("ARU", "Arakkonam",
                OrgWebsiteStatus.SiteClaimKind.ARRIVAL, 1340, 1320), siteCards = emptyList())
        val summary = WebsiteReportDisplay.detachedClaimSummary(detached, pageAt + 2_000L)
        assertTrue(summary.contains("MYS→TLGP matches live identity from erail.in"))
        assertTrue(summary.contains("selected timetable SBC→SMET"))
        assertTrue(summary.contains("Website claims arrival at Arakkonam (ARU)"))
        assertTrue(summary.contains("page schedule 22:00"))
        assertTrue(summary.contains("selected timetable origin day"))
        assertTrue(summary.contains("page age 2s at check"))
        assertTrue(summary.contains("not attached to timetable rows"))
        assertFalse(summary.contains("verified actuals"))
        val datedRouteConflict = WebsiteReportDisplay.detachedClaimSummary(
            detached.copy(runDateMatched = true), pageAt + 2_000L)
        assertTrue(datedRouteConflict.contains("visible page date matches selected origin day"))
        assertTrue(datedRouteConflict.contains("route conflicts with the selected timetable"))
        assertFalse(datedRouteConflict.contains("origin day is unverified"))
        assertTrue(datedRouteConflict.contains("not attached to timetable rows"))
    }

    @Test fun answeredSitePastCardsRemainSelectedButCacheAndUnparsedPagesFallBack() {
        assertTrue(WebsiteReportDisplay.selected(site))
        assertTrue(WebsiteReportDisplay.usable("comparison", true, report))
        assertFalse(WebsiteReportDisplay.usable("comparison", false, report))
        assertFalse(WebsiteReportDisplay.usable("comparison", true, null))
        val cardsOnly = mapOf("SRF" to OrgWebsiteStatus.SiteClocks(
            341, 343, forecast = false, mark = OrgWebsiteStatus.SiteCardMark.PAST))
        assertTrue("a fully parsed card-clock comparison stays selectable without an event row",
            WebsiteReportDisplay.usable("comparison", true, null, cardsOnly))
        assertFalse("partial/unparsed HTML must not count as a usable website response",
            WebsiteReportDisplay.usable("comparison", false, null, cardsOnly))
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
