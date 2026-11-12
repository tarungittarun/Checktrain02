package `in`.opentrack.india.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

/**
 * Source-strict acceptance for the installed APK's direct `.in/cache/live_status` provider.
 * A request selector, HTTP 200 by itself, stale stamp, route/date mismatch, cache comparison,
 * `.org.in` page, NTES fallback, or an empty schedule is not a WIMT live-data pass.
 */
@RunWith(AndroidJUnit4::class)
class WimtLiveApiAcceptanceTest {
    private fun trainForIstHour(): String = when (
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "16227" // overnight MYS -> TLGP run
        in 5..11 -> "20652" // morning TLGP -> SBC run
        in 12..14 -> "12002" // daytime NDLS -> BPL run
        in 15..19 -> "20651" // afternoon SBC -> TLGP run
        else -> "16227"
    }

    @Test fun installedApkMustFetchFreshRunMatchedWimtLiveStopTimes() {
        val number = trainForIstHour()
        try {
            E2E.launch()
            E2E.awaitSearchScreen()
            assertTrue("train mode missing", E2E.tap("modeTrain"))
            assertTrue("$number not in local search", E2E.typeInBox("trainBox", number) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select $number", E2E.tapSuggestion("trainBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("$number result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("Track not opened", E2E.openTrackFromFirstResult())
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            assertTrue("source selector not visible", E2E.reveal("sourceSpinner", "scroller"))
            val initialSource = E2E.find("sourceSpinner")?.contentDescription.orEmpty()
            assertTrue("single-request precondition requires the cleared NTES default; got '$initialSource'",
                initialSource.contains("Selected NTES · official", true))
            E2E.shotWimt("wimt-live-before-selected-source")

            assertTrue("source picker cannot open", E2E.tap("sourceSpinner"))
            val source = E2E.ui.wait(
                Until.findObject(By.textContains("WIMT .in live-status feed")), E2E.LOCAL_MS)
            assertNotNull("direct WIMT .in live-status source is not selectable", source)
            source!!.click() // one deliberate selected-source request; no test retry or Auto timer

            val settleDeadline = System.currentTimeMillis() + E2E.NETWORK_MS + 2 * 60_000L + 15_000L
            var summary = ""
            var selected = ""
            while (System.currentTimeMillis() < settleDeadline) {
                summary = E2E.text("sourceSummary", 500)
                selected = E2E.find("sourceSpinner", 500)?.contentDescription.orEmpty()
                val selectedRequestSettled =
                    selected.contains("Selected WIMT .in live-status feed", true) &&
                        !summary.contains("Checking", true)
                val rowsVisible = summary.contains("source-reported stop row", true)
                val stampFresh = summary.contains("upstream update stamp is fresh", true)
                if (selectedRequestSettled && (!rowsVisible || stampFresh)) break
                Thread.sleep(250)
            }
            // A source timestamp a few seconds ahead is not treated as fresh early. The app's
            // one-shot local recheck may update it without issuing a second HTTP request.
            E2E.shotWimt("wimt-live-after-selected-source")

            assertTrue("source details picker is not reachable", E2E.reveal("sourceSpinner", "scroller"))
            E2E.find("sourceSpinner")!!.longClick()
            val detailsButton = E2E.ui.wait(Until.findObject(By.text("Source details")), E2E.LOCAL_MS)
            assertNotNull("Source details action missing", detailsButton)
            detailsButton!!.click()
            val detailsNode = E2E.ui.wait(
                Until.findObject(By.textContains("Selected WIMT .in live-status response")), E2E.LOCAL_MS)
            assertNotNull("selected WIMT .in response details missing", detailsNode)
            val details = detailsNode!!.text.orEmpty()
            E2E.shotWimt("wimt-live-source-details")

            // Close both nested explanation dialogs, then read current app state. A source stamp
            // may cross its small future-skew window while the details dialog is open.
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
            E2E.ui.pressBack()
            E2E.ui.waitForIdle()
            summary = E2E.text("sourceSummary", 500)
            selected = E2E.find("sourceSpinner", 500)?.contentDescription.orEmpty()

            // Inspect the displayed route cells after the selected-source table has settled.
            val cells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            val sourceCells = cells.filter {
                it.contentDescription.orEmpty().contains("WIMT .in source-reported", true) &&
                    !it.contentDescription.orEmpty().endsWith("unknown", true)
            }
            val rowCellCount = sourceCells.size

            val reportRows = Regex("""Selected WIMT \.in source-reported rows \((\d+)\):""")
                .find(details)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val http200 = details.contains("HTTP 200")
            val runMatched = details.contains("Run date echoed and matched: true")
            val routeMatched = details.contains("both route endpoints echoed and matched: true")
            val fresh = details.contains("fresh source stamp; not event time") &&
                summary.contains("upstream update stamp is fresh")
            val cellsAttributed = rowCellCount > 0 &&
                sourceCells.any { it.contentDescription.orEmpty().contains("WIMT .in source-reported") }
            val selectedSourceVisible = selected.contains("Selected WIMT .in live-status feed", true)
            val passed = selectedSourceVisible && http200 && runMatched && routeMatched && fresh &&
                reportRows > 0 && cellsAttributed && summary.contains("source-reported stop row")

            // Safe, bounded diagnostics only: no response body, headers, cookies, URL identifiers,
            // route IDs, event clocks, or raw transport receipt is printed to the workflow log.
            Log.i("WimtLiveApiAcceptance", "WIMT_LIVE_DEVICE_RESULT http200=$http200 " +
                "runDateMatched=$runMatched routeMatched=$routeMatched freshUpdate=$fresh " +
                "evidenceRows=$reportRows visibleSourceCells=$rowCellCount selected=$selectedSourceVisible")
            assertTrue("The installed app did not show fresh, run/date/route-matched WIMT .in stop " +
                "times in its table; HTTP 200, stale data, a station field, timetable, or fallback " +
                "is not acceptance. selected=$selectedSourceVisible http200=$http200 " +
                "runDate=$runMatched route=$routeMatched fresh=$fresh rows=$reportRows " +
                "visibleSourceCells=$rowCellCount", passed)
            Log.i("WimtLiveApiAcceptance", "WIMT_LIVE_DEVICE_VERDICT=PASS_FRESH_MATCHED_SOURCE_TIMES_IN_APP")
            E2E.assertNoSystemDialog("WIMT .in live-status source")
        } finally {
            runCatching { E2E.shotWimt("wimt-live-final-even-on-failure") }
                .onFailure { println("WIMT_LIVE_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
