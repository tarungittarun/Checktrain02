package `in`.opentrack.india.v2.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PublicPageChecksTest {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("2026-10-14")!!.time / 86_400_000L
    private val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        .parse("2026-10-15 04:19:00 +0530")!!.time
    private val relay = """{"success":true,"train_number":"16227","train_start_date":"2026-10-14",
        "data_from":"ntes","update_time":"2026-10-15 04:17:00 +0530",
        "status":"D","current_station_code":"TKE",
        "previous_stations":[{"station_code":"RRB","eta":"03:38","etd":"03:40"}],
        "upcoming_stations":[{"station_code":"BDVT","eta":"04:40","etd":"04:42"}]}"""

    @Test fun `relay reports returned origin provenance and station claim but never a stop event`() {
        val r = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, relay, "16227", day, now)
        assertTrue(r.pageAnswered)
        assertTrue(r.detail.contains("origin 2026-10-14"))
        assertTrue(r.detail.contains("upstream ntes"))
        assertTrue(r.detail.contains("TKE departed"))
        assertTrue(r.detail.contains("same run; station claim only"))
        assertTrue(r.detail.contains("not observed Act"))
        assertFalse(r.detail.contains("BDVT 04:40"))
    }

    @Test fun `wrong run stale clock and future update cannot look like recent matching check`() {
        val wrong = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("2026-10-14", "2026-10-15"), "16227", day, now)
        assertTrue(wrong.detail.contains("wrong train/origin run"))
        val stale = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("04:17:00", "04:09:00"), "16227", day, now)
        assertTrue(stale.detail.contains(">5 min old"))
        val future = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("04:17:00", "04:20:00"), "16227", day, now)
        assertTrue(future.detail.contains("future"))
        assertTrue(PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("16227", "20689"), "16227", day, now).detail.contains("wrong train"))
    }

    @Test fun `public pages show status and selected date without converting timetable to position`() {
        val ixigo = """<html><script>Today 14-10-2026 train arrived TKE</script>
            <h1>16227 Running Status</h1><p>Today 15-10-2026</p><p>Yet to start</p>
            <div>BDVT 04:40 arrived on time</div></html>"""
        val r = PublicPageChecks.parse(PublicPageChecks.IXIGO, ixigo, "16227", day, now)
        assertTrue(r.detail.contains("page-selected 15-10-2026"))
        assertTrue(r.detail.contains("wrong origin run"))
        assertTrue(r.detail.contains("yet to start"))
        assertFalse(r.detail.contains("train arrived TKE"))
        assertTrue(PublicPageChecks.parse(PublicPageChecks.IXIGO, ixigo, "20689", day, now)
            .detail.contains("did not identify train"))
        val orgResult = PublicPageChecks.parse(PublicPageChecks.ORG,
            "<p>16227 Running Status</p><p>Last Updated: 04:19</p><p>TKE On time</p>",
            "16227", day, now)
        assertTrue(orgResult.detail.contains("does not identify a selected origin date"))
        assertTrue(orgResult.detail.contains("not Google's WIMT phone app"))
    }

    @Test fun `relay URL asks for origin-day offset rather than default today`() {
        val url = PublicPageChecks.url(PublicPageChecks.RAIL_YATRI, "16227", day, now)!!
        assertTrue(url.contains("/16227/1.json?start_day=1"))
        assertEquals(null, PublicPageChecks.url(PublicPageChecks.RAIL_YATRI, "16227", day - 3, now))
        assertEquals(null, PublicPageChecks.url(PublicPageChecks.IXIGO, "../../evil", day, now))
    }
}
