package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A STRICT online gate on the installed APK, not the more permissive fixture/source UI suite.
 * HTTP 403, a challenge page, NTES fallback, a web-cache candidate, or a page with no
 * source-qualified past-card event claim for the selected run must FAIL with screenshots.
 * A route-conflicting page may be shown as a detached diagnostic, but cannot pass this
 * selected-run acceptance or paint timetable rows/position. The .org.in site is not Google's WIMT app. */
@RunWith(AndroidJUnit4::class)
class WimtWebsiteLiveAcceptanceTest {
    @Test fun websiteRefreshMustPaintARealSourceQualifiedPastCardFor16227() {
        // Keep the live check pinned to the requested train; never substitute another
        // train merely to get a green result. The site has no day selector: a route-matched
        // report must pass the active-run gate, while a route conflict stays detached and
        // explicitly does not verify this selected timetable's run date.
        // Do not gate on the CI wall-clock: that hid the actual fetch and its refusal. The
        // strict checks below still reject a stale/other-day page, timetable echo, or HTTP
        // success without a source-qualified past card.
        val number = "16227" // MYS -> TLGP; the search target selects the nearest origin run.
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
            var claimSummary = ""
            var detachedDescription = ""
            var detachedClaim = false
            while (System.currentTimeMillis() < until) {
                headline = E2E.text("headline", 250)
                badge = E2E.text("provenance", 250)
                warning = E2E.text("connectionAlert", 250)
                eventFreshness = E2E.text("age", 250)
                claimSummary = E2E.text("sourceSummary", 250)
                detachedDescription = E2E.find("headline", 250)?.contentDescription?.toString().orEmpty()
                val reported = headline.startsWith("Last qualifying website event ·") &&
                    badge.contains("run inferred", true) &&
                    eventFreshness.startsWith("Site ") && eventFreshness.contains("ago")
                detachedClaim = headline.startsWith("Website claims ") &&
                    badge.contains("Detached site claim", true) &&
                    claimSummary.contains("matches live identity from") &&
                    claimSummary.contains("selected timetable") &&
                    claimSummary.contains("page schedule") &&
                    claimSummary.contains("Page updated") &&
                    claimSummary.contains("app response received") &&
                    claimSummary.contains("not attached to timetable rows") &&
                    detachedDescription.contains("not a timetable actual") &&
                    (eventFreshness.contains("Website claim time") ||
                        eventFreshness.contains("not verified"))
                if (reported) { accepted = true; break }
                if (detachedClaim) break // Preserve the visible mismatch; never relax selected-run acceptance.
                if (warning.contains("HTTP 403") || warning.contains("blocks request", true) ||
                    warning.contains("HTTP 429")) break
                Thread.sleep(250)
            }
            E2E.shotWimt("wimt-after-selected-source")
            assertTrue("No source-qualified .org.in website claim in the installed app; " +
                "headline='$headline' badge='$badge' eventFreshness='$eventFreshness' " +
                "summary='$claimSummary' description='$detachedDescription' warning='$warning'. " +
                "A route-conflicting detached claim is diagnostic only and cannot satisfy selected-run acceptance. " +
                "HTTP 200 alone, NTES " +
                "fallback and the .in web cache do not pass.", accepted)
            assertTrue("time-sheet not reachable", E2E.reveal("routeHeadActual", "scroller"))
            assertEquals("website data was mislabeled ACTUAL", "WEBSITE", E2E.text("routeHeadActual"))
            val siteCells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            assertTrue("route-matched site report has no painted source-attributed times",
                siteCells.any { it.contentDescription.orEmpty().startsWith("Website displayed") })
        } finally {
            // Preserve the original assertion (especially the observed 403) even if
            // this optional last shot fails. Before/after captures are mandatory.
            runCatching { E2E.shotWimt("wimt-final-even-on-failure") }
                .onFailure { println("WIMT_OPTIONAL_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
