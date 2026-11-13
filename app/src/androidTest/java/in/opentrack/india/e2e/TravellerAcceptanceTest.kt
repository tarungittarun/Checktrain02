package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance tests against the RELEASE-DOWNLOAD BYTES, not a separately assembled app APK.
 * Drive only visible controls; a spinner, an arbitrary nonempty status or an empty result is NOT success.
 * We intentionally keep these independent from the older, more permissive flow-counting suite. */
@RunWith(AndroidJUnit4::class)
class TravellerAcceptanceTest {
    @Before fun start() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    @After fun evidence() {
        E2E.shot("traveller-acceptance-final")
        E2E.assertNoSystemDialog("traveller acceptance")
    }

    private fun openTicketTrain() {
        assertTrue("Train mode was not reachable", E2E.tap("modeTrain"))
        assertTrue("Cannot enter the train number", E2E.typeInBox("trainBox", "12627"))
        assertTrue("No suggestion for a known offline train: ${E2E.debugSearch("trainBox")}",
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not select the train", E2E.tapSuggestion("trainBox"))
        assertTrue("Suggestion published train results without Find", E2E.resultRows() == 0)
        assertTrue("Find is not reachable after selecting a train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("Known train did not produce a tappable result; ${E2E.debugSearch("trainBox")}",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("Result is not the train requested: ${E2E.all(E2E.res("hitNo")).map { it.text }}",
            E2E.all(E2E.res("hitNo")).any { it.text.orEmpty().contains("12627") })
        assertTrue("Track on the result did not open a route", E2E.openTrackFromFirstResult())
        assertEquals("Track opened a DIFFERENT train from the ticket", "12627", E2E.text("trainNo"))
    }

    private fun findTextContains(text: String, timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        // AlertDialog buttons may expose their theme-transformed all-caps label
        // (CANCEL) to UiAutomator even though the builder says "Cancel". Try the
        // conventional label first, then its all-caps rendering with the same API
        // used by the rest of this device suite.
        val firstWait = minOf(timeoutMs, 250L)
        E2E.ui.wait(Until.findObject(By.textContains(text)), firstWait)?.let { return it }
        val remaining = (timeoutMs - firstWait).coerceAtLeast(1L)
        return E2E.ui.wait(Until.findObject(By.textContains(text.uppercase(java.util.Locale.ROOT))),
            remaining)
    }

    private fun textContains(text: String, timeoutMs: Long = E2E.LOCAL_MS): String =
        findTextContains(text, timeoutMs)?.text.orEmpty()

    private fun tapTextContains(text: String, timeoutMs: Long = E2E.LOCAL_MS): Boolean {
        val node = findTextContains(text, timeoutMs) ?: return false
        node.click()
        E2E.ui.waitForIdle()
        return true
    }

    private fun visibleTextNodes(): String = E2E.all(By.clazz("android.widget.TextView"))
        .mapNotNull { runCatching { it.text?.toString()?.trim() }.getOrNull()?.takeIf { it.isNotBlank() } }
        .distinct().joinToString(" | ")

    private fun precedingStationChoices(): List<androidx.test.uiautomator.UiObject2> =
        E2E.all(By.textContains("(")).filter {
            it.text.orEmpty().matches(Regex(""".*\([A-Z0-9]{2,6}\).*"""))
        }

    /** CI installs with runtime grants, but accept the real Android prompt if a
     * profile/API still presents one. This only clicks a visible system choice. */
    private fun allowPermissionIfPrompted() {
        for (label in listOf("Allow", "While using the app", "Allow while using the app",
            "Only this time")) {
            val button = E2E.ui.wait(Until.findObject(By.text(label)), 350) ?: continue
            button.click()
            E2E.ui.waitForIdle()
            return
        }
    }

    private fun openAlertChoices() {
        assertTrue("Arrival alert control is not reachable",
            E2E.reveal("arrivalAlert", "scroller", swipes = 4) && E2E.tap("arrivalAlert"))
        allowPermissionIfPrompted()
        assertTrue("Alert trigger chooser did not open: ${textContains("No route for this alert", 300)}",
            E2E.ui.wait(Until.hasObject(By.textContains("Arrival alert · train 12627")),
                E2E.NETWORK_MS))
    }

    /** On a missing badge, distinguish an absent source label from a clipped
     * details viewport. Keep the traveller assertion; never accept a hidden
     * badge just because the model populated it internally. */
    private fun reportGeometry(): String {
        var result = "not laid out"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(
                    androidx.test.runner.lifecycle.Stage.RESUMED).first()
            val badge = activity.findViewById<android.widget.TextView>(
                `in`.opentrack.india.R.id.provenance)
            val details = activity.findViewById<android.widget.ScrollView>(
                `in`.opentrack.india.R.id.heroDetailsScroll)
            val card = activity.findViewById<android.view.View>(
                `in`.opentrack.india.R.id.trackingSummaryCard)
            val scroller = activity.findViewById<android.widget.ScrollView>(
                `in`.opentrack.india.R.id.scroller)
            val footer = activity.findViewById<android.view.View>(
                `in`.opentrack.india.R.id.sourceSpinner).parent as android.view.View
            result = "badge='${badge.text}' visible=${badge.isShown} " +
                "alpha=${badge.alpha} badgeHeight=${badge.height} " +
                "details=${details.height}/${details.getChildAt(0).height} " +
                "detailsScroll=${details.scrollY} card=${card.height} " +
                "footerBottom=${footer.bottom} scroller=${scroller.height}/${scroller.scrollY}"
        }
        return result
    }

    @Test fun ticketToTimetableHasRealRowsAndClearlyQualifiedPosition() {
        openTicketTrain()
        val route = E2E.text("routeLabel")
        assertTrue("Opened train has no route", route.isNotBlank())
        assertTrue("Opened train has no run date", E2E.text("runDate").isNotBlank())
        assertTrue("Too few visible main stops to understand the journey", E2E.count(E2E.res("stopName")) >= 3)
        assertTrue("No scheduled arrival/departure is readable without opening a station",
            E2E.all(E2E.res("schedArr")).plus(E2E.all(E2E.res("schedDep")))
                .any { Regex("\\d{1,2}:\\d{2}").containsMatchIn(it.text.orEmpty()) })
        val badge = E2E.text("provenance")
        val heading = E2E.text("heroEyebrow")
        assertTrue("A traveller cannot tell whether the position is measured or only a timetable: " +
            "$badge / $heading · ${reportGeometry()}",
            badge.isNotBlank() && heading.isNotBlank())
        assertTrue("Offline screen calls a position live: $badge / $heading",
            !E2E.flag("offline") || (!badge.contains("LIVE", true) &&
                !heading.contains("LIVE POSITION", true)))
        E2E.shot("traveller-route")
    }

    @Test fun manualRefreshKeepsTicketAndShowsAnAnswer() {
        openTicketTrain()
        val route = E2E.text("routeLabel")
        // The initial source check can still be in flight after the timetable
        // appears. A second tap during that check is intentionally ignored by
        // the app; time the MANUAL request only after the first one completes.
        val initialDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
        while (System.currentTimeMillis() < initialDeadline && E2E.visible("checkingBar", 250)) {
            Thread.sleep(250)
        }
        assertTrue("Initial source check never stopped; manual refresh cannot begin",
            !E2E.visible("checkingBar", 250))
        assertTrue("No refresh control a traveller can reach", E2E.reveal("refresh", "scroller") && E2E.tap("refresh"))
        val until = System.currentTimeMillis() + E2E.NETWORK_MS
        var receipt = ""
        while (System.currentTimeMillis() < until) {
            // The three-line overlay lasts only two seconds. Its complete
            // receipt stays accessible on the More/Less control afterward.
            E2E.reveal("heroToggle", "scroller", swipes = 3, up = false)
            // The warning can cover the badge, and Android's Spinner can expose
            // its selected child instead of its own description. The always-
            // reachable More/Less control must speak the completed receipt.
            val accessible = E2E.find("heroToggle", 250)?.contentDescription.orEmpty()
            receipt = Regex("""Checked \d{2}:\d{2}:\d{2} · [^.]+""")
                .find(accessible)?.value.orEmpty()
            if (receipt.startsWith("Checked ") && !receipt.contains("Checking", true) &&
                !receipt.contains("Refreshing", true) && !receipt.contains("Loading", true)) break
            Thread.sleep(250)
        }
        assertTrue("Refresh must not leave a persistent receipt below the card",
            E2E.find("refreshFeedback", 250) == null)
        assertTrue("Refresh never gave a completed, accessible receipt: '$receipt'; " +
            "toggle='${E2E.find("heroToggle", 250)?.contentDescription}' " +
            "badge='${E2E.find("provenance", 250)?.contentDescription}' " +
            "source='${E2E.find("sourceSpinner", 250)?.contentDescription}' " +
            "warning='${E2E.text("connectionAlert", 250)}' " +
            "busy=${E2E.visible("checkingBar", 250)}", receipt.startsWith("Checked ") &&
            !receipt.contains("Checking", true) && !receipt.contains("Refreshing", true) &&
            !receipt.contains("Loading", true))
        assertEquals("Refresh changed the ticket's train", "12627", E2E.text("trainNo"))
        val afterRoute = E2E.text("routeLabel")
        val baseRoute = "SBC → NDLS · 2406 km"
        val confirmedRoute = "$baseRoute · identity confirmed live (via erail)"
        // A parallel erail identity request may finish during refresh. Assert the
        // exact corridor/distance AND permit only the one-way addition of its
        // explicitly attributed identity; no other route mutation is acceptable.
        assertTrue("Initial ticket had a different corridor or unsupported identity: $route",
            route == baseRoute || route == confirmedRoute)
        assertTrue("Refresh erased or changed the route: $route -> $afterRoute",
            afterRoute == route || route == baseRoute && afterRoute == confirmedRoute)
        assertTrue("Refresh erased the station list", E2E.count(E2E.res("stopName")) > 0)
        E2E.shot("traveller-refresh")
    }

    @Test fun batteryRefreshIsUnmistakableAndTicketSurvivesModeSwitch() {
        openTicketTrain()
        val trainBefore = E2E.text("trainNo")
        val routeBefore = E2E.text("routeLabel")
        val runDateBefore = E2E.text("runDate")
        assertEquals("the selected ticket is wrong before changing presentation", "12627", trainBefore)
        assertTrue("the opened journey has no route or run date", routeBefore.isNotBlank() && runDateBefore.isNotBlank())
        assertTrue("the opened journey has no stop rows", E2E.count(E2E.res("stopName")) > 0)
        E2E.ui.pressBack()
        assertTrue("Back from the journey lost its search result",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("Settings could not be opened after returning from the journey", E2E.tap("settings"))
        assertTrue("Settings did not show the Battery toggle", E2E.visible("batteryMode"))
        val battery = E2E.find("batteryMode") ?: throw AssertionError("Battery toggle disappeared")
        if (!battery.isChecked) assertTrue("Battery mode could not be enabled", E2E.tap("batteryMode"))
        assertTrue("Battery mode did not persist through Settings recreation",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.NETWORK_MS) &&
                E2E.find("batteryMode")?.isChecked == true)
        E2E.shot("traveller-battery-enabled")
        E2E.ui.pressBack()
        assertTrue("Battery mode switch erased the selected search results",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("The existing result could not reopen its journey after the mode switch",
            E2E.openTrackFromFirstResult())
        assertEquals("Mode switch changed the selected train", trainBefore, E2E.text("trainNo"))
        assertEquals("Mode switch changed the selected route", routeBefore, E2E.text("routeLabel"))
        assertEquals("Mode switch changed the selected run date", runDateBefore, E2E.text("runDate"))
        assertTrue("Mode switch erased the journey's timetable rows", E2E.count(E2E.res("stopName")) > 0)

        var label = ""
        var labelColor = 0
        var labelSizeSp = 0f
        var labelBold = false
        var fill = 0
        var targetWidthDp = 0f
        var targetHeightDp = 0f
        var refreshDescription = ""
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(
                    androidx.test.runner.lifecycle.Stage.RESUMED).first()
            val button = activity.findViewById<android.view.View>(`in`.opentrack.india.R.id.refresh)
            val frame = button as android.view.ViewGroup
            val text = (0 until frame.childCount).mapNotNull { frame.getChildAt(it) as? android.widget.TextView }
                .firstOrNull { it.text.toString() == "Refresh" }
            label = text?.text?.toString().orEmpty()
            labelColor = text?.currentTextColor ?: 0
            labelSizeSp = (text?.textSize ?: 0f) / activity.resources.displayMetrics.scaledDensity
            labelBold = text?.typeface?.isBold == true
            fill = (button.background as? android.graphics.drawable.GradientDrawable)
                ?.color?.defaultColor ?: 0
            targetWidthDp = button.width / activity.resources.displayMetrics.density
            targetHeightDp = button.height / activity.resources.displayMetrics.density
            refreshDescription = button.contentDescription?.toString().orEmpty()
        }
        assertTrue("Battery Refresh label is absent or too small: '$label' $labelSizeSp sp",
            label == "Refresh" && labelSizeSp >= 12f && labelBold)
        assertEquals("Battery Refresh must invert to white on the black OLED surface",
            android.graphics.Color.WHITE, fill)
        assertEquals("Battery Refresh's white button requires black label ink",
            android.graphics.Color.BLACK, labelColor)
        assertTrue("Battery Refresh target is below 48dp: $targetWidthDp × $targetHeightDp",
            targetWidthDp >= 48f && targetHeightDp >= 48f)
        assertTrue("Battery Refresh has no spoken action label",
            refreshDescription.contains("Refresh selected source"))
        assertTrue("Refresh button is not visible to a traveller", E2E.visible("refresh"))
        E2E.shot("traveller-battery-refresh")

        E2E.ui.pressBack()
        assertTrue("Search screen disappeared before restoring normal mode",
            E2E.ui.wait(Until.hasObject(E2E.res("settings")), E2E.NETWORK_MS))
        assertTrue("Settings could not be reopened to turn Battery mode off", E2E.tap("settings"))
        assertTrue("Battery toggle disappeared while restoring normal mode", E2E.visible("batteryMode"))
        if (E2E.find("batteryMode")?.isChecked == true)
            assertTrue("Battery mode could not be turned off", E2E.tap("batteryMode"))
        assertTrue("Normal appearance did not persist through Settings recreation",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.NETWORK_MS) &&
                E2E.find("batteryMode")?.isChecked == false)
        E2E.ui.pressBack()
        assertTrue("Returning to normal mode erased the existing search result",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
    }

    @Test fun arrivalAlertSetupShowsTriggersPreferencesAndSaveCancel() {
        openTicketTrain()
        openAlertChoices()
        assertTrue("Destination report choice is hidden",
            E2E.ui.wait(Until.hasObject(By.textContains("At destination")), E2E.LOCAL_MS))
        assertTrue("Preceding-station choice is hidden",
            E2E.ui.wait(Until.hasObject(By.textContains("preceding station")), E2E.LOCAL_MS))
        for (distance in listOf(2, 5, 10)) assertTrue("$distance km distance choice is hidden",
            E2E.ui.wait(Until.hasObject(By.textContains("Within $distance km")), E2E.LOCAL_MS))
        E2E.shot("traveller-alert-triggers")

        assertTrue("Preceding-station choice did not open its station list",
            tapTextContains("preceding station"))
        assertTrue("The preceding-station chooser is absent",
            E2E.ui.wait(Until.hasObject(By.textContains("Choose a preceding station")), E2E.LOCAL_MS))
        assertTrue("No preceding route station can be selected; visible text: ${visibleTextNodes()}",
            precedingStationChoices().isNotEmpty())
        assertTrue("Preceding-station chooser has no clear cancel action; visible text: ${visibleTextNodes()}",
            tapTextContains("Cancel"))
        assertEquals("cancelling trigger selection must not arm an alert", "Alert", E2E.text("arrivalAlert"))

        openAlertChoices()
        assertTrue("Preceding-station choice did not reopen its station list",
            tapTextContains("preceding station"))
        val stationChoice = precedingStationChoices().firstOrNull()
        assertTrue("No preceding station could be selected; visible text: ${visibleTextNodes()}",
            stationChoice != null)
        stationChoice!!.click()
        E2E.ui.waitForIdle()
        assertTrue("Selecting a preceding station did not open alert preferences",
            E2E.ui.wait(Until.hasObject(By.text("Alert style")), E2E.LOCAL_MS))
        assertTrue("Cancel after station selection did not close setup", tapTextContains("Cancel"))
        assertEquals("cancelling a preceding-station alert must not arm it", "Alert", E2E.text("arrivalAlert"))

        openAlertChoices()
        assertTrue("Destination trigger did not open alert preferences", tapTextContains("At destination"))
        assertTrue("Alert style choices are missing", E2E.ui.wait(Until.hasObject(By.text("Alert style")), E2E.LOCAL_MS))
        for (style in listOf("Notification · one alert", "Vibration · one pattern", "Ring until I stop it"))
            assertTrue("Alert style is not selectable: $style",
                E2E.ui.wait(Until.hasObject(By.text(style)), E2E.LOCAL_MS))
        for (interval in listOf("1 minute", "2 minutes", "5 minutes"))
            assertTrue("Alert interval is not selectable: $interval",
                E2E.ui.wait(Until.hasObject(By.textContains(interval)), E2E.LOCAL_MS))
        assertTrue("Set alert action is absent", textContains("Set alert").isNotBlank())
        assertTrue("Setup dialog has no Cancel action", textContains("Cancel").isNotBlank())
        var evidenceNote = textContains("Station alerts use fresh, route-matched", 500)
        repeat(3) {
            if (evidenceNote.isNotBlank()) return@repeat
            val scroll = E2E.all(By.clazz("android.widget.ScrollView"))
                .maxByOrNull { it.visibleBounds.height() } ?: return@repeat
            val bounds = scroll.visibleBounds
            if (bounds.height() > 100) E2E.ui.swipe(bounds.centerX(), bounds.bottom - 35,
                bounds.centerX(), bounds.top + 35, 20)
            evidenceNote = textContains("Station alerts use fresh, route-matched", 500)
        }
        assertTrue("Station alert live-evidence limits were not visible in setup: '$evidenceNote'",
            evidenceNote.contains("whereismytrain.org.in") &&
                evidenceNote.contains("not independently verified"))
        E2E.shot("traveller-alert-preferences")
        assertTrue("Cancel did not close alert setup", tapTextContains("Cancel"))
        assertEquals("cancelling preferences must not create an alert", "Alert", E2E.text("arrivalAlert"))

        openAlertChoices()
        assertTrue("Distance trigger did not open alert preferences", tapTextContains("Within 2 km"))
        val distanceEvidenceNote = textContains("Distance uses phone location", 1_000)
        assertTrue("Distance alert limits were not explained for the selected trigger: '$distanceEvidenceNote'",
            distanceEvidenceNote.contains("250 m accuracy") &&
                distanceEvidenceNote.contains("straight-line distance—not train GPS"))
        assertTrue("Vibration preference cannot be selected", tapTextContains("Vibration · one pattern"))
        assertTrue("Five-minute low-work interval cannot be selected",
            tapTextContains("5 minutes · lower background work"))
        assertTrue("Set alert action is missing before save", tapTextContains("Set alert"))
        allowPermissionIfPrompted()
        assertTrue("Set alert did not save or start: '${textContains("Permission needed for arrival alerts", 300)}'",
            E2E.ui.wait(Until.hasObject(By.text("Alert on")), E2E.NETWORK_MS))
        assertTrue("Saved alert is not visible on its control",
            E2E.text("arrivalAlert") == "Alert on")
        E2E.shot("traveller-alert-saved")
        assertTrue("Saved alert details cannot be reopened", E2E.tap("arrivalAlert"))
        assertTrue("Saved distance trigger is not identified",
            E2E.ui.wait(Until.hasObject(By.textContains("Within 2 km")), E2E.LOCAL_MS))
        assertTrue("Saved vibration preference is not identified",
            E2E.ui.wait(Until.hasObject(By.textContains("Vibration · one pattern")), E2E.LOCAL_MS))
        assertTrue("Saved five-minute low-work interval is not identified",
            E2E.ui.wait(Until.hasObject(By.textContains("Check interval · 5 minutes")), E2E.LOCAL_MS))
        assertTrue("An armed alert has no clear cancel action", tapTextContains("Cancel alert"))
        assertTrue("Cancel alert did not clear the saved monitor",
            E2E.ui.wait(Until.hasObject(By.text("Alert")), E2E.NETWORK_MS))
        assertEquals("Alert", E2E.text("arrivalAlert"))
    }

    @Test fun invalidOrImpossibleTripCannotMasqueradeAsAResult() {
        assertTrue("Board-at search is unavailable", E2E.typeInBox("fromBox", "yes"))
        assertTrue("Known station has no suggestion", E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not pick boarding station", E2E.tapSuggestion("fromBox"))
        assertEquals("A single station must not return trains before Find", 0, E2E.resultRows())
        assertTrue("Get-off-at search is unavailable", E2E.typeInBox("toBox", "yes"))
        // On the resized keyboard viewport a populated suggestion can sit below
        // the search-card fold. Scroll to the actual result; never accept a blank
        // panel as a match or skip the same-station refusal check.
        val sameStationSuggestion = E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) ||
            E2E.reveal("sugPrimary", "searchCardScroll", swipes = 4)
        if (!sameStationSuggestion) E2E.shot("same-station-suggestion-hidden")
        assertTrue("Same station has no reachable suggestion: ${E2E.debugSearch("toBox")}",
            sameStationSuggestion)
        assertTrue("Could not pick destination", E2E.tapSuggestion("toBox"))
        assertEquals("Picking the second station must wait for Find", 0, E2E.resultRows())
        assertTrue("Find missing for same-station validation",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("Same-station trip was not explicitly refused: '${E2E.text("statusLine")}'",
            listOf("same", "different").any { E2E.text("statusLine").contains(it, ignoreCase = true) })
        // ListAdapter diffs asynchronously: a previous search can still be in
        // the accessibility snapshot for a frame after the refusal is painted.
        assertTrue("Impossible trip kept stale train results after 3 seconds",
            E2E.ui.wait(Until.gone(E2E.res("hitNo")), 3_000L))
        assertEquals("Impossible trip still displays train results", 0, E2E.resultRows())
        E2E.shot("traveller-invalid-trip")
    }

    @Test fun settingsRoundTripReturnsToUsableSearch() {
        assertTrue("Settings entry does not respond", E2E.tap("settings"))
        assertTrue("Settings opened no usable screen", E2E.visible("settingsScroll"))
        // Battery mode must be a separate opt-in, and turning it OFF must restore
        // the ordinary search in this same published APK, without an uninstall.
        assertTrue("Battery toggle missing or unexpectedly enabled",
            E2E.find("batteryMode")?.isChecked == false)
        assertTrue("Battery toggle cannot be enabled", E2E.tap("batteryMode"))
        assertTrue("Battery toggle did not persist through screen recreation",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.LOCAL_MS) &&
                E2E.find("batteryMode")?.isChecked == true)
        var bars = -1
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(
                    androidx.test.runner.lifecycle.Stage.RESUMED).first()
            bars = activity.window.statusBarColor
        }
        assertEquals("Battery mode did not black out system bars", android.graphics.Color.BLACK, bars)
        E2E.shot("traveller-battery-settings")
        assertTrue("Battery toggle cannot be disabled", E2E.tap("batteryMode"))
        assertTrue("Battery mode did not switch off",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.LOCAL_MS) &&
                E2E.find("batteryMode")?.isChecked == false)
        E2E.ui.pressBack()
        assertTrue("Back from Settings did not return to search", E2E.visible("modeTrain"))
        assertTrue("Search cannot be used after Settings", E2E.tap("modeTrain") &&
            E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        E2E.shot("traveller-settings-return")
    }

    @Test fun primaryControlsHaveLegibleTouchTargets() {
        val dp = E2E.densityDpi().toFloat() / 160f
        for (id in listOf("settings", "modeTrain", "modeStations")) {
            val control = E2E.find(id) ?: throw AssertionError("Missing visible $id control")
            val bounds = control.visibleBounds
            assertTrue("$id touch target too narrow: ${bounds.width() / dp}dp", bounds.width() / dp >= 48f)
            assertTrue("$id touch target too short: ${bounds.height() / dp}dp", bounds.height() / dp >= 48f)
            assertTrue("$id has no accessible spoken label",
                control.contentDescription.orEmpty().isNotBlank() || control.text.orEmpty().isNotBlank())
        }
        E2E.shot("traveller-controls")
    }
}
