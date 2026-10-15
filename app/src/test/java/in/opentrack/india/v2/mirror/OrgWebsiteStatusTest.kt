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

    @Test fun `website marks past card but future first clock is still a prediction`() {
        val out = OrgWebsiteStatus.parse(page, "16227", day, now, route)
        val report = out.report ?: error(out.reason)
        assertEquals("ARU", report.anchorCode)
        assertTrue(report.departed)
        assertEquals(1794, report.eventMinute)
        assertEquals(1790, report.rows["ARU"]?.actualArrivalMin)
        assertEquals(1794, report.rows["ARU"]?.actualDepartureMin)
        assertEquals("1", report.rows["ARU"]?.platform)
        assertNull(report.rows["ANF"])
        assertTrue(out.reason.contains("origin day inferred"))
    }

    @Test fun `parser through station model keeps next stop static and website platform attributed`() {
        val report = OrgWebsiteStatus.parse(page, "16227", day, now, route).report!!
        val pos = TrainPosition(anchorSeq = 2, nextSeq = 3, fraction = 0f,
            kmAlong = 453f, status = StopStatus.DEPARTED, etaNextMinute = null,
            delayMinutes = null, remainingMin = null, provenance = SourceKind.PROVIDER_OVERRIDE,
            confidence = TrainPosition.Confidence.LOW, ageSeconds = 2)
        val model = TrackModel.build(route, pos, report.rows, 1806, 1, 4, day,
            null, positionOnly = true)
        val aru = model.rows.first { it.code == "ARU" }
        val future = model.rows.first { it.code == "ANF" }
        assertEquals(1790, aru.showArr)
        assertEquals(1794, aru.showDep)
        assertEquals("1", aru.platform)
        assertEquals(TrackModel.MinuteSource.REPORTED, aru.source)
        assertEquals(TrackModel.State.PASSED, aru.state)
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
        assertEquals(1822, report.rows["ANF"]?.actualDepartureMin)
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
