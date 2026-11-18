package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import `in`.opentrack.india.core.WebsiteBrowserRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        // train merely to get a green result. The visible page must explicitly identify
        // the selected origin date as well as the selected route; timetable-day inference
        // and a route-conflicting detached claim are diagnostic-only, never acceptance.
        // Do not gate on the CI wall-clock: that hid the actual fetch and its refusal. The
        // strict checks below still reject a stale/other-day page, timetable echo, or HTTP
        // success without a source-qualified past card.
        val number = "16227"
        val fromCode = "MYS"
        val toCode = "TLGP"
        try {
            println("WIMT_DEVICE_SELECTED_TRAIN=$number route=$fromCode->$toCode (selected timetable run, not live position)")
            E2E.launch()
            E2E.awaitSearchScreen()
            // The diagnostic switch is opt-in, enabled here through the real Settings UI.
            assertTrue("Settings not reachable", E2E.tap("settings"))
            assertTrue("diagnostic switch not reachable", E2E.reveal("diagToggle", "settingsScroll"))
            assertTrue("cannot enable source diary", E2E.tap("diagToggle"))
            E2E.shotWimt("wimt-settings-diagnostics")
            E2E.ui.pressBack()
            // Select the stated passenger segment. Searching by number alone would open the
            // stale bundled SBC→SMET corridor before the live MYS→TLGP route is known.
            assertTrue("station-pair mode missing", E2E.tap("modeStations"))
            assertTrue("MYS origin is not searchable", E2E.typeInBox("fromBox", fromCode.lowercase()) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select MYS", E2E.tapSuggestion("fromBox"))
            assertTrue("TLGP destination is not searchable", E2E.typeInBox("toBox", toCode.lowercase()) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select TLGP", E2E.tapSuggestion("toBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll"))
            E2E.shotWimt("wimt-search-route-criteria")
            assertTrue("Find could not be tapped", E2E.tap("find"))
            assertTrue("MYS→TLGP search produced no real train results",
                E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            E2E.shotWimt("wimt-search-route-results")
            val opened = E2E.openTrackForTrain(number)
            val visibleTrains = E2E.all(E2E.res("hitNo")).map { it.text.orEmpty() }
            assertTrue("MYS→TLGP did not return the pinned train $number; visible=$visibleTrains", opened)
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            var selectedRoute = E2E.text("routeLabel")
            val routeDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
            while (!selectedRoute.startsWith("$fromCode → $toCode") &&
                System.currentTimeMillis() < routeDeadline) {
                Thread.sleep(200)
                selectedRoute = E2E.text("routeLabel", 250)
            }
            assertTrue("selected timetable still does not cover $fromCode→$toCode: '$selectedRoute'",
                selectedRoute.startsWith("$fromCode → $toCode"))
            E2E.shotWimt("wimt-before-selected-source")
            assertTrue("source selector not reachable", E2E.reveal("sourceSpinner", "scroller") &&
                E2E.fingerTap("sourceSpinner"))
            val site = E2E.ui.wait(Until.findObject(By.textContains("Where Is My Train · website")),
                E2E.LOCAL_MS)
            assertNotNull("independent website not selectable", site)
            assertTrue("the .in live-status feed is a distinct provider, not a picker choice",
                E2E.ui.findObject(By.textContains("WIMT .in live-status feed")) == null)
            assertTrue("the .in web cache is a distinct provider, not a picker choice",
                E2E.ui.findObject(By.textContains("WIMT web cache · experimental")) == null)
            E2E.shotWimt("wimt-source-picker-open")
            val deviceTiming = AtomicReference<String?>(null)
            WebsiteBrowserRequest.timingObserverForTest = { timing ->
                deviceTiming.set(timing)
                // This is the actual device-side document duration, separate from the website's
                // printed update-age and the full screen-selection-to-render duration.
                println("WIMT_DEVICE_FETCH_TIMING=$timing")
            }
            val navigationEntered = CountDownLatch(1)
            val allowNavigation = CountDownLatch(1)
            // Hold the one genuine WebView navigation off the UI thread. This makes the
            // pending capture deterministic: the website is selected, but its request has
            // not yet reached the network. The gate is always released before after-response
            // acceptance begins, and it never fabricates or substitutes a website reply.
            WebsiteBrowserRequest.beforeDocumentNavigationForTest = {
                println("WIMT_DEVICE_PRE_NAVIGATION_BARRIER_ENTERED")
                navigationEntered.countDown()
                allowNavigation.await(90, TimeUnit.SECONDS)
            }
            try {
                assertTrue("could not physically tap the website source option",
                    E2E.fingerTap(By.textContains("Where Is My Train · website")))
                assertTrue("website option stayed open after the tap; no selection was consumed",
                    E2E.ui.wait(Until.gone(By.textContains("Where Is My Train · website")), E2E.LOCAL_MS))
                val selectionDeadline = System.currentTimeMillis() + 5_000L
                var selectedBeforeResponse = false
                while (System.currentTimeMillis() < selectionDeadline) {
                    selectedBeforeResponse = E2E.find("sourceSpinner", 250)?.contentDescription?.toString()
                        ?.contains("Selected Where Is My Train · website") == true
                    if (selectedBeforeResponse) break
                    Thread.sleep(100)
                }
                assertTrue("website selection did not bind synchronously; " +
                    "spinner='${E2E.find("sourceSpinner", 250)?.contentDescription}'",
                    selectedBeforeResponse)
                assertTrue("selected website request did not reach the pre-navigation barrier",
                    navigationEntered.await(E2E.NETWORK_MS + 20_000L, TimeUnit.MILLISECONDS))
                var pendingSummary = ""
                val pendingDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < pendingDeadline) {
                    selectedBeforeResponse = E2E.find("sourceSpinner", 250)?.contentDescription?.toString()
                        ?.contains("Selected Where Is My Train · website") == true
                    pendingSummary = E2E.text("sourceSummary", 250)
                    if (selectedBeforeResponse && pendingSummary.startsWith("Checking ", true)) break
                    Thread.sleep(100)
                }
                E2E.shotWimt("wimt-source-selected-pending")
                assertTrue("pending capture must show the website selected and its response not yet returned; " +
                    "spinner='${E2E.find("sourceSpinner", 250)?.contentDescription}' summary='$pendingSummary'",
                    selectedBeforeResponse && pendingSummary.startsWith("Checking ", true))
                println("WIMT_DEVICE_PENDING_CAPTURE=pre-navigation response_pending=true")
            } finally {
                allowNavigation.countDown()
                WebsiteBrowserRequest.beforeDocumentNavigationForTest = null
            }
            // One deliberate selected-source request; no background retry in the test.
            // The WebView navigation now overlaps route/identity work. Its one-document
            // deadline is 40 seconds total (including the bounded DOM-readiness wait); parsing
            // and UI publication follow with the best context ready at response time. Keep this
            // one-request gate above that deadline and publication work; a 45-second test cap
            // previously ended after the page answered but before selected-source evidence appeared.
            val until = System.currentTimeMillis() + 120_000L
            var accepted = false
            var warning = ""
            var headline = ""
            var badge = ""
            var eventFreshness = ""
            var claimSummary = ""
            var provenanceDescription = ""
            var detachedDescription = ""
            var selectedWebsite = false
            var detachedClaim = false
            while (System.currentTimeMillis() < until) {
                headline = E2E.text("headline", 250)
                badge = E2E.text("provenance", 250)
                warning = E2E.text("connectionAlert", 250)
                eventFreshness = E2E.text("age", 250)
                claimSummary = E2E.text("sourceSummary", 250)
                provenanceDescription = E2E.find("provenance", 250)?.contentDescription?.toString().orEmpty()
                detachedDescription = E2E.find("headline", 250)?.contentDescription?.toString().orEmpty()
                selectedWebsite = E2E.find("sourceSpinner", 250)?.contentDescription?.toString()
                    ?.contains("Selected Where Is My Train · website") == true
                val hasSiteEventTime = Regex("""[0-9]{1,2}:[0-9]{2}""").containsMatchIn(headline)
                val conciseSummary = !claimSummary.contains('\n') &&
                    !claimSummary.contains(".org.in page returned", true)
                val reported = selectedWebsite && headline.contains(" · site ", true) &&
                    hasSiteEventTime && badge.contains("date matched", true) &&
                    !badge.contains("run inferred", true) &&
                    eventFreshness.startsWith("Page age ", true) && conciseSummary &&
                    claimSummary.contains("Position unknown", true) &&
                    claimSummary.contains("website claim only", true) &&
                    provenanceDescription.contains("visible page date matches the selected origin run", true) &&
                    provenanceDescription.contains("Page updated", true) &&
                    provenanceDescription.contains("not independent verification", true)
                detachedClaim = headline.startsWith("Website claims ") &&
                    badge.contains("Detached site claim", true) &&
                    claimSummary.contains("route mismatch", true) &&
                    claimSummary.contains("not attached", true) &&
                    detachedDescription.contains("matches live identity from") &&
                    detachedDescription.contains("selected timetable") &&
                    detachedDescription.contains("page schedule") &&
                    detachedDescription.contains("Page updated") &&
                    detachedDescription.contains("app response received") &&
                    detachedDescription.contains("not attached to timetable rows") &&
                    detachedDescription.contains("not a timetable actual") &&
                    eventFreshness.contains("unverified", true)
                if (reported) { accepted = true; break }
                if (detachedClaim) break // Preserve the visible mismatch; never relax selected-run acceptance.
                if (warning.contains("HTTP 403") || warning.contains("blocks request", true) ||
                    warning.contains("HTTP 429")) break
                Thread.sleep(250)
            }
            E2E.shotWimt("wimt-after-selected-source")
            val measuredTiming = deviceTiming.get()
            println("WIMT_DEVICE_FETCH_TIMING_CAPTURED=${measuredTiming ?: "missing"}")
            assertTrue("the selected WebView attempt did not report gate wait and device-side duration",
                measuredTiming?.let { it.contains("gateWait=") && it.contains("total=") } == true)
            assertTrue("explicit website picker choice did not become the selected source; " +
                "spinner='${E2E.find("sourceSpinner", 250)?.contentDescription}' " +
                "warning='$warning'", selectedWebsite)
            assertTrue("No source-qualified .org.in website claim in the installed app; " +
                "headline='$headline' badge='$badge' eventFreshness='$eventFreshness' " +
                "summary='$claimSummary' description='$detachedDescription' warning='$warning' " +
                "deviceTiming='$measuredTiming'. " +
                "A route-conflicting detached claim is diagnostic only and cannot satisfy selected-run acceptance. " +
                "HTTP 200 alone, NTES fallback and the .in web cache do not pass.", accepted)
            assertTrue("time-sheet not reachable", E2E.reveal("routeHeadActual", "scroller"))
            assertEquals("website data was mislabeled ACTUAL", "WEBSITE", E2E.text("routeHeadActual"))
            val siteCells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            assertTrue("route-matched site report has no painted source-attributed times",
                siteCells.any { it.contentDescription.orEmpty().startsWith("Website displayed") })
        } finally {
            WebsiteBrowserRequest.beforeDocumentNavigationForTest = null
            WebsiteBrowserRequest.timingObserverForTest = null
            // Preserve the original assertion (especially the observed 403) even if
            // this optional last shot fails. Before/after captures are mandatory.
            runCatching { E2E.shotWimt("wimt-final-even-on-failure") }
                .onFailure { println("WIMT_OPTIONAL_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
