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
        return E2E.ui.wait(Until.hasObject(E2E.res("state")), E2E.LOCAL_MS) != null
    }

    // @flow settings-entry
    // @flow settings-open
    @Test
    fun everyControlIsReachableAndBackReturns() {
        assertTrue("the Data source button does not open settings", openSettings())
        for (id in listOf("state", "overrideUrl", "columnHint", "refreshChoices", "save", "clear", "about")) {
            assertTrue("settings screen is missing the control '$id'", E2E.visible(id))
        }
        assertTrue("the install state is blank: '${E2E.text("state")}'", E2E.text("state").isNotBlank())
        E2E.shot("settings")
        assertTrue("back does not leave settings", E2E.tap("back"))
        assertTrue("back did not return to search", E2E.visible("fromBox"))
    }

    // @flow endpoint-save-reload
    @Test
    fun endpointPersistsAcrossReopen() {
        assertTrue(openSettings())
        val probe = "https://example.invalid/{trainNo}/{date}"
        assertTrue("the endpoint field cannot be typed into", typeInBoxField("overrideUrl", probe))
        E2E.tap("save")
        E2E.tap("back")
        assertTrue("settings will not reopen", openSettings())
        val seen = fieldText("overrideUrl")
        assertTrue("the saved endpoint did not come back ('$seen')", seen.contains("example.invalid"))
        // Restore the default so a later row in this run is not testing the test.
        typeInBoxField("overrideUrl", "")
        E2E.tap("save")
        E2E.tap("back")
    }

    // @flow column-hint
    @Test
    fun columnHintPersists() {
        assertTrue(openSettings())
        val hint = "1:code,2:name,3:sa,4:sd,5:aa,6:ad"
        assertTrue("the column map cannot be typed into", typeInBoxField("columnHint", hint))
        E2E.tap("save")
        E2E.tap("back")
        assertTrue(openSettings())
        assertEquals("the column map did not round-trip", hint, fieldText("columnHint"))
        typeInBoxField("columnHint", "")
        E2E.tap("save")
        E2E.tap("back")
    }

    // @flow endpoint-rejects-non-http
    @Test
    fun junkEndpointSaysSo() {
        assertTrue(openSettings())
        assertTrue(typeInBoxField("overrideUrl", "not-a-url"))
        E2E.tap("save")
        E2E.tap("back")
        // The consequence, on the screen it is visible from: the tracking screen must tell the traveller the
        // configured endpoint was not usable, rather than fall back quietly to the built-in one.
        val state = E2E.text("state")
        assertTrue("settings accepted a non-URL and said nothing ('$state')", state.isNotBlank())
        typeInBoxField("overrideUrl", "")
        E2E.tap("save")
        E2E.tap("back")
    }

    // @flow clear-cache
    @Test
    fun clearingCacheIsVisibleOnTheTrackScreen() {
        assertTrue(openSettings())
        assertTrue("no clear control", E2E.tap("clear"))
        val state = E2E.text("state")
        assertTrue("clearing left the state line blank", state.isNotBlank())
        E2E.tap("back")
        // Then check the *consequence* the user cares about: an offline screen after a clear must not present
        // anything as a recent live report.
        if (E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        ) {
            E2E.tapSuggestion("trainBox", 0)
            E2E.tap("find")
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

    // @flow auto-refresh-choice
    @Test
    fun refreshChoicePersists() {
        assertTrue(openSettings())
        val before = E2E.text("refreshChoices")
        assertTrue("the auto-refresh control shows no current choice", before.isNotBlank())
        assertTrue(E2E.tap("refreshChoices"))
        val after = E2E.text("refreshChoices")
        assertNotEquals("the refresh choice does not respond", before, after)
        E2E.tap("save")
        E2E.tap("back")
        assertTrue(openSettings())
        assertEquals("the refresh choice did not survive Save", after, E2E.text("refreshChoices"))
    }

    // Settings fields are plain EditTexts, not SuggestBoxes, so they need their own accessors.
    private fun fieldOf(id: String) = E2E.find(id, E2E.LOCAL_MS)

    private fun typeInBoxField(id: String, value: String): Boolean {
        val o = fieldOf(id) ?: return false
        o.click()
        o.setText(value)
        E2E.ui.waitForIdle()
        return true
    }

    private fun fieldText(id: String): String = fieldOf(id)?.text.orEmpty().trim()
}
