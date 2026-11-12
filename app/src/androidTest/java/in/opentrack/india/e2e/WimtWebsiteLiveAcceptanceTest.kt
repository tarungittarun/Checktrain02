package `in`.opentrack.india.e2e

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

/** A STRICT online gate on the installed APK, not the more permissive fixture/source UI suite.
 * HTTP 403, a challenge page, NTES fallback, a web-cache candidate, timetable-only cells,
 * or a page with no qualified current-run past card must all FAIL, with a screenshot.
 * The independent .org.in website is not Google's WIMT phone-app feed. */
@RunWith(AndroidJUnit4::class)
class WimtWebsiteLiveAcceptanceTest {
    @Test fun websiteRefreshMustPaintARealSourceQualifiedPastCardFor16227() {
        // The site omits the origin run date. Keep this acceptance pinned to the
        // requested train; never substitute another train merely to get a green
        // result. 16227's overnight origin run is unambiguous only in these IST
        // windows. Outside them, fail before making any website request.
        val hour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            .get(Calendar.HOUR_OF_DAY)
        assertTrue("train 16227 has no active overnight test window at IST hour $hour",
            hour in 0..4 || hour in 20..23)
        val number = "16227" // MYS -> TLGP; early-hours search resolves yesterday's origin day.
        try {
            println("WIMT_DEVICE_SELECTED_TRAIN=$number (timetable candidate, not live position)")
            E2E.launch()
            E2E.awaitSearchScreen()
            // The diagnostic switch is opt-in, enabled here through the real Settings UI.
            assertTrue("Settings not reachable", E2E.tap("settings"))
            assertTrue("diagnostic switch not reachable", E2E.reveal("diagToggle", "settingsScroll"))
            assertTrue("cannot enable source diary", E2E.tap("diagToggle"))
            E2E.ui.pressBack()
            assertTrue("train mode missing", E2E.tap("modeTrain"))
            assertTrue("$number not in search", E2E.typeInBox("trainBox", number) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select $number", E2E.tapSuggestion("trainBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("$number result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("Track not opened", E2E.openTrackFromFirstResult())
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            E2E.shotWimt("wimt-before-selected-source")
            assertTrue("source selector not reachable", E2E.reveal("sourceSpinner", "scroller") &&
                E2E.tap("sourceSpinner"))
            val site = E2E.ui.wait(Until.findObject(By.textContains("Where Is My Train · website")),
                E2E.LOCAL_MS)
            assertNotNull("independent website not selectable", site)
            assertTrue("the .in live-status feed is a distinct provider, not a picker choice",
                E2E.ui.findObject(By.textContains("WIMT .in live-status feed")) == null)
            assertTrue("the .in web cache is a distinct provider, not a picker choice",
                E2E.ui.findObject(By.textContains("WIMT web cache · experimental")) == null)
            site!!.click() // one deliberate selected-source request; no background retry in the test
            val until = System.currentTimeMillis() + E2E.NETWORK_MS
            var accepted = false
            var warning = ""
            var headline = ""
            var badge = ""
            var eventFreshness = ""
            while (System.currentTimeMillis() < until) {
                headline = E2E.text("headline", 250)
                badge = E2E.text("provenance", 250)
                warning = E2E.text("connectionAlert", 250)
                eventFreshness = E2E.text("age", 250)
                val reported = headline.startsWith("Last site card ·") &&
                    badge.contains("run inferred", true) &&
                    eventFreshness.startsWith("Site ") && eventFreshness.contains("ago")
                if (reported) { accepted = true; break }
                if (warning.contains("HTTP 403") || warning.contains("blocks request", true) ||
                    warning.contains("HTTP 429")) break
                Thread.sleep(250)
            }
            E2E.shotWimt("wimt-after-selected-source")
            assertTrue("No qualified website past card in the installed app; " +
                "headline='$headline' badge='$badge' eventFreshness='$eventFreshness' warning='$warning'. " +
                "NTES fallback, HTTP 200 alone and the .in web cache do not pass.", accepted)
            assertTrue("time-sheet not reachable", E2E.reveal("routeHeadActual", "scroller"))
            assertEquals("website data was mislabeled ACTUAL", "WEBSITE", E2E.text("routeHeadActual"))
            val siteCells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            assertTrue("site report has no painted source-attributed times",
                siteCells.any { it.contentDescription.orEmpty().startsWith("Website displayed") })
        } finally {
            // Preserve the original assertion (especially the observed 403) even if
            // this optional last shot fails. Before/after captures are mandatory.
            runCatching { E2E.shotWimt("wimt-final-even-on-failure") }
                .onFailure { println("WIMT_OPTIONAL_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
