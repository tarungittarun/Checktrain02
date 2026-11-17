package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertState
import `in`.opentrack.india.alarm.AlertTrigger
import `in`.opentrack.india.alarm.JourneyAlarmConfig
import `in`.opentrack.india.alarm.JourneyAlarmService
import `in`.opentrack.india.alarm.JourneyAlarmStore
import `in`.opentrack.india.core.RailTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance tests against the exact same-run build-artifact bytes, not a second app build.
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

    /** The source gate is intentionally strict, so offline acceptance cannot create a live alert.
     * This explicit local fixture lets the same profile still exercise Alerts' management controls;
     * its status is labeled CI fixture and is never treated as train evidence. */
    private fun seedCiJourneyAlert(context: android.content.Context = E2E.context()): Int {
        var trainNo = ""
        var fromCode = ""
        var toCode = ""
        var originDay = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .firstOrNull { it.javaClass.name.endsWith(".TrainActivity") }
                ?: throw AssertionError("The selected train screen was not resumed for the CI fixture")
            val intent = activity.intent
            trainNo = intent.getStringExtra("trainNo").orEmpty()
            fromCode = intent.getStringExtra("fromCode").orEmpty()
            toCode = intent.getStringExtra("toCode").orEmpty()
            originDay = intent.getLongExtra("originEpochDay", RailTime.todayEpochDay())
        }
        assertTrue("The CI alert fixture could not read the selected journey",
            trainNo == "12627" && fromCode.isNotBlank() && toCode.isNotBlank() && fromCode != toCode)
        val config = JourneyAlarmConfig(
            trainNo = trainNo, trainName = "CI lifecycle fixture",
            fromCode = fromCode, toCode = toCode, toName = toCode,
            toSeq = 2, trigger = AlertTrigger.DESTINATION_REPORT,
            triggerCode = toCode, triggerName = toCode, triggerSeq = 2,
            distanceKm = 0, feedback = AlertFeedback.VIBRATION,
            intervalMinutes = 7, repeatIntervalSeconds = 15, originEpochDay = originDay,
            baselineSeq = 0, baselineEventMinute = null,
            createdAtMillis = System.currentTimeMillis(),
        )
        val id = JourneyAlarmStore.arm(context, config)
        JourneyAlarmStore.update(context, id, AlertState.ARMED,
            "CI fixture · local alert only; no source evidence", checkFailed = false)
        return id
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

    private fun alertStationOption(label: String): androidx.test.uiautomator.BySelector =
        By.res(E2E.PKG, "alertTriggerOption").text(label)

    private fun selectSpinnerOption(description: String, label: String): Boolean {
        val spinner = E2E.ui.wait(Until.findObject(By.desc(description)), E2E.LOCAL_MS) ?: return false
        if (!E2E.fingerTap(spinner)) return false
        val option = findTextContains(label, E2E.LOCAL_MS) ?: return false
        if (!visibleOnScreen(option)) return false
        return runCatching {
            option.click()
            E2E.ui.waitForIdle()
            true
        }.getOrDefault(false)
    }

    /** Radio selection can redraw a frame after the click callback returns. Do not save
     * screenshot proof until the exact option is checked and every competitor is unchecked. */
    private fun tapRadioAndWait(label: String, competingOptions: List<String>, container: String,
                                timeoutMs: Long = E2E.LOCAL_MS): Boolean {
        val selector = By.clazz("android.widget.RadioButton").text(label)
        val radio = E2E.ui.wait(Until.findObject(selector), timeoutMs) ?: return false
        if (!visibleInScroll(radio, container)) return false
        if (!radio.isChecked) {
            runCatching { radio.click() }.getOrElse { return false }
        }
        fun isExclusiveSelection(): Boolean {
            val selected = E2E.ui.findObject(selector) ?: return false
            if (!selected.isChecked || !visibleInScroll(selected, container)) return false
            return competingOptions.all { other ->
                val peer = E2E.ui.findObject(
                    By.clazz("android.widget.RadioButton").text(other))
                peer != null && visibleInScroll(peer, container) && !peer.isChecked
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isExclusiveSelection()) {
                E2E.ui.waitForIdle()
                Thread.sleep(200) // let the touch ripple finish before the proof frame
                return isExclusiveSelection()
            }
            Thread.sleep(50)
        }
        return false
    }

    private fun visibleOnScreen(node: androidx.test.uiautomator.UiObject2?): Boolean {
        val bounds = node?.visibleBounds ?: return false
        return bounds.width() > 0 && bounds.height() > 0
    }

    private fun visibleResource(name: String): Boolean =
        visibleOnScreen(E2E.find(name, 300))

    private fun visibleInScroll(node: androidx.test.uiautomator.UiObject2?, container: String): Boolean {
        if (!visibleOnScreen(node)) return false
        val item = node!!.visibleBounds
        val viewport = E2E.find(container, 500)?.visibleBounds ?: return false
        // UiObject2 can expose a clipped child with a non-empty intersection. Treat that
        // as *not revealed*: Alerts choices must be wholly visible inside their own scroll
        // viewport, not just leave a few pixels above the fixed Cancel/Set-alert footer.
        return item.left >= viewport.left && item.top >= viewport.top &&
            item.right <= viewport.right && item.bottom <= viewport.bottom
    }

    private fun revealTextContains(text: String, container: String, swipes: Int = 6,
                                   up: Boolean = true): Boolean {
        repeat(swipes + 1) { attempt ->
            val node = findTextContains(text, 300)
            if (visibleInScroll(node, container)) return true
            if (attempt == swipes || !E2E.swipeIn(container, up = up)) return false
        }
        return false
    }

    private fun revealDescription(description: String, container: String, swipes: Int = 6,
                                  up: Boolean = true): Boolean {
        repeat(swipes + 1) { attempt ->
            val node = E2E.ui.wait(Until.findObject(By.desc(description)), 300)
            if (visibleInScroll(node, container)) return true
            if (attempt == swipes || !E2E.swipeIn(container, up = up)) return false
        }
        return false
    }

    /** Radio options below the fold are not accessibility nodes until scrolled into view.
     * Scope this lookup to radio buttons so a similarly worded lead-time Spinner label
     * cannot satisfy the check-interval assertion. */
    private fun revealRadioContains(text: String, container: String, swipes: Int = 6,
                                    up: Boolean = true): Boolean {
        val selector = By.clazz("android.widget.RadioButton").textContains(text)
        repeat(swipes + 1) { attempt ->
            val node = E2E.ui.wait(Until.findObject(selector), 300)
            if (visibleInScroll(node, container)) return true
            if (attempt == swipes || !E2E.swipeIn(container, up = up)) return false
        }
        return false
    }

    private fun visibleTextNodes(): String = E2E.all(By.clazz("android.widget.TextView"))
        .mapNotNull { runCatching { it.text?.toString()?.trim() }.getOrNull()?.takeIf { it.isNotBlank() } }
        .distinct().joinToString(" | ")

    /** CI usually installs with runtime grants; accept only a visible affirmative system
     * permission choice if an API/profile still presents the notification prompt. */
    private fun allowPermissionIfPrompted(firstPromptWaitMs: Long = 8_000L) {
        val systemPermissionId = "com.android.permissioncontroller"
        val permissionPackages = listOf("com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.android.packageinstaller")
        val affirmativeLabels = listOf("While using the app", "Allow while using the app",
            "Only this time", "Allow")
        val affirmativeChoices = listOf(
            By.res(systemPermissionId, "permission_allow_foreground_only_button"),
            By.res(systemPermissionId, "permission_allow_one_time_button"),
            By.res(systemPermissionId, "permission_allow_button"),
        ) + permissionPackages.flatMap { pkg ->
            affirmativeLabels.map { label -> By.pkg(pkg).text(label) }
        }
        val firstPromptDeadline = System.currentTimeMillis() + firstPromptWaitMs
        var promptsAccepted = 0
        var quietSince: Long? = null
        while (promptsAccepted < 3) {
            val button = affirmativeChoices.asSequence()
                .mapNotNull { selector -> runCatching { E2E.ui.findObject(selector) }.getOrNull() }
                .firstOrNull()
            if (button != null) {
                val label = runCatching { button.text.orEmpty() }.getOrDefault("")
                var clicked = runCatching { button.click(); true }.getOrDefault(false)
                if (!clicked) {
                    val bounds = runCatching { button.visibleBounds }.getOrNull()
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0)
                        clicked = runCatching {
                            E2E.ui.click(bounds.centerX(), bounds.centerY())
                        }.getOrDefault(false)
                }
                if (!clicked) {
                    println("E2E_PERMISSION_PROMPT_NOT_CLICKED label=$label")
                    return
                }
                println("E2E_PERMISSION_PROMPT_ACCEPTED label=$label")
                promptsAccepted++
                quietSince = null
                E2E.ui.waitForIdle()
                Thread.sleep(200)
                continue
            }
            val now = System.currentTimeMillis()
            if (promptsAccepted == 0) {
                if (now >= firstPromptDeadline) return
            } else {
                if (quietSince == null) quietSince = now
                if (now - quietSince >= 1_500L) return
            }
            Thread.sleep(75)
        }
    }

    private fun waitForAlertSetup(): Boolean {
        val deadline = System.currentTimeMillis() + E2E.NETWORK_MS
        val chooser = By.textContains("Arrival alert · train 12627")
        val routeRefusal = By.textContains("No route for this alert")
        val permissionRefusal = By.textContains("Permission needed for arrival alerts")
        while (System.currentTimeMillis() < deadline) {
            if (E2E.ui.findObject(chooser) != null) return true
            if (E2E.ui.findObject(routeRefusal) != null ||
                E2E.ui.findObject(permissionRefusal) != null) return false
            Thread.sleep(150)
        }
        return E2E.ui.findObject(chooser) != null
    }

    private fun openAlertSetup(screenshotTag: String? = null) {
        assertTrue("Arrival alert control is not visible on screen",
            visibleResource("arrivalAlert") && E2E.tap("arrivalAlert"))
        allowPermissionIfPrompted()
        val setupOpened = waitForAlertSetup()
        if (!setupOpened) E2E.shotDiagnostic("alert-diagnostic-open-failure")
        assertTrue("Four-field alert setup did not open; visible app text='${visibleTextNodes()}'; " +
            "arrival control='${E2E.text("arrivalAlert", 300)}' description='" +
            "${E2E.find("arrivalAlert", 300)?.contentDescription.orEmpty()}'.", setupOpened)
        assertTrue("Alert setup is missing its scrollable fields",
            E2E.ui.wait(Until.hasObject(E2E.res("alertDialogScroll")), E2E.LOCAL_MS))
        assertTrue("Reusable four-field editor is missing",
            E2E.ui.wait(Until.hasObject(E2E.res("alertDialogBody")), E2E.LOCAL_MS))
        for (field in listOf("1 · Upcoming station", "2 · Notify style",
                "3 · Frequency / duration", "4 · Check frequency"))
            assertTrue("Alert setup is missing '$field'",
                revealTextContains(field, "alertDialogScroll"))
        assertTrue("Upcoming station section cannot be brought back into view",
            revealTextContains("1 · Upcoming station", "alertDialogScroll", up = false))
        assertTrue("Upcoming station picker is missing",
            E2E.ui.findObject(By.desc("Upcoming station through the final station")) != null)
        screenshotTag?.let { E2E.shotRequired(it) }
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
        E2E.shotRequired("traveller-route")
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
        E2E.shotRequired("traveller-refresh")
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
        E2E.shotRequired("traveller-battery-enabled")
        E2E.ui.pressBack()
        assertTrue("Battery mode switch erased the selected search results",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("The existing result could not reopen its journey after the mode switch",
            E2E.openTrackFromFirstResult())
        assertEquals("Mode switch changed the selected train", trainBefore, E2E.text("trainNo"))
        val routeAfterModeSwitch = E2E.text("routeLabel")
        val identitySuffix = " · identity confirmed live (via erail)"
        val routeIdentityPreserved = routeBefore == routeAfterModeSwitch ||
            (routeBefore == routeBefore.removeSuffix(identitySuffix) &&
                routeAfterModeSwitch == routeBefore + identitySuffix)
        assertTrue("Mode switch changed the selected route or erased its identity: " +
            "'$routeBefore' -> '$routeAfterModeSwitch'", routeIdentityPreserved)
        assertEquals("Mode switch changed the selected run date", runDateBefore, E2E.text("runDate"))
        assertTrue("Mode switch erased the journey's timetable rows", E2E.count(E2E.res("stopName")) > 0)

        var label = ""
        var labelColor = 0
        var labelSizeSp = 0f
        var labelBold = false
        var fill = 0
        var expectedFill = 0
        var expectedInk = 0
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
            expectedFill = activity.getColor(`in`.opentrack.india.R.color.refresh_surface)
            expectedInk = activity.getColor(`in`.opentrack.india.R.color.refresh_ink)
            targetWidthDp = button.width / activity.resources.displayMetrics.density
            targetHeightDp = button.height / activity.resources.displayMetrics.density
            refreshDescription = button.contentDescription?.toString().orEmpty()
        }
        assertTrue("Battery Refresh label is absent or too small: '$label' $labelSizeSp sp",
            label == "Refresh" && labelSizeSp >= 12f && labelBold)
        assertEquals("Battery Refresh uses its app accent, not a full white tile",
            expectedFill, fill)
        assertTrue("Battery Refresh accent must remain distinct from OLED black/white",
            fill != android.graphics.Color.BLACK && fill != android.graphics.Color.WHITE)
        assertEquals("Battery Refresh uses readable ink for its accent surface",
            expectedInk, labelColor)
        assertTrue("Battery Refresh target is below 48dp: $targetWidthDp × $targetHeightDp",
            targetWidthDp >= 48f && targetHeightDp >= 48f)
        assertTrue("Battery Refresh has no spoken action label",
            refreshDescription.contains("Refresh selected source"))
        assertTrue("Refresh button is not visible to a traveller", E2E.visible("refresh"))
        E2E.shotRequired("traveller-battery-refresh")

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

    @Test fun arrivalAlertSetupShowsFourFieldsAndLifecycleControls() {
        openTicketTrain()
        assertTrue("Arrival alert control is not visible on the journey screen",
            visibleResource("arrivalAlert"))
        E2E.shotRequired("alert-journey-entry")

        openAlertSetup("alert-setup-fields")
        for (style in listOf("Ping", "Vibrate", "Ring · turn off manually"))
            assertTrue("Notify style is missing or unreachable: $style",
                revealRadioContains(style, "alertDialogScroll"))
        assertTrue("Station picker cannot be brought back into view",
            revealTextContains("1 · Upcoming station", "alertDialogScroll", up = false))
        val stationSpinner = E2E.ui.findObject(By.desc("Upcoming station through the final station"))
            ?: throw AssertionError("Upcoming station picker disappeared")
        assertTrue("Upcoming station picker is outside its setup viewport",
            visibleInScroll(stationSpinner, "alertDialogScroll"))
        val stationOptionPattern = Regex(""".+\([A-Z0-9]{2,6}\) · .+""")
        val selectedStation = stationSpinner.text.orEmpty().ifBlank {
            E2E.all(By.clazz("android.widget.TextView")).mapNotNull { it.text?.toString() }
                .firstOrNull(stationOptionPattern::matches).orEmpty()
        }
        assertTrue("The route's selected station is not identified: '$selectedStation'",
            stationOptionPattern.matches(selectedStation))
        assertTrue("Station setup unexpectedly exposes the removed phone-radius choice",
            E2E.ui.findObject(By.desc("Phone-distance radius")) == null)

        assertTrue("Upcoming station picker did not open", E2E.fingerTap(stationSpinner))
        val stationOptionSelector = By.res(E2E.PKG, "alertTriggerOption")
        var intermediateLabel: String? = null
        val stationDeadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (System.currentTimeMillis() < stationDeadline && intermediateLabel == null) {
            intermediateLabel = E2E.all(stationOptionSelector).firstNotNullOfOrNull { node ->
                val label = runCatching { node.text.orEmpty() }.getOrDefault("")
                val visible = runCatching { visibleOnScreen(node) }.getOrDefault(false)
                label.takeIf {
                    visible && stationOptionPattern.matches(it) && it != selectedStation
                }
            }
            if (intermediateLabel == null) Thread.sleep(75)
        }
        assertTrue("The station picker did not expose a selectable intermediate stop: ${visibleTextNodes()}",
            intermediateLabel != null)
        val chosenIntermediate = intermediateLabel.orEmpty()
        val option = E2E.ui.wait(
            Until.findObject(alertStationOption(chosenIntermediate)), E2E.LOCAL_MS)
        assertTrue("The intermediate stop is not visible in the station dropdown",
            option != null && runCatching { visibleOnScreen(option) }.getOrDefault(false))
        E2E.shotRequired("alert-station-dropdown")
        val currentOption = E2E.ui.wait(
            Until.findObject(alertStationOption(chosenIntermediate)), E2E.LOCAL_MS)
        val selectionClicked = runCatching {
            currentOption?.click()
            E2E.ui.waitForIdle()
            currentOption != null
        }.getOrDefault(false)
        assertTrue("An intermediate upcoming station could not be selected", selectionClicked)
        assertTrue("The station dropdown stayed open after selection",
            E2E.ui.wait(Until.gone(By.res(E2E.PKG, "alertTriggerOption")), E2E.LOCAL_MS))
        assertTrue("The station picker did not retain the selected intermediate stop",
            revealTextContains(chosenIntermediate, "alertDialogScroll"))
        E2E.shotRequired("alert-station-intermediate-selected")
        assertTrue("Cancel did not close alert setup", tapTextContains("Cancel"))
        assertEquals("Cancelling setup must not arm an alert", "Alert", E2E.text("arrivalAlert"))
        E2E.shotRequired("alert-setup-cancelled")

        // Open a fresh setup so the save below uses the train journey's original, future destination.
        openAlertSetup("alert-setup-reopened")
        assertTrue("The manual-stop ring option is not reachable",
            revealRadioContains("Ring · turn off manually", "alertDialogScroll"))
        assertTrue("Ring style could not be selected",
            tapRadioAndWait("Ring · turn off manually", listOf("Ping", "Vibrate"),
                "alertDialogScroll"))
        assertTrue("Ring does not explain its manual stop behavior",
            revealTextContains("Ring continues until you turn it off", "alertDialogScroll"))
        assertTrue("A manually stopped ring should not offer a ping repeat interval",
            E2E.ui.findObject(By.desc("Ping or vibration repeat interval")) == null)
        E2E.shotRequired("alert-ring-manual-stop")
        assertTrue("Vibration style could not be brought back into view",
            revealRadioContains("Vibrate", "alertDialogScroll", up = false))
        assertTrue("Vibration style could not be selected",
            tapRadioAndWait("Vibrate", listOf("Ping", "Ring · turn off manually"),
                "alertDialogScroll"))

        val repeatDescription = "Ping or vibration repeat interval"
        assertTrue("Repeat-frequency section cannot be brought into view",
            revealTextContains("3 · Frequency / duration", "alertDialogScroll"))
        assertTrue("Ping/vibration repeat selector is missing",
            E2E.ui.findObject(By.desc(repeatDescription)) != null)
        assertTrue("One-shot ping/vibration is not selectable",
            selectSpinnerOption(repeatDescription, "Once"))
        E2E.shotRequired("alert-frequency-once")
        assertTrue("Five-second repeat preset is missing",
            selectSpinnerOption(repeatDescription, "Every 5 seconds"))
        assertTrue("Ten-second repeat preset is missing",
            selectSpinnerOption(repeatDescription, "Every 10 seconds"))
        assertTrue("Custom repeat interval is missing",
            selectSpinnerOption(repeatDescription, "Custom seconds…"))
        val customRepeat = E2E.ui.wait(
            Until.findObject(By.desc("Custom ping or vibration repeat interval in seconds")), E2E.LOCAL_MS)
        assertTrue("Custom repeat-seconds field did not appear in setup",
            visibleInScroll(customRepeat, "alertDialogScroll"))
        E2E.setTextIn(customRepeat!!, "15")
        E2E.ui.pressBack() // dismiss the IME before scrolling to the next field

        assertTrue("Check-frequency field cannot be brought into view",
            revealTextContains("4 · Check frequency", "alertDialogScroll"))
        val checkDescription = "Alert source check frequency in minutes"
        assertTrue("Source-check interval selector is missing",
            E2E.ui.findObject(By.desc(checkDescription)) != null)
        assertTrue("Two-minute check preset is missing",
            selectSpinnerOption(checkDescription, "2 minutes"))
        assertTrue("Five-minute check preset is missing",
            selectSpinnerOption(checkDescription, "5 minutes"))
        assertTrue("Ten-minute check preset is missing",
            selectSpinnerOption(checkDescription, "10 minutes"))
        assertTrue("Custom check interval is missing",
            selectSpinnerOption(checkDescription, "Custom minutes…"))
        val customCheck = E2E.ui.wait(
            Until.findObject(By.desc("Custom alert source check frequency in minutes")), E2E.LOCAL_MS)
        assertTrue("Custom check-minutes field did not appear in setup",
            visibleInScroll(customCheck, "alertDialogScroll"))
        E2E.setTextIn(customCheck!!, "7")
        E2E.ui.pressBack() // hide the numeric keyboard without dismissing setup
        E2E.shotRequired("alert-custom-repeat-and-check")

        assertTrue("Source/reliability details are not disclosed",
            revealTextContains("Source and reliability details", "alertDialogScroll"))
        assertTrue("Source/reliability details could not be expanded",
            tapTextContains("Source and reliability details"))
        assertTrue("Routine usable-data checks are not explicitly quiet",
            revealTextContains("Successful checks stay quiet", "alertDialogScroll"))
        assertTrue("Alert evidence is not limited to fresh NTES and direct WIMT reports",
            revealTextContains("Only fresh, route- and run-matched NTES or direct WIMT .in reports",
                "alertDialogScroll"))
        assertTrue("The outage notice is not limited to one per continuous failure streak",
            revealTextContains("one silent notice is sent per continuous failure streak",
                "alertDialogScroll"))
        E2E.shotRequired("alert-source-warning-copy")

        assertTrue("Set alert action is missing", tapTextContains("Set alert"))
        allowPermissionIfPrompted(firstPromptWaitMs = 8_000L)
        val context = E2E.context()
        var saved = false
        var positionUnavailable = false
        var stopAlreadyReached = false
        val resultDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
        while (System.currentTimeMillis() < resultDeadline &&
            !saved && !positionUnavailable && !stopAlreadyReached) {
            saved = E2E.ui.findObject(By.text("Alert on")) != null ||
                E2E.ui.findObject(By.text("Alert active")) != null
            positionUnavailable = E2E.ui.findObject(By.text("Current position not verified")) != null
            stopAlreadyReached = E2E.ui.findObject(By.text("Selected stop already reached")) != null
            if (!saved && !positionUnavailable && !stopAlreadyReached) Thread.sleep(100)
        }
        if (positionUnavailable || stopAlreadyReached) {
            val activeForTrain = JourneyAlarmStore.readAll(context).any {
                it.config.trainNo == "12627" && it.state in setOf(
                    AlertState.ARMED, AlertState.ALERTING, AlertState.RINGING)
            }
            assertTrue("The live-position gate showed a refusal but still armed an alert", !activeForTrain)
            assertTrue("The live-position gate did not explain that no alert was armed",
                E2E.ui.findObject(By.textContains("No alert was armed")) != null)
            if (E2E.flag("offline"))
                assertTrue("Offline setup did not fail closed when no live position source was available",
                    positionUnavailable)
            E2E.shotDiagnostic("alert-setup-position-refused")
            assertTrue("The live-position refusal could not be dismissed", tapTextContains("Close"))
            seedCiJourneyAlert(context)
            saved = E2E.ui.wait(Until.hasObject(By.text("Alert on")), E2E.LOCAL_MS)
        }
        if (!saved) E2E.shotDiagnostic("alert-setup-result-unknown")
        assertTrue("Set alert neither armed with qualified position evidence nor refused safely: " +
            "'${E2E.text("arrivalAlert", 300)}'", saved)
        assertTrue("Saved or fixture alert control is not visible on the journey",
            visibleResource("arrivalAlert"))
        E2E.shotRequired("alert-journey-active")

        // The master Alerts page is the single place to review status and timing for quiet checks.
        assertTrue("Saved alert actions could not be opened", E2E.tap("arrivalAlert"))
        if (E2E.ui.findObject(By.textContains("ACTIVE ALERTS")) == null)
            assertTrue("Central alert management is not reachable from the journey",
                tapTextContains("Manage alerts"))
        assertTrue("The central alert list did not open",
            E2E.ui.wait(Until.hasObject(By.textContains("ACTIVE ALERTS")), E2E.LOCAL_MS))
        assertTrue("The active alert does not identify its selected station",
            revealTextContains("At selected station", "alertsScroll"))
        assertTrue("The active alert did not retain the custom vibration frequency",
            revealTextContains("Vibrate · repeats every 15 sec", "alertsScroll"))
        assertTrue("The active alert did not retain the custom check interval",
            revealTextContains("Check frequency · every 7 minutes", "alertsScroll"))
        E2E.shotRequired("alert-active-management")

        assertTrue("Alerts has no default-options action", visibleResource("options"))
        assertTrue("Alert defaults could not be opened from Alerts", E2E.fingerTap("options"))
        assertTrue("The Alert defaults editor did not open",
            findTextContains("Save defaults", E2E.LOCAL_MS) != null)
        for (field in listOf("2 · Notify style", "3 · Frequency / duration", "4 · Check frequency"))
            assertTrue("Alert defaults are missing '$field'",
                revealTextContains(field, "alertDialogScroll"))
        assertTrue("Default Ping style cannot be brought into view",
            revealRadioContains("Ping", "alertDialogScroll", up = false))
        assertTrue("Default ping style could not be selected",
            tapRadioAndWait("Ping", listOf("Vibrate", "Ring · turn off manually"),
                "alertDialogScroll"))
        assertTrue("Default editor lost the ping/vibration interval control",
            revealTextContains("3 · Frequency / duration", "alertDialogScroll") &&
                E2E.ui.findObject(By.desc(repeatDescription)) != null)
        assertTrue("One-shot default is not selectable",
            selectSpinnerOption(repeatDescription, "Once"))
        assertTrue("Default editor lost the source-check interval control",
            revealTextContains("4 · Check frequency", "alertDialogScroll") &&
                E2E.ui.findObject(By.desc(checkDescription)) != null)
        assertTrue("Ten-minute default check interval is not selectable",
            selectSpinnerOption(checkDescription, "10 minutes"))
        E2E.shotRequired("alert-master-default-options")
        assertTrue("Default alert preferences have no save action", tapTextContains("Save defaults"))
        assertTrue("Saving defaults did not return to active Alerts",
            E2E.ui.wait(Until.hasObject(By.textContains("ACTIVE ALERTS")), E2E.LOCAL_MS))

        assertTrue("Modify alert is not visible for the armed journey alert",
            revealTextContains("Modify alert", "alertsScroll"))
        assertTrue("Modify alert could not be opened", tapTextContains("Modify alert"))
        assertTrue("The active alert editor did not identify its train",
            E2E.ui.wait(Until.hasObject(By.textContains("Modify alert · train 12627")),
                E2E.LOCAL_MS))
        assertTrue("The active alert editor is missing its station picker",
            revealTextContains("1 · Upcoming station", "alertDialogScroll") &&
                E2E.ui.findObject(By.desc("Upcoming station through the final station")) != null)
        assertTrue("The active alert editor is missing its frequency field",
            revealTextContains("3 · Frequency / duration", "alertDialogScroll") &&
                E2E.ui.findObject(By.desc(repeatDescription)) != null)
        assertTrue("The active alert editor is missing its check interval field",
            revealTextContains("4 · Check frequency", "alertDialogScroll") &&
                E2E.ui.findObject(By.desc(checkDescription)) != null)
        assertTrue("The active editor cannot return to the station field",
            revealTextContains("1 · Upcoming station", "alertDialogScroll", up = false))
        E2E.shotRequired("alert-active-edit-options")
        assertTrue("Cancel did not close the active alert editor", tapTextContains("Cancel"))
        assertTrue("Cancel did not return to the active Alerts list",
            E2E.ui.wait(Until.hasObject(By.textContains("ACTIVE ALERTS")), E2E.LOCAL_MS))

        // Reopen setup via Add another and prove the Alerts defaults feed the journey editor.
        assertTrue("Alerts back control is missing", E2E.tap("back"))
        assertTrue("Returning from Alerts lost the selected journey",
            E2E.ui.wait(Until.hasObject(E2E.res("arrivalAlert")), E2E.NETWORK_MS))
        assertTrue("Saved journey alert could not be reopened", E2E.tap("arrivalAlert"))
        val addAnotherButton = E2E.ui.wait(Until.findObject(By.text("Add another")), 300L)
            ?: E2E.ui.wait(Until.findObject(By.text("ADD ANOTHER")), E2E.LOCAL_MS)
        assertTrue("A second alert cannot be added for this journey", addAnotherButton != null)
        addAnotherButton!!.click()
        E2E.ui.waitForIdle()
        val secondSetupOpened = waitForAlertSetup()
        if (!secondSetupOpened) E2E.shotDiagnostic("alert-add-another-open-failure")
        assertTrue("Add another did not open the four-field editor; visible app text='${visibleTextNodes()}'",
            secondSetupOpened)
        assertTrue("Saved Ping default was not applied to a new journey alert",
            revealRadioContains("Ping", "alertDialogScroll", up = false) &&
                E2E.ui.findObject(By.clazz("android.widget.RadioButton").text("Ping"))?.isChecked == true)
        assertTrue("One-shot repeat selection is not visible in the new journey alert",
            revealTextContains("3 · Frequency / duration", "alertDialogScroll") &&
                E2E.ui.wait(Until.hasObject(By.text("Once")), E2E.LOCAL_MS))
        assertTrue("Ten-minute check selection is not visible in the new journey alert",
            revealTextContains("4 · Check frequency", "alertDialogScroll") &&
                E2E.ui.wait(Until.hasObject(By.text("10 minutes")), E2E.LOCAL_MS))
        E2E.shotRequired("alert-defaults-applied")
        assertTrue("Cancel did not close the new, unsaved alert", tapTextContains("Cancel"))
        assertEquals("Cancelling the new alert changed the existing one", "Alert on",
            E2E.text("arrivalAlert"))

        // Reopen Alerts and transition a local CI fixture into the fired state. This is not
        // reported train evidence; it verifies that the actual notification and in-app controls
        // let the traveller stop or snooze one alert without affecting others.
        assertTrue("The existing alert's management page could not be reopened", E2E.tap("arrivalAlert"))
        assertTrue("Manage alerts was not offered for the armed alert", tapTextContains("Manage alerts"))
        assertTrue("The alert did not return to the management page",
            E2E.ui.wait(Until.hasObject(By.textContains("ACTIVE ALERTS")), E2E.LOCAL_MS))
        val armed = JourneyAlarmStore.readAll(context).firstOrNull {
            it.config.trainNo == "12627" && it.state in setOf(
                AlertState.ARMED, AlertState.ALERTING, AlertState.RINGING)
        } ?: throw AssertionError("The saved journey alert was not in the local alert store")
        JourneyAlarmStore.update(context, armed.id, AlertState.ALERTING,
            "CI fixture · alert action controls", evidenceAtMillis = System.currentTimeMillis(),
            evidenceSourceLabel = "CI fixture", checkFailed = false)
        JourneyAlarmService.reconfigure(context)
        assertTrue("Fired in-app alert did not show Turn off",
            findTextContains("Turn off", E2E.LOCAL_MS) != null)
        val snoozeDescription = "Snooze 2 min · train ${armed.config.trainNo} · alert ${armed.id}"
        assertTrue("Fired in-app alert did not show its alert-scoped snooze action",
            revealDescription(snoozeDescription, "alertsScroll"))
        E2E.shotRequired("alert-in-app-action-controls")

        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: throw AssertionError("NotificationManager is unavailable")
        var postedNotification: android.app.Notification? = null
        val notificationDeadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < notificationDeadline && postedNotification == null) {
            postedNotification = runCatching {
                notificationManager.activeNotifications.firstOrNull { it.id == armed.id }?.notification
            }.getOrNull()
            if (postedNotification == null) Thread.sleep(100)
        }
        assertTrue("Fired alert notification was not posted", postedNotification != null)
        val notificationActions = postedNotification?.actions.orEmpty()
            .mapNotNull { it.title?.toString() }
        assertTrue("Notification shade is missing Turn off", "Turn off" in notificationActions)
        assertTrue("Notification shade is missing Snooze 2 min", "Snooze 2 min" in notificationActions)
        val snoozeAction = E2E.ui.wait(Until.findObject(By.desc(snoozeDescription)), E2E.LOCAL_MS)
        assertTrue("Alert-scoped snooze action is not visible inside the Alerts list",
            visibleInScroll(snoozeAction, "alertsScroll"))
        assertTrue("A physical tap on the visible alert-scoped Snooze control did not land",
            E2E.fingerTap(snoozeAction))
        E2E.ui.waitForIdle()
        val snoozed = JourneyAlarmStore.find(context, armed.id)
            ?: throw AssertionError("Snoozed alert disappeared from the store")
        val checkedAt = System.currentTimeMillis()
        val hasSnoozeDeadline = snoozed.snoozedUntilMillis > checkedAt &&
            snoozed.snoozedUntilMillis <= checkedAt + 121_000L
        if (!hasSnoozeDeadline) E2E.shotDiagnostic("alert-snooze-not-persisted")
        assertTrue("Snooze did not persist a two-minute resume time: state=${snoozed.state}, " +
            "deadline=${snoozed.snoozedUntilMillis}, now=$checkedAt",
            hasSnoozeDeadline)
        assertTrue("Snoozed alert card lost its Turn off control",
            revealTextContains("Turn off", "alertsScroll"))
        assertTrue("Snoozed alert card lost its alert-scoped Snooze 2 min control",
            revealDescription(snoozeDescription, "alertsScroll"))
        E2E.shotRequired("alert-snoozed-state")
        E2E.shotRequired("alert-active-action-controls")
        assertTrue("Turn off could not stop the active alert",
            tapTextContains("Turn off"))
        assertTrue("Stopped alert was not retained in past history",
            E2E.ui.wait(Until.hasObject(By.textContains("Stopped or cancelled")), E2E.LOCAL_MS))
        assertTrue("Stopped alert status is not visible in Alerts history",
            revealTextContains("Stopped or cancelled", "alertsScroll"))
        E2E.shotRequired("alert-cancelled-history")
        assertTrue("Alerts back control is missing after stopping the alert", E2E.tap("back"))
        assertTrue("Turn off did not return to the journey screen",
            E2E.ui.wait(Until.hasObject(E2E.res("arrivalAlert")), E2E.NETWORK_MS))
        assertEquals("Alert", E2E.text("arrivalAlert"))
        E2E.shotRequired("alert-returned-to-journey")
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
        E2E.shotRequired("traveller-invalid-trip")
    }

    @Test fun settingsRoundTripReturnsToUsableSearch() {
        assertTrue("Settings entry does not respond", E2E.tap("settings"))
        assertTrue("Settings opened no usable screen", E2E.visible("settingsScroll"))
        // Battery mode must be a separate opt-in, and turning it OFF must restore
        // the ordinary search in this same installed APK, without an uninstall.
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
        E2E.shotRequired("traveller-battery-settings")
        assertTrue("Battery toggle cannot be disabled", E2E.tap("batteryMode"))
        assertTrue("Battery mode did not switch off",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.LOCAL_MS) &&
                E2E.find("batteryMode")?.isChecked == false)
        E2E.ui.pressBack()
        assertTrue("Back from Settings did not return to search", E2E.visible("modeTrain"))
        assertTrue("Search cannot be used after Settings", E2E.tap("modeTrain") &&
            E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        E2E.shotRequired("traveller-settings-return")
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
        E2E.shotRequired("traveller-controls")
    }
}
