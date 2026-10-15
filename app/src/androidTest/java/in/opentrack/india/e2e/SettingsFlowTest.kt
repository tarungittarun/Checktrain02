package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data-source screen: the escape hatch that keeps this app alive when the public feed changes.
 *
 * Every control on it is load-bearing in an outage — a column map the user can type, an endpoint they can
 * repoint, a cache they can clear — so each is driven and its effect read back in the UI rather than in
 * SharedPreferences. A setting that persists but is not visible anywhere is a setting the user will not
 * find at 6 a.m. with a train to catch, and `junkEndpointSaysSo` is here for exactly that reason.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @Before
    fun up() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    private fun openSettings(): Boolean {
        if (!E2E.tap("settings")) return false
        // The state is populated by an asynchronous repository check; a mounted but empty
        // TextView is not yet an answer to the traveller.
        val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (System.currentTimeMillis() < deadline) {
            if (E2E.text("state").isNotBlank()) return true
            Thread.sleep(150)
        }
        return false
    }

    // @flow settings-entry
    // @flow settings-open
    @Test
    fun everyControlIsReachableAndBackReturns() {
        assertTrue("the Data source button does not open settings", openSettings())
        assertTrue("the install state is blank: '${E2E.text("state")}'",
            E2E.text("state").isNotBlank())
        for (
            id in listOf(
                "state", "overrideUrl", "columnHint", "save", "clear",
                "diagToggle", "diagShare", "diagSave", "diagClear", "diagState", "about",
            )
        ) {
            assertTrue("settings screen is missing or cannot scroll to '$id'",
                E2E.reveal(id, "settingsScroll"))
        }
        E2E.shot("settings")
        // The about text is below the fold on large-font phones: Back remains reachable
        // through Android navigation without pretending the top button is still visible.
        E2E.ui.pressBack()
        // On API 24 the first system Back can dismiss an IME that retained focus
        // while the settings scroller was moved. One more Back must leave Settings.
        if (!E2E.visible("settings", 750)) E2E.ui.pressBack()
        assertTrue("back did not return to search", E2E.visible("settings"))
    }

    /**
     * The diagnostic collector, driven end to end: off by default, visibly live once enabled, and emptied by
     * Clear. The reason this is a device flow rather than a unit test is that the whole feature exists to be
     * handed to a stranger in distress — if the state line never changes when the switch is flipped, the
     * feature is worthless no matter how correct the file writing is.
     */
    // @flow diag-log-collector
    @Test
    fun diagnosticCollectorIsInertThenLiveThenEmpty() {
        assertTrue(openSettings())
        assertTrue("diagnostic card cannot be reached by scrolling",
            E2E.reveal("diagState", "settingsScroll"))
        val idle = E2E.text("diagState")
        assertTrue(
            "the card does not explain how to start collecting: '" + idle + "'",
            idle.contains("reproduce", ignoreCase = true) || idle.contains("Turn on", ignoreCase = true),
        )
        if (!E2E.visible("diagToggle", 300)) E2E.swipeIn("settingsScroll", up = false)
        assertTrue("collection cannot be turned on", E2E.tap("diagToggle"))
        E2E.ui.wait(Until.hasObject(E2E.res("diagState")), E2E.LOCAL_MS)
        val live = E2E.text("diagState") + " " + E2E.text("diagToggle")
        assertTrue(
            "enabling collection left no visible trace: '" + live + "'",
            live.contains("bytes", ignoreCase = true) || live.contains("Turn off", ignoreCase = true),
        )
        // Clear must empty it while leaving collection on — a user mid-repro must not have to re-enable.
        assertTrue("clear is unreachable", E2E.tap("diagClear"))
        E2E.ui.wait(Until.hasObject(E2E.res("diagState")), E2E.LOCAL_MS)
        val cleared = E2E.text("diagState")
        assertTrue(
            "after Clear the card still claims content: '" + cleared + "'",
            cleared.contains("reproduce", ignoreCase = true) || cleared.isBlank(),
        )
        assertTrue("collection cannot be turned off", E2E.tap("diagToggle"))
        E2E.shot("settings-diary")
    }

    // @flow endpoint-save-reload
    @Test
    fun endpointPersistsAcrossReopen() {
        assertTrue(openSettings())
        val probe = "https://example.invalid/{trainNo}/{date}"
        assertTrue("the endpoint field cannot be typed into", typeInBoxField("overrideUrl", probe))
        assertTrue("Save did not run", E2E.tap("save"))
        assertTrue("settings will not reopen", openSettings())
        val seen = fieldText("overrideUrl")
        assertTrue("the saved endpoint did not come back ('$seen')", seen.contains("example.invalid"))
        // Restore the default so a later row in this run is not testing the test.
        typeInBoxField("overrideUrl", "")
        assertTrue("Save did not run", E2E.tap("save"))
    }

    // @flow column-hint
    @Test
    fun columnHintPersists() {
        assertTrue(openSettings())
        val hint = "1:code,2:name,3:sa,4:sd,5:aa,6:ad"
        assertTrue("the column map cannot be typed into", typeInBoxField("columnHint", hint))
        assertTrue("Save did not run", E2E.tap("save"))
        assertTrue(openSettings())
        assertEquals("the column map did not round-trip", hint, fieldText("columnHint"))
        typeInBoxField("columnHint", "")
        assertTrue("Save did not run", E2E.tap("save"))
    }

    // @flow endpoint-rejects-non-http
    @Test
    fun junkEndpointSaysSo() {
        assertTrue(openSettings())
        assertTrue(typeInBoxField("overrideUrl", "not-a-url"))
        assertTrue("save could not be tapped", E2E.tap("save"))
        // Invalid input must keep this screen open with visible feedback, not silently persist
        // a broken live source. The old test pressed Back before reading this screen's state.
        val state = E2E.text("state")
        assertTrue("invalid endpoint did not explain what to fix ('$state')",
            state.contains("http", ignoreCase = true) && state.contains("Nothing was saved"))
        assertTrue("an invalid endpoint closed Settings instead of letting it be corrected",
            E2E.visible("overrideUrl", 500))
        typeInBoxField("overrideUrl", "")
        E2E.tap("save")
    }

    // @flow clear-cache
    @Test
    fun clearingCacheIsVisibleOnTheTrackScreen() {
        assertTrue(openSettings())
        assertTrue("no clear control", E2E.tap("clear"))
        val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (!E2E.text("state").contains("cache cleared", true) && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
        }
        val state = E2E.text("state")
        assertTrue("cache clearing never updated the state line: '$state'",
            state.contains("cache cleared", true))
        E2E.tap("back")
        // Then check the *consequence* the user cares about: an offline screen after a clear must not present
        // anything as a recent live report.
        if (E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        ) {
            // Picking a train already runs the search. A second Find creates duplicate work.
            E2E.tapSuggestion("trainBox", 0)
            if (E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS)) {
                E2E.openTrackFromFirstResult()
                val provenance = E2E.text("provenance")
                val age = E2E.text("age")
                assertNotEquals(
                    "the cache was cleared but the screen still claims '$provenance' / '$age'",
                    "LIVE · cached", "$provenance · $age",
                )
            }
        }
        E2E.shot("after-clear")
    }

    // @flow manual-refresh-only-settings
    @Test
    fun settingsHasNoAutoRefreshChoice() {
        assertTrue(openSettings())
        assertTrue("Auto chooser was meant to be removed", E2E.find("refreshChoices", 1_000) == null)
        assertTrue("the source override must remain available", E2E.visible("overrideUrl"))
    }

    // Settings fields are plain EditTexts, not SuggestBoxes, so they need their own accessors.
    private fun fieldOf(id: String) = E2E.find(id, E2E.LOCAL_MS)

    private fun typeInBoxField(id: String, value: String): Boolean {
        val o = fieldOf(id) ?: return false
        // Through the shared writer, because the field is not empty on the second call (the saved endpoint
        // is restored to the default at the end of the row) and setText on a non-empty field throws.
        E2E.setTextIn(o, value)
        return true
    }

    private fun fieldText(id: String): String = fieldOf(id)?.text.orEmpty().trim()
}
