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

/** Strict live check of the separately selected, undocumented `.in` web cache using the installed APK.
 * A parser fixture, HTTP 200 alone, `.org.in` page, NTES fallback, phone-app claim, or empty timetable
 * is not a pass. The cache's rows are unverified comparisons and must remain out of ACTUAL cells. */
@RunWith(AndroidJUnit4::class)
class WimtCacheLiveAcceptanceTest {
    private fun trainForIstHour(): String = when (
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "16228" // TLGP -> MYS overnight run; wait for the independent live route first
        in 5..11 -> "20652" // TLGP -> SBC morning service
        in 12..13 -> "12002" // NDLS -> BPL daytime service
        in 14..19 -> "20651" // SBC -> TLGP afternoon service
        else -> "16227"
    }

    @Test fun installedApkMustPaintDatedCacheRowsAsAnUnverifiedComparison() {
        val number = trainForIstHour()
        println("WIMT_CACHE_DEVICE_SELECTED_TRAIN=$number (test train, not a train-position claim)")
        try {
            E2E.launch()
            E2E.awaitSearchScreen()
            assertTrue("train mode missing", E2E.tap("modeTrain"))
            assertTrue("$number not in search", E2E.typeInBox("trainBox", number) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select $number", E2E.tapSuggestion("trainBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("$number result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("Track not opened", E2E.openTrackFromFirstResult())
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            if (number == "16228") {
                val routeDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
                var routeLabel = ""
                while (System.currentTimeMillis() < routeDeadline) {
                    routeLabel = E2E.text("routeLabel", 500)
                    if (routeLabel.contains("TLGP", true) && routeLabel.contains("MYS", true) &&
                        routeLabel.contains("identity confirmed live (via erail)", true)) break
                    Thread.sleep(250)
                }
                assertTrue("refuse a cache lookup before the independent TLGP→MYS route is " +
                    "confirmed in the app: '$routeLabel'", routeLabel.contains("TLGP", true) &&
                    routeLabel.contains("MYS", true) &&
                    routeLabel.contains("identity confirmed live (via erail)", true))
            }
            assertTrue("cache selection is not visible", E2E.reveal("sourceSpinner", "scroller"))
            E2E.shotWimt("wimt-cache-before-selected-source")

            assertTrue("source picker cannot open", E2E.tap("sourceSpinner"))
            val cache = E2E.ui.wait(
                Until.findObject(By.textContains("WIMT web cache · experimental")), E2E.LOCAL_MS)
            assertNotNull("explicit cache comparison is missing", cache)
            cache!!.click() // one deliberate GET from the installed app; no auto-refresh or retries

            val deadline = System.currentTimeMillis() + E2E.NETWORK_MS + 10_000L
            var summary = ""
            var selected = ""
            while (System.currentTimeMillis() < deadline) {
                summary = E2E.text("sourceSummary", 500)
                selected = E2E.find("sourceSpinner", 500)?.contentDescription.orEmpty()
                if (selected.contains("Selected WIMT web cache · experimental", true) &&
                    !summary.contains("Checking", true)) break
                Thread.sleep(250)
            }
            E2E.shotWimt("wimt-cache-after-selected-source")
            assertTrue("selected source identity is not visible: '$selected'", selected.contains(
                "Selected WIMT web cache · experimental", true))
            assertTrue("installed-app source request did not settle: '$summary'",
                summary.isNotBlank() && !summary.contains("Checking", true))

            // Source details must show the response rows and provenance, while the separate
            // ACTUAL cells remain empty of cache claims. `reports` is intentionally not a time feed.
            val actualCells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            assertTrue("the source selection hid the stop table", actualCells.isNotEmpty())
            assertTrue("web-cache text was promoted into ACTUAL accessibility cells",
                actualCells.none { it.contentDescription.orEmpty().contains("WIMT web", true) })

            assertTrue("source details picker is not reachable",
                E2E.reveal("sourceSpinner", "scroller"))
            E2E.find("sourceSpinner")!!.longClick()
            val detailsButton = E2E.ui.wait(
                Until.findObject(By.text("Source details")), E2E.LOCAL_MS)
            assertNotNull("source details not reachable", detailsButton)
            detailsButton!!.click()
            val detailsNode = E2E.ui.wait(
                Until.findObject(By.textContains("WIMT web cache:")), E2E.LOCAL_MS)
            assertNotNull("selected cache status missing from Source details", detailsNode)
            val details = detailsNode!!.text.orEmpty()
            E2E.shotWimt("wimt-cache-source-details")

            val rowCount = Regex(
                "WIMT web cache: (\\d+) source-reported web-cache time row\\(s\\)")
                .find(details)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Log.i("WimtCacheLiveAcceptance", "WIMT_CACHE_DEVICE_RESULT selectedTrain=$number " +
                "candidateRows=$rowCount http200=${details.contains("HTTP 200")} " +
                "detailBytes=${details.toByteArray(Charsets.UTF_8).size} summary=${summary.take(180)}")
            assertTrue("source claims must be dated, explicitly unverified, never railway ACTUAL: " +
                details.take(1200), details.contains("run date ") &&
                    details.contains("unverified, not railway ACTUAL") &&
                    details.contains("WIMT web cache is not the WIMT phone app"))
            assertTrue("HTTP refusal, challenge, malformed/stale/ETA-only data, or empty rows are " +
                "NOT acceptance. No source-reported cache row reached the app's comparison details. " +
                "summary='$summary' details='${details.take(1200)}'", rowCount > 0 &&
                    details.contains("WIMT web note (unverified, not Act)"))
            assertTrue("data summary does not expose the selected cache's candidate row count",
                summary.startsWith("WIMT web cache: $rowCount source-reported web-cache time row"))
            Log.i("WimtCacheLiveAcceptance",
                "WIMT_CACHE_DEVICE_VERDICT=PASS_DATED_CACHE_ROWS_SHOWN_AS_UNVERIFIED_COMPARISON")
        } finally {
            runCatching { E2E.shotWimt("wimt-cache-final-even-on-failure") }
                .onFailure { println("WIMT_CACHE_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
