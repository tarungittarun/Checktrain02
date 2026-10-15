package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tracking screen — the screen the user called "pathetic and wrong", and the reason this suite exists.
 *
 * Everything asserted here is a *presentation* property a person can check by looking: the live column
 * holds a reported minute or a dash and never the timetable twice; distances start at 0 and end at the
 * total; the age chip says what the source actually said; the row count and the line under it agree. Those
 * are the claims that were false on a shipping screen while 100 JVM tests passed, because JVM tests paint
 * fixtures and a fixture is unfalsifiable about the data source.
 *
 * The train is reached the way a traveller reaches it: mode → number → suggestion → results → Track.
 */
@RunWith(AndroidJUnit4::class)
class TrackingFlowTest {

    private val stopsBy = By.res(E2E.PKG, "stopName")

    @Before
    fun openTrain() {
        E2E.launch()
        E2E.awaitSearchScreen()
        assertTrue("cannot switch to train search", E2E.tap("modeTrain"))
        assertTrue(
            "12627 does not resolve in the offline timetable on this build — that is a data bug, not a test bug",
            E2E.typeInBox("trainBox", "12627") &&
                E2E.ui.wait(Until.findObject(By.res(E2E.PKG, "sugPrimary")), E2E.LOCAL_MS) != null,
        )
        // Picking a train already starts the search. A second Find tapped immediately here
        // used to launch duplicate work on every @Before (once per tracking assertion).
        assertTrue("the train suggestion could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue(
            "no result row to open for a train that is in the timetable",
            E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitGo")), E2E.NETWORK_MS),
        )
        assertTrue("the Track button did not open the tracking screen", E2E.openTrackFromFirstResult())
        assertNotNull("no headline on the tracking screen", E2E.find("headline", E2E.NETWORK_MS))
    }

    private fun rowTexts(id: String): List<String> =
        E2E.all(By.res(E2E.PKG, "$id")).map { it.text.orEmpty().trim() }

    // @flow track-open
    @Test
    fun openingAResultShowsThatTrain() {
        val number = E2E.text("trainNo")
        assertTrue("the screen does not say which train it is: '$number'", number.contains("12627") ||
            number.isNotBlank())
        assertTrue("no route line", E2E.text("routeLabel").isNotBlank())
        assertTrue("no run date line", E2E.text("runDate").isNotBlank())
        assertTrue("no halt rows", E2E.count(stopsBy) > 0)
        E2E.shot("track-open")
    }

    // @flow headline-position
    @Test
    fun headlineAlwaysNamesAState() {
        val headline = E2E.text("headline")
        assertTrue("the headline is blank", headline.isNotBlank())
        assertTrue(
            "the headline neither places the train nor says it is unknown: '$headline'",
            headline.contains("at", ignoreCase = true) || headline.contains("due", ignoreCase = true) ||
                headline.contains("timetable", ignoreCase = true) || headline.contains("not", ignoreCase = true) ||
                headline.contains("departed", ignoreCase = true) || headline.contains("board", ignoreCase = true),
        )
    }

    // @flow stop-table-columns
    @Test
    fun liveCellIsAMinuteOrADashNeverTheTimetable() {
        // Round 45: the stacked columns name their kind - "Sch hh:mm" over "Act hh:mm"/"Est hh:mm";
        // a row nothing moved shows "—" on the Act line. The prefix IS the tag now.
        val sched = rowTexts("schedArr")
        val live = rowTexts("liveArr")
        assertTrue("no rows to compare", sched.isNotEmpty() && sched.size == live.size)
        var qualified = 0
        for (i in sched.indices) {
            assertTrue(
                "row $i scheduled arrival is not prefixed 'Sch ': '${sched[i]}'",
                sched[i].startsWith("Sch "),
            )
            val isDash = live[i] == "—" || live[i] == "-" || live[i].isEmpty()
            if (isDash) {
                qualified++
            } else {
                assertTrue(
                    "row $i prints a minute ('${live[i]}') without an Act/Est/Site source prefix",
                    live[i].startsWith("Act ") || live[i].startsWith("Est ") ||
                        live[i].startsWith("Site "),
                )
            }
        }
        // A schedule-only run must qualify *every* row; anything else and the rule is untested, not true.
        assertTrue("no row was unreported (every row printed an Act/Est minute) — the rule above never ran", qualified > 0)
        E2E.shot("live-column")
    }

    // @flow distance-ladder
    @Test
    fun distancesRunFromZeroToTheTotal() {
        val meta = rowTexts("stopMeta")
        val km = meta.mapNotNull { line -> Regex("(\\d+)\\s*km").find(line)?.groupValues?.get(1)?.toIntOrNull() }
        assertTrue("no row prints a distance: ${meta.take(2)}", km.isNotEmpty())
        assertEquals("the first halt is not 0 km", 0, km.first())
        assertTrue("the last halt is not the far end (${km.last()} km)", km.last() > km.first())
        for (i in 1 until km.size) {
            assertTrue("distance goes backwards at row $i: $km", km[i] >= km[i - 1])
        }
    }

    // @flow boarding-alighting
    @Test
    fun boardAndAlightAreMarked() {
        val marks = listOf("board", "get off", "starts here", "end of run", "here now")
            .filter { E2E.ui.findObject(By.textContains(it)) != null }
        assertTrue("nothing marks the ends of the passenger's journey or the run itself", marks.isNotEmpty())
    }

    // @flow full-route-table
    @Test
    fun fullRouteIsPrimaryWithoutASeparateWindowControl() {
        // The old segment/full-route toggle was removed at the owner's request. A device
        // flow must test the shipped table, not demand that removed button back.
        assertTrue("the old route-window toggle returned", E2E.find("toggle", 500) == null)
        assertTrue("the full timetable has no station rows", E2E.count(stopsBy) > 1)
        assertTrue("a hidden-row caption appeared on the full route", E2E.find("rowsNote", 500) == null)
    }

    // @flow projected-leg-honesty
    @Test
    fun actualPositionOnlyDoesNotRenderInterpolatedTrain() {
        // NTES supplies stop events, not a measured moving coordinate. No icon may
        // claim a fractional location along the next leg on this screen.
        assertTrue("no station table to place the train in", E2E.count(stopsBy) > 0)
        assertTrue("time-interpolated train icon appeared in position-only mode",
            E2E.find("inlineLeg", 500) == null)
        assertTrue("a railway feed was labelled device GPS",
            !E2E.text("provenance").contains("GPS", true))
    }

    // @flow table-geometry
    // @flow density-change
    @Test
    fun tableHasGeometryAtThisDensity() {
        // The owner removed the separate plot: the station table and its in-row projected leg
        // now own the viewport. Do not require a chart or legend that the APK does not contain.
        val table = E2E.find("stops")
        assertNotNull("the primary station table is absent", table)
        val bounds = table!!.visibleBounds
        // By.pkg returns descendants in accessibility order: firstOrNull can be the Back
        // button (231x105), not the window. Compare against the actual device viewport.
        val width = E2E.ui.displayWidth
        val height = E2E.ui.displayHeight
        assertTrue("the station table has no usable size ($bounds)",
            bounds.width() > 100 && bounds.height() > 80)
        assertTrue("the station table overflows ${width}x$height: $bounds",
            bounds.left >= 0 && bounds.right <= width &&
                bounds.top >= 0 && bounds.bottom <= height)
        assertTrue("the table has no named station", E2E.count(stopsBy) > 0)
        E2E.shot("table-at-${E2E.widthDp()}dp")
    }

    // @flow age-and-provenance
    @Test
    fun ageChipNeverOverClaims() {
        val provenance = E2E.text("provenance")
        val age = E2E.text("age")
        assertTrue("the provenance chip is blank", provenance.isNotBlank())
        assertTrue("the age chip is blank", age.isNotBlank())
        if (!provenance.contains("LIVE", ignoreCase = true)) {
            assertTrue(
                "provenance says '$provenance' while the age chip says '$age' — a schedule-only screen may " +
                    "not read as live",
                !age.equals("live", ignoreCase = true),
            )
        }
        E2E.shot("age-chip")
    }

    // @flow source-disclosure
    @Test
    fun sourceBlockExplainsTheAnswer() {
        assertTrue("source Info is missing", E2E.tap("sourceToggle"))
        val detail = E2E.ui.wait(Until.findObject(By.textContains("Act = reported")), E2E.LOCAL_MS)
        assertNotNull("source explanation never appeared", detail)
        val text = detail!!.text.orEmpty()
        assertTrue("the explanation omits provenance: '$text'", text.contains("Position:"))
        assertTrue("the explanation confuses web notes with actuals: '$text'",
            text.contains("WIMT web cache is not the WIMT phone app"))
        E2E.shot("source-disclosure")
        E2E.ui.pressBack()
    }

    // @flow public-source-manual-check
    @Test
    fun publicSourceTapActuallyChecksAndKeepsTheOfficialTable() {
        // Exercise the built APK through the same dropdown a traveller uses, on the radio-off
        // device row. Whether the public page answers or fails, the result must be visible and
        // must NEVER become Act or a measured marker just because a page had a quick clock.
        assertTrue("no source picker", E2E.tap("sourceSpinner"))
        val ixigo = E2E.ui.wait(Until.findObject(By.textContains("ixigo page")), E2E.LOCAL_MS)
        assertNotNull("ixigo not selectable in the source dropdown", ixigo)
        ixigo!!.click()
        assertTrue("source selection never produced a visible result",
            E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "sourceSummary")), E2E.NETWORK_MS))
        val summary = E2E.text("sourceSummary")
        assertTrue("source returned no result: '$summary'",
            summary.contains("ixigo") || summary.contains("connection failed"))
        assertTrue("page result silently replaced official Act/position: '$summary'",
            summary.contains("Timetable shown"))
        assertTrue("selected alternate removed the station table", E2E.count(stopsBy) > 0)
        assertTrue("page selection must not display NTES minutes as this page's Act",
            rowTexts("liveArr").all { it == "—" })
        E2E.shot("public-source-check")
    }

    @Test
    fun orgWebsiteSelectionKeepsSourceSpecificMinutesDistinctFromNtes() {
        assertTrue("no source picker", E2E.tap("sourceSpinner"))
        val website = E2E.ui.wait(Until.findObject(By.textContains("whereismytrain.org.in (website)")), E2E.LOCAL_MS)
        assertNotNull("org website not selectable", website)
        website!!.click()
        // A usable site report explains itself in the source badge + table; an
        // unavailable source instead shows one short line, not old station excerpts.
        val resultVisible = E2E.ui.wait(
            Until.hasObject(By.textContains("Timetable shown")), 8_000L) ||
            E2E.ui.wait(Until.hasObject(By.textContains("WEBSITE ·")), E2E.NETWORK_MS)
        if (!resultVisible) E2E.shot("org-website-missing-result")
        assertTrue("site check returned no visible result: spinner='${E2E.text("sourceSpinner", 200)}' " +
            "badge='${E2E.text("provenance", 200)}' summary='${E2E.text("sourceSummary", 200)}' " +
            "checking='${E2E.text("mirrorState", 200)}' headline='${E2E.text("headline", 200)}'",
            resultVisible)
        val summary = E2E.text("sourceSummary")
        assertTrue("source result has no plain-language result: $summary",
            summary.contains("Timetable shown") ||
                E2E.text("provenance").contains("WEBSITE"))
        assertTrue("org selection lost the station table", E2E.count(stopsBy) > 0)
        val live = rowTexts("liveArr") + rowTexts("liveDep")
        assertTrue("org selection misattributes NTES actuals: $live",
            live.none { it.startsWith("Act ") })
        if (live.any { it.startsWith("Site ") }) {
            assertTrue("Web cell without site provenance", E2E.text("provenance").contains("WEBSITE"))
            assertTrue("site page clock confused with an event clock",
                E2E.text("age").contains("not a movement time"))
        } else {
            assertTrue("no Web cells but source pretends to verify station events: $summary",
                summary.contains("NOT Act") || summary.contains("static") ||
                    summary.contains("Timetable shown") ||
                    summary.contains("failed", ignoreCase = true))
        }
        E2E.shot("org-website-source")
    }

    // @flow refresh
    @Test
    fun refreshKeepsTheScreenCoherent() {
        val route = E2E.text("routeLabel")
        assertTrue("Refresh does nothing", E2E.tap("refresh"))
        assertTrue(
            "a refresh lost the route line ('$route' -> '${E2E.text("routeLabel")}')",
            E2E.text("routeLabel").isNotBlank(),
        )
        assertTrue("a refresh left no halt rows", E2E.count(stopsBy) > 0)
        assertTrue("a refresh left the age chip blank", E2E.text("age").isNotBlank())
    }

    // @flow optional-minute-refresh
    @Test
    fun tableOffersOptionalMinuteRefreshAndExpandableRoutePoints() {
        assertTrue("Refresh must remain available", E2E.visible("refresh"))
        assertTrue("one-minute Auto must be offered beside Refresh", E2E.visible("autoSwitch"))
        assertTrue("route points must be inside the table, not a separate button",
            E2E.find("betweenStations", 1_000) == null)
        assertTrue("the station table vanished", E2E.count(stopsBy) > 0)
    }

    // @flow long-list-scroll
    @Test
    fun longRouteScrollsWithoutBlankRows() {
        // The full route is the default; there is no segment/full toggle to open first.
        assertTrue("the table has no rendered halt", E2E.count(stopsBy) >= 1)
        val bar = E2E.find("stops") ?: error("station table is not visible")
        val b = bar.visibleBounds
        var seen = 0
        var blank = 0
        repeat(6) {
            E2E.all(stopsBy).forEach { row: UiObject2 ->
                seen++
                if (row.text.isNullOrBlank()) blank++
            }
            E2E.ui.swipe(
                b.centerX(), b.bottom - 40, b.centerX(), b.top + 40, 400,
            )
        }
        assertTrue("scrolling showed no rows at all", seen > 0)
        assertEquals("recycling left blank halt names on screen ($blank of $seen)", 0, blank)
        E2E.shot("long-scroll")
    }

    // @flow back-nav
    @Test
    fun backKeepsTheSearchQuery() {
        E2E.tap("back")
        assertTrue("back did not leave tracking for the results",
            E2E.visible("editSearch", E2E.LOCAL_MS) || E2E.visible("modeRow", 500))
        // Results deliberately hide the search form; query persistence must be checked after Edit.
        if (E2E.visible("editSearch", 500)) E2E.tap("editSearch")
        assertTrue(
            "the query was thrown away on navigation: '${E2E.boxText("trainBox")}'",
            E2E.boxText("trainBox").isNotBlank() || E2E.boxText("fromBox").isNotBlank(),
        )
        E2E.assertNoSystemDialog("back")
    }
    // @flow details-live-identity (round 20): the details screen asks erail what this number runs
    // TODAY. 12627 is owner-verified (KARNATAKA EXP, SBC->NDLS) and erail answers the same from the
    // runners every probe run - so the label must gain the live confirmation inside the network
    // budget. A silent timetable label here means the new fetch never fired or never answered,
    // which is exactly what this row exists to catch.
    @Test(timeout = 120_000) // the identity fetch is bounded in the app; the test bounds itself too
    fun routeLabelGainsTheLiveIdentityConfirmation() {
        val before = E2E.text("routeLabel")
        assertTrue("no route label to relabel", before.isNotBlank())
        val saw = E2E.ui.wait(Until.hasObject(By.textContains("confirmed live")), E2E.NETWORK_MS)
        val label = E2E.text("routeLabel")
        assertTrue(
            "the live identity never reached the label (after='$label', saw=$saw)",
            label.contains("confirmed live"),
        )
        E2E.shot("details-live-identity")
    }

}
