package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class OrgWebsiteStatusTest {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse("2026-10-14")!!.time / 86_400_000L
    private val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        .parse("2026-10-15 06:06:30 +0530")!!.time
    private fun stop(seq: Int, code: String, arr: Int?, dep: Int?, km: Int) = StopRow(
        seq, code, "$code Jn", "KA", null, null, arr, dep, if (seq == 1) 1 else 2,
        km, true)
    private val route = TrainRoute("16227", "TEST", "MYS", "Mysuru", "TLGP", "Talguppa",
        500, 730, null, null, listOf(stop(1, "MYS", null, 1180, 0),
            stop(2, "ARU", 1783, 1784, 453), stop(3, "ANF", 1800, 1802, 468),
            stop(4, "TLGP", 1910, null, 500)),
        listOf(SegmentRow(1, "MYS", "ARU", 453, 603),
            SegmentRow(2, "ARU", "ANF", 15, 17), SegmentRow(3, "ANF", "TLGP", 32, 108)))
    private fun card(code: String, color: String, arr: String, schedArr: String,
                     dep: String, schedDep: String) = """
        <!-- Station Details Card -->
        <div class="flex-grow p-3 mb-3 rounded-lg ml-4 shadow-sm $color">
        <div><h3 class="font-medium">Station ($code)</h3><span>• PF 1</span></div>
        <div><!-- Arrival --><div>ARRIVAL</div><div class="font-semibold">$arr</div>
        <div class="text-xs text-gray-400">$schedArr</div></div>
        <div><!-- Departure --><div>DEPARTURE</div><div class="font-semibold">$dep</div>
        <div class="text-xs text-gray-400">$schedDep</div></div></div>
    """.trimIndent()
    private val page = """<html><h1>16227 Running Status</h1><p>Source: MYS</p>
        <p>Destination: TLGP</p><p>Last Updated: 06:06:19 AM</p>
        ${card("ARU", "bg-green-50 opacity-90", "05:50 AM", "05:43 AM", "05:54 AM", "05:44 AM")}
        ${card("ANF", "bg-white", "06:08 AM", "06:00 AM", "06:10 AM", "06:02 AM")}
        </html>""".trimIndent()

    @Test fun `website mobile retry is bounded and other source policy is unchanged`() {
        assertEquals(1, PublicPageChecks.retriesFor(PublicPageChecks.ORG))
        assertEquals(0, PublicPageChecks.retriesFor(PublicPageChecks.RAIL_YATRI))
        assertEquals(0, PublicPageChecks.retriesFor(PublicPageChecks.IXIGO))
    }

    @Test fun `website marks past card but future first clock is still a prediction`() {
        val out = OrgWebsiteStatus.parse(page, "16227", day, now, route)
        val report = out.report ?: error(out.reason)
        assertEquals("ARU", report.anchorCode)
        assertFalse("last departure can drift with page clock", report.departed)
        assertEquals(1790, report.eventMinute)
        assertEquals(1790, report.rows["ARU"]?.actualArrivalMin)
        assertNull(report.rows["ARU"]?.actualDepartureMin)
        assertEquals(1794, report.provisionalDeparture)
        assertEquals("1", report.rows["ARU"]?.platform)
        assertNull(report.rows["ANF"])
        assertTrue(out.reason.contains("origin day inferred"))
    }

    @Test fun `past stop departure does not manufacture an arrival from a scheduled clock`() {
        val depOnly = page.replace("05:50 AM", "05:43 AM")
            .replace("bg-white", "bg-green-50 opacity-90")
            .replace("06:06:19 AM", "06:23:19 AM")
            .replace("06:08 AM", "06:11 AM")
            .replace("06:10 AM", "06:22 AM")
        val report = OrgWebsiteStatus.parse(depOnly, "16227", day,
            now + 17 * 60_000L, route).report!!
        assertEquals("ANF", report.anchorCode)
        assertNull("schedule-equal website arrival is not independently reported", report.rows["ARU"]?.actualArrivalMin)
        assertEquals("departure has its own valid schedule pair", 1794,
            report.rows["ARU"]?.actualDepartureMin)
    }

    @Test fun `parser through station model keeps next stop static and website platform attributed`() {
        val report = OrgWebsiteStatus.parse(page, "16227", day, now, route).report!!
        val pos = TrainPosition(anchorSeq = 2, nextSeq = 3, fraction = 0f,
            kmAlong = 453f, status = StopStatus.ARRIVED, etaNextMinute = null,
            delayMinutes = null, remainingMin = null, provenance = SourceKind.PROVIDER_OVERRIDE,
            confidence = TrainPosition.Confidence.LOW, ageSeconds = 2)
        val model = TrackModel.build(route, pos, report.rows, 1806, 1, 4, day,
            null, positionOnly = true)
        val aru = model.rows.first { it.code == "ARU" }
        val future = model.rows.first { it.code == "ANF" }
        assertEquals(1790, aru.showArr)
        assertNull(aru.showDep)
        assertEquals("1", aru.platform)
        assertEquals(TrackModel.MinuteSource.REPORTED, aru.source)
        assertEquals(TrackModel.State.HERE, aru.state)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, future.source)
        assertEquals(1800, future.showArr) // 06:08 website forecast must NOT replace Sch 06:00
        assertNull(future.platform)
    }

    @Test fun `a revised upcoming website clock can become a later attributed past card`() {
        // 06:08 was published while ANF was white/upcoming. At 06:23 the same
        // website instead showed 06:11/06:22. This tests a changing site claim,
        // not independent proof that its later clocks were observed at the station.
        val later = page.replace("06:06:19 AM", "06:23:19 AM")
            .replace("bg-white", "bg-green-50 opacity-90")
            .replace("06:08 AM", "06:11 AM")
            .replace("06:10 AM", "06:22 AM")
        val report = OrgWebsiteStatus.parse(later, "16227", day, now + 17 * 60_000L,
            route).report!!
        assertEquals("ANF", report.anchorCode)
        assertEquals(1811, report.rows["ANF"]?.actualArrivalMin)
        assertNull(report.rows["ANF"]?.actualDepartureMin)
        assertEquals(1822, report.provisionalDeparture)
        assertEquals(1794, report.rows["ARU"]?.actualDepartureMin)
        assertFalse("published first-clock value changed, not merely the page clock",
            report.rows["ANF"]?.actualArrivalMin == 1808)
    }

    @Test fun `green card with only a schedule or a future value is not Act`() {
        val onlySchedule = page.replace("05:50 AM", "05:43 AM").replace("05:54 AM", "05:44 AM")
        assertNull(OrgWebsiteStatus.parse(onlySchedule, "16227", day, now, route).report)
        val futureGreen = page.replace("05:50 AM", "06:30 AM")
            .replace("05:54 AM", "06:35 AM")
        assertNull(OrgWebsiteStatus.parse(futureGreen, "16227", day, now, route).report)
    }

    @Test fun `16205 blue highlighted reached halt cannot make later refresh jump back`() {
        val runDay = day + 1 // 15 Oct, unlike the overnight 16227 fixture
        val halts = TrainRoute("16205", "Mysuru", "TLGP", "Talguppa", "MYS", "Mysuru",
            372, 450, 127, null, listOf(
                stop(1, "TLGP", null, 890, 0),
                stop(2, "KMSI", 975, 976, 72),
                stop(3, "SMET", 1005, 1010, 98),
                stop(4, "BDVT", 1028, 1030, 116),
                stop(5, "MYS", 1350, null, 372)), emptyList())
        val map = halts.copy(stops = listOf(halts.stops[0], halts.stops[1], halts.stops[2],
            stop(4, "SME", 1013, 1013, 99).copy(isHalt = false),
            stop(5, "BEI", 1018, 1018, 105).copy(isHalt = false),
            halts.stops[3].copy(seq = 6), halts.stops[4].copy(seq = 7)))
        // SMET's blue/two-clock arrival and single-clock departure match the
        // read-only 17:20 source trial. BDVT's blue style is a safety fixture
        // for the owner's later screenshot, not a claimed capture of its HTML.
        fun page(clock: String, beiStyle: String,
                 bdvtStyle: String = "bg-blue-50"): String = """
            <h1>16205</h1> Source: TLGP Destination: MYS Last Updated: $clock
            ${card("KMSI", "bg-green-50 opacity-90", "04:21 PM", "04:15 PM", "04:25 PM", "04:16 PM")}
            ${card("SMET", "bg-blue-50", "04:48 PM", "04:45 PM", "04:50 PM", "04:50 PM")
                .replace("<div class=\"text-xs text-gray-400\">04:50 PM</div>", "")}
            ${card("BEI", beiStyle, "05:03 PM", "04:58 PM", "05:04 PM", "04:58 PM")}
            ${card("BDVT", bdvtStyle, "05:10 PM", "05:08 PM", "05:12 PM", "05:10 PM")}
            ${card("MYS", "bg-white", "10:20 PM", "10:30 PM", "10:35 PM", "10:35 PM")}
        """.trimIndent()
        val early = OrgWebsiteStatus.parse(page("05:08:45 PM", "bg-green-50 opacity-90"),
            "16205", runDay, `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(
                runDay, 17 * 60 + 9), halts, map).report!!
        assertEquals("BEI", early.anchorCode)
        assertEquals(105, early.intermediate?.mapKm)
        val later = OrgWebsiteStatus.parse(page("05:11:45 PM", "bg-white"),
            "16205", runDay, `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(
                runDay, 17 * 60 + 12), halts, map).report!!
        assertEquals("BDVT", later.anchorCode) // not KMSI, even when BEI is absent
        val laterGreen = OrgWebsiteStatus.parse(
            page("05:11:45 PM", "bg-white", "bg-green-50 opacity-90"),
            "16205", runDay, `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(
                runDay, 17 * 60 + 12), halts, map).report!!
        assertEquals("BDVT", laterGreen.anchorCode) // 17:20 read-only capture had later green stops
        assertEquals(17 * 60 + 10, later.rows["BDVT"]?.actualArrivalMin)
        assertNull("last blue card departure is provisional", later.rows["BDVT"]?.actualDepartureMin)
        assertNull("white future MYS is not actual", later.rows["MYS"])
        val earlyPosition = OrgWebsiteStatus.progress(early, halts)!!
        val laterPosition = OrgWebsiteStatus.progress(later, halts)!!
        assertTrue(laterPosition > earlyPosition)
        assertEquals(later, OrgWebsiteStatus.forwardOnly(early, later, earlyPosition, halts,
            later.pageUpdatedAt))
        // Two static distance axes may disagree by hundreds of km; route ORDER
        // still makes the next halt later than a named intermediate before it.
        val differentAxis = early.copy(intermediate = early.intermediate!!.copy(mapKm = 905))
        assertEquals(later, OrgWebsiteStatus.forwardOnly(differentAxis, later,
            OrgWebsiteStatus.progress(differentAxis, halts), halts, later.pageUpdatedAt))
        // If another reply regresses to a previously passed station, keep the
        // original report while fresh. Never advance its page/event clock.
        val regressed = early.copy(anchorCode = "KMSI", intermediate = null,
            eventMinute = 16 * 60 + 21, pageUpdatedAt = later.pageUpdatedAt + 60_000)
        assertEquals(later, OrgWebsiteStatus.forwardOnly(later, regressed, laterPosition, halts,
            regressed.pageUpdatedAt))
        assertNull(OrgWebsiteStatus.forwardOnly(later, regressed, laterPosition, halts,
            later.pageUpdatedAt + 301_000)) // old report expired; unknown, not KMSI
        // Loading a longer dated route can insert three earlier halts. The floor
        // must be recomputed from its station CODE on the grown route, not cached
        // as the original BDVT index (which KMSI would now exceed numerically).
        val grown = halts.copy(stops = listOf(halts.stops[0],
            stop(2, "AAA", 925, 926, 20), stop(3, "AAB", 935, 936, 34),
            stop(4, "AAC", 945, 946, 48)) +
            halts.stops.drop(1).mapIndexed { i, s -> s.copy(seq = i + 5) })
        assertTrue(OrgWebsiteStatus.progress(regressed, grown)!! > laterPosition)
        assertEquals(later, OrgWebsiteStatus.forwardOnly(later, regressed,
            OrgWebsiteStatus.progress(later, grown), grown, regressed.pageUpdatedAt))
    }

    @Test fun `blue highlight alone or a single schedule clock is never a reported arrival`() {
        val blue = page.replace("bg-green-50 opacity-90", "bg-blue-50")
        assertEquals("ARU", OrgWebsiteStatus.parse(blue, "16227", day, now, route).report?.anchorCode)
        assertNull(OrgWebsiteStatus.parse(blue.replace("05:50 AM", "05:43 AM"),
            "16227", day, now, route).report)
        assertNull(OrgWebsiteStatus.parse(blue.replace("05:50 AM", "06:30 AM"),
            "16227", day, now, route).report)
        assertNull(OrgWebsiteStatus.parse(blue.replace(
            "<div class=\"text-xs text-gray-400\">05:43 AM</div>", ""),
            "16227", day, now, route).report)
    }

    @Test fun `wrong endpoints stale clock and a different origin run cannot anchor`() {
        assertNull(OrgWebsiteStatus.parse(page.replace("Source: MYS", "Source: SBC"),
            "16227", day, now, route).report)
        assertNull(OrgWebsiteStatus.parse(page.replace("06:06:19", "05:55:19"),
            "16227", day, now, route).report)
        val old = OrgWebsiteStatus.parse(page, "16227", day - 1, now, route)
        assertNull(old.report)
        assertTrue(old.reason.contains("not unambiguously active"))
        assertNull(OrgWebsiteStatus.parse(page, "20689", day, now, route).report)
    }


    @Test fun `mapped non-halt is site-attributed at station distance not a measured train km`() {
        val halts = route.copy(stops = listOf(route.stops[0], route.stops[1],
            route.stops[3].copy(seq = 3)))
        val staticMap = route.copy(stops = route.stops.map { point ->
            if (point.code == "ANF") point.copy(isHalt = false, arrivalMin = null,
                departureMin = null) else point
        })
        val later = page.replace("06:06:19 AM", "06:23:19 AM")
            .replace("bg-white", "bg-green-50 opacity-90")
            .replace("06:08 AM", "06:11 AM")
            .replace("06:10 AM", "06:22 AM")
        val out = OrgWebsiteStatus.parse(later, "16227", day, now + 17 * 60_000L,
            halts, staticMap)
        val report = out.report ?: error(out.reason)
        assertEquals("ANF", report.anchorCode)
        assertEquals("ARU", report.intermediate?.precedingHalt)
        assertEquals("TLGP", report.intermediate?.followingHalt)
        assertEquals(468, report.intermediate?.mapKm)
        assertEquals(1800, report.intermediate?.scheduledMinute)
        assertEquals(1811, report.rows["ANF"]?.actualArrivalMin)
        assertNull("last card's growing departure isn't a past actual", report.rows["ANF"]?.actualDepartureMin)
        val leg = `in`.opentrack.india.domain.RouteBetween.siteIntermediateLeg(
            halts, staticMap, "ARU", "TLGP", "ANF", 468)!!
        assertEquals(`in`.opentrack.india.domain.RouteBetween.MarkerKind.SITE_INTERMEDIATE,
            leg.markerKind)
        assertEquals(15f / 47f, leg.fraction, 0.001f)
        assertNull(OrgWebsiteStatus.parse(later, "16227", day, now + 17 * 60_000L,
            halts, null).report?.intermediate)
    }
    @Test fun `cached HTML cannot publish a fresh website claim`() {
        val fetched = PublicPageChecks.fetch(PublicPageChecks.ORG, "16227", day, now,
            "MYS", "TLGP", route.stops.map { it.code }.toSet(), route) {
            `in`.opentrack.india.core.Http.Result.Ok(page, fromCache = true, ageMillis = 20_000)
        }
        assertFalse(fetched.pageAnswered)
        assertNull(fetched.orgReport)
        // Use the network-independent parser time for the positive counterpart.
        val matched = PublicPageChecks.parse(PublicPageChecks.ORG, page, "16227", day,
            now, "MYS", "TLGP", route.stops.map { it.code }.toSet(), route)
        assertEquals("ARU", matched.orgReport?.anchorCode)
        assertTrue(matched.detail.contains("mapped to the active selected run by timetable"))
    }
}
