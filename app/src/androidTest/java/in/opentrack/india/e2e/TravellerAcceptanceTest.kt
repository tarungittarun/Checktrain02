package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
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
        assertTrue("Known train did not produce a tappable result; ${E2E.debugSearch("trainBox")}",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("Result is not the train requested: ${E2E.all(E2E.res("hitNo")).map { it.text }}",
            E2E.all(E2E.res("hitNo")).any { it.text.orEmpty().contains("12627") })
        assertTrue("Track on the result did not open a route", E2E.openTrackFromFirstResult())
        assertEquals("Track opened a DIFFERENT train from the ticket", "12627", E2E.text("trainNo"))
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
        assertTrue("A traveller cannot tell whether the position is measured or only a timetable: $badge / $heading",
            badge.isNotBlank() && heading.isNotBlank())
        assertTrue("Offline screen calls a position live: $badge / $heading",
            !E2E.flag("offline") || (!badge.contains("LIVE", true) &&
                !heading.contains("LIVE POSITION", true)))
        E2E.shot("traveller-route")
    }

    @Test fun manualRefreshKeepsTicketAndShowsAnAnswer() {
        openTicketTrain()
        val route = E2E.text("routeLabel")
        assertTrue("No refresh control a traveller can reach", E2E.reveal("refresh", "scroller") && E2E.tap("refresh"))
        val until = System.currentTimeMillis() + E2E.NETWORK_MS
        var receipt = ""
        while (System.currentTimeMillis() < until) {
            // A source response can return the scrollable card to its top. A person
            // scrolls to read the receipt; an offscreen accessibility node is blank.
            E2E.reveal("refreshFeedback", "scroller", swipes = 3)
            receipt = E2E.text("refreshFeedback", 250).trim()
            if (receipt.isNotBlank() && !receipt.contains("Checking", true) &&
                !receipt.contains("Refreshing", true) && !receipt.contains("Loading", true)) break
            Thread.sleep(250)
        }
        assertTrue("Refresh never gave a completed, readable receipt: '$receipt'", receipt.isNotBlank() &&
            !receipt.contains("Checking", true) && !receipt.contains("Refreshing", true) &&
            !receipt.contains("Loading", true))
        assertEquals("Refresh changed the ticket's train", "12627", E2E.text("trainNo"))
        assertEquals("Refresh erased or changed the route", route, E2E.text("routeLabel"))
        assertTrue("Refresh erased the station list", E2E.count(E2E.res("stopName")) > 0)
        E2E.shot("traveller-refresh")
    }

    @Test fun invalidOrImpossibleTripCannotMasqueradeAsAResult() {
        assertTrue("Board-at search is unavailable", E2E.typeInBox("fromBox", "yes"))
        assertTrue("Known station has no suggestion", E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not pick boarding station", E2E.tapSuggestion("fromBox"))
        // Selecting one end may compact the form; the traveller uses Edit search.
        if (E2E.visible("editSearch", 500)) assertTrue(E2E.tap("editSearch"))
        assertTrue("Get-off-at search is unavailable after Edit search", E2E.typeInBox("toBox", "yes"))
        assertTrue("Same station has no suggestion", E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not pick destination", E2E.tapSuggestion("toBox"))
        assertTrue("Same-station trip was not explicitly refused: '${E2E.text("statusLine")}'",
            listOf("same", "different").any { E2E.text("statusLine").contains(it, ignoreCase = true) })
        assertEquals("Impossible trip still displays train results", 0, E2E.resultRows())
        E2E.shot("traveller-invalid-trip")
    }

    @Test fun settingsRoundTripReturnsToUsableSearch() {
        assertTrue("Settings entry does not respond", E2E.tap("settings"))
        assertTrue("Settings opened no usable screen", E2E.visible("settingsScroll"))
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
