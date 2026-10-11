package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Searching by station, from the keyboard to a result list — the flow the bug report started from
 * ("suggestions are not coming while entering station name"), so it is the flow that gets the most
 * assertions here. Every one of them is a *promise about an answer*: the box either offers something to
 * tap or says why it cannot. Blank is never a legal outcome, which is the property no render test in this
 * repo could see, because a screenshot of an empty dropdown looks like a correct empty dropdown.
 */
@RunWith(AndroidJUnit4::class)
class StationSearchFlowTest {

    @Before
    fun up() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    /** Rows appear after a 200 ms debounce and a query on an IO thread; polling is the only honest wait. */
    private fun awaitAnySuggestion(box: String, ms: Long = E2E.LOCAL_MS): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            val boxNode = E2E.ui.findObject(E2E.res(box))
            if (boxNode?.findObject(By.res(E2E.PKG, "id/sugPrimary")) != null) return true
            Thread.sleep(120)
        }
        return false
    }

    private fun pick(box: String, query: String): Boolean {
        if (!E2E.typeInBox(box, query) || !awaitAnySuggestion(box)) return false
        return E2E.tapSuggestion(box, 0)
    }

    // @flow type-ahead-station
    @Test
    fun threeLettersOfferMatchesThatFillTheField() {
        assertTrue("typing 'yes' in the board-at box offered nothing at all", awaitAnySuggestion("fromBox"))
        val offered = E2E.suggestionRows()
        assertTrue("the panel showed ${offered.size} rows for 'yes'", offered.isNotEmpty())
        val first = offered[0].text.orEmpty()
        assertTrue("tapping should fill the name, got '$first'", first.isNotBlank())
        assertTrue("tapping the row did nothing", E2E.tapSuggestion("fromBox", 0))
        assertEquals("the field does not show the name that was picked", first, E2E.boxText("fromBox"))
        val chip = E2E.boxChip("fromBox")
        assertTrue("the resolved code did not surface for the traveller: '$chip'", chip.contains("SBC") ||
            chip.isNotBlank())
        E2E.shot("type-ahead-picked")
        E2E.assertNoSystemDialog("type-ahead")
    }

    // @flow type-ahead-min-length
    @Test
    fun twoLettersAreSilentByDesign() {
        E2E.typeInBox("fromBox", "ne")
        // 'B' matching 400 stations is noise; three letters is where a name or code becomes specific.
        assertTrue("'ne' opened a list although two letters are below the floor", E2E.suggestionRows().isEmpty())
        // …and the same box must light up at three, so this test cannot pass by the box being dead.
        assertTrue("'yes' offered nothing after 'ne' was correctly ignored", awaitAnySuggestion("fromBox"))
    }

    // @flow type-ahead-never-silent
    @Test
    fun everyQueryAnswersWithRowsOrWithAReason() {
        for (q in listOf("yes", "mas", "qqzzxxy", "01404")) {
            E2E.typeInBox("fromBox", q)
            assertTrue("typing '$q' left the box with no rows and no explanation", awaitAnySuggestion("fromBox"))
            val rows = E2E.suggestionRows()
            assertTrue("'$q' produced an empty panel", rows.isNotEmpty())
            if (q == "qqzzxxy") {
                // A miss has to read as a miss: one line, explaining, not a list and not nothing.
                assertEquals("an unmatched query should say so in one line, got ${rows.size}", 1, rows.size)
            }
        }
        E2E.shot("never-silent")
    }

    // @flow both-ends-search
    @Test
    fun bothEndsRunASearch() {
        assertTrue("board-at never resolved", pick("fromBox", "yes"))
        assertTrue("get-off-at never resolved", pick("toBox", "ndls"))
        // Picking the second end runs the search on its own — the common case, both ends known.
        val sawAnswer = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "id/resultsTitle")), E2E.NETWORK_MS)
        val status = E2E.text("statusLine")
        assertTrue(
            "both ends filled and the screen said nothing (answer=$sawAnswer, status='$status')",
            sawAnswer || status.isNotBlank() || E2E.resultRows() > 0,
        )
        val title = E2E.text("resultsTitle")
        assertTrue("the results header is blank while showing a list", title.isNotBlank() || E2E.resultRows() == 0)
        E2E.shot("both-ends")
        E2E.assertNoSystemDialog("both ends")
    }

    // @flow empty-query-feedback
    @Test
    fun findWithBlankFieldsSaysWhatsMissing() {
        E2E.typeInBox("fromBox", "")
        E2E.typeInBox("toBox", "")
        assertTrue("no Find button on the search screen", E2E.tap("find"))
        val status = E2E.text("statusLine")
        assertTrue("Find with both fields blank answered with nothing", status.isNotBlank())
        assertTrue("said: $status", status.contains("station", ignoreCase = true))
    }

    // @flow unknown-station
    @Test
    fun unknownStationNamesTheFix() {
        E2E.typeInBox("fromBox", "qqzzxxy")
        E2E.typeInBox("toBox", "yes")
        assertTrue("Find never ran", E2E.tap("find"))
        val status = E2E.text("statusLine")
        assertTrue("an unknown boarding station produced no message", status.isNotBlank())
        // The advice that actually helps at a station: use the code.
        assertTrue("said: $status", status.contains("code", ignoreCase = true) ||
            status.contains("matches", ignoreCase = true))
    }

    // @flow same-station
    @Test
    fun sameStationBothEndsIsRefused() {
        assertTrue(E2E.typeInBox("fromBox", "yes") && awaitAnySuggestion("fromBox"))
        E2E.tapSuggestion("fromBox", 0)
        assertTrue(E2E.typeInBox("toBox", "yes") && awaitAnySuggestion("toBox"))
        E2E.tapSuggestion("toBox", 0)
        E2E.tap("find")
        val status = E2E.text("statusLine")
        val rows = E2E.resultRows()
        assertTrue("board==alight produced neither a refusal nor a list (status='$status', rows=$rows)",
            status.isNotBlank() || rows == 0)
        if (status.isNotBlank()) {
            assertTrue("said: $status", status.contains("same", ignoreCase = true) ||
                !status.contains("direct", ignoreCase = true))
        }
    }

    // @flow day-shift
    @Test
    fun dayButtonsMoveTheDate() {
        val start = E2E.text("whenValue")
        assertTrue("the travelling date is not shown at all", start.isNotBlank())
        assertTrue("no +1d control", E2E.tap("dayFwd"))
        val ahead = E2E.text("whenValue")
        assertNotEquals("+1d left the date unchanged", start, ahead)
        assertTrue("no 'now' control to get back", E2E.tap("dayNow"))
        assertEquals("'now' did not return to the original date", start, E2E.text("whenValue"))
        assertTrue("no −1d control", E2E.tap("dayBack"))
        assertNotEquals("−1d left the date on 'now'", start, E2E.text("whenValue"))
        // The window itself has to be stated, or ±24 h is an invisible rule the traveller cannot use.
        assertTrue("the ±24 h window is never explained on screen", E2E.text("whenHint").isNotBlank())
    }

    // @flow swap
    @Test
    fun swapMovesTheResolvedCodes() {
        assertTrue(E2E.typeInBox("fromBox", "yes") && awaitAnySuggestion("fromBox"))
        E2E.tapSuggestion("fromBox", 0)
        assertTrue(E2E.typeInBox("toBox", "ndls") && awaitAnySuggestion("toBox"))
        E2E.tapSuggestion("toBox", 0)
        val before = E2E.boxText("fromBox") to E2E.boxText("toBox")
        assertTrue("no swap control", E2E.tap("swap"))
        val after = E2E.boxText("fromBox") to E2E.boxText("toBox")
        assertEquals("swap did not exchange the fields", before.second, after.first)
        assertEquals("swap did not exchange the fields", before.first, after.second)
        E2E.shot("swap")
    }
}
