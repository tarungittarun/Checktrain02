package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of search: "which train is this, where is it on its run", entered by number or name.
 *
 * This mode is the one a traveller uses when they already have a ticket, so the flows here are about
 * finding the train and, more importantly, about what the app says when the train does not run on the
 * date asked for — the single most common way a timetable app looks like it is lying.
 */
@RunWith(AndroidJUnit4::class)
class TrainSearchFlowTest {

    @Before
    fun up() {
        E2E.launch()
        E2E.awaitSearchScreen()
        assertTrue("cannot switch to train search", E2E.tap("modeTrain"))
    }

    private fun awaitTrainSuggestion(): Boolean =
        E2E.ui.wait(Until.findObject(By.res(E2E.PKG, "sugPrimary")), E2E.LOCAL_MS) != null

    // @flow type-ahead-train
    @Test
    fun trainBoxSuggestsAndFills() {
        val offered = E2E.typeInBox("trainBox", "126") && awaitTrainSuggestion()
        assertTrue("typing a train number offered nothing: ${E2E.debugSearch("trainBox")}", offered)
        val rows = E2E.suggestionRows()
        assertTrue("the train panel showed no rows", rows.isNotEmpty())
        val chosen = rows[0].text.orEmpty()
        assertTrue("tapping a train row did nothing", E2E.tapSuggestion("trainBox", 0))
        // A successful pick starts the search and collapses its fields under Edit search.
        // Reveal them as a traveller would before asking UiAutomator for their visible text.
        if (E2E.visible("editSearch", 500)) assertTrue(E2E.tap("editSearch"))
        assertTrue(
            "the field does not read back what was picked ('$chosen' -> '${E2E.boxText("trainBox")}')",
            E2E.boxText("trainBox").isNotBlank(),
        )
        assertNotNull("the resolved train number never surfaced", E2E.boxChip("trainBox"))
        E2E.shot("train-suggest")
    }

    // @flow train-mode-search
    @Test
    fun trainModeFindsByNumber() {
        assertTrue(E2E.typeInBox("trainBox", "12627") && awaitTrainSuggestion())
        // Selecting a train starts the search and may immediately collapse Find.
        assertTrue("the train suggestion did not launch a search", E2E.tapSuggestion("trainBox", 0))
        val answered = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitNo")), E2E.NETWORK_MS) ||
            E2E.text("statusLine").isNotBlank() || E2E.resultRows() > 0
        assertTrue("a real train number produced neither a result nor a message", answered)
        E2E.shot("train-mode-results")
        E2E.assertNoSystemDialog("train mode")
    }

    // @flow wrong-date
    @Test
    fun noRunOnThatDateSaysSo() {
        // Shift to a date three days out and back: whatever the window answers, it must answer in words,
        // because "no trains" and "not in the offline data for that day" are different actions.
        E2E.tap("dayFwd")
        E2E.tap("dayFwd")
        assertTrue(E2E.typeInBox("trainBox", "12627") && awaitTrainSuggestion())
        assertTrue("the train row was not selected", E2E.tapSuggestion("trainBox", 0))
        val title = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "resultsTitle")), E2E.NETWORK_MS)
        assertTrue(
            "an empty date range was met with silence (results header present=$title)",
            title || E2E.text("statusLine").isNotBlank() || E2E.resultRows() > 0,
        )
        E2E.tap("dayNow")
    }

    // @flow bogus-train-number
    @Test
    fun nonexistentTrainNumberIsExplicit() {
        E2E.clearField("trainBox")
        val box = E2E.boxField("trainBox")
        assertNotNull("no train field in this mode", box)
        E2E.typeInBox("trainBox", "99999")
        // Below three characters the box is silent by design; at five digits either a list or a message.
        assertTrue("Find cannot be reached with the keyboard open",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val answered = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitNo")), E2E.LOCAL_MS) ||
            E2E.text("statusLine").isNotBlank()
        assertTrue("a number that exists nowhere got no reply at all", answered)
        E2E.shot("bogus-train")
    }
}
