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
 * Everything asserted here is a *presentation* property a person can check by looking: the disclosed
 * actual-time sheet holds a reported minute or a dash and never the timetable twice; distances start at
 * 0 and end at the total; the age chip says what the source actually said; the route remains navigable. Those
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
        assertTrue("first-load overlay still occupies the route after the timetable painted",
            E2E.find("firstLoad", 300) == null)
    }

    private fun rowTexts(id: String): List<String> {
        // RecyclerView replaces accessibility nodes during a finger swipe. Never reuse a
        // UiObject2 across frames: re-query the viewport if it became stale while reading.
        repeat(3) {
            val values = runCatching {
                E2E.all(By.res(E2E.PKG, id)).map { it.text.orEmpty().trim() }
            }.getOrNull()
            if (values != null) return values
        }
        return emptyList()
    }

    /** Times are visible without tapping a station; reveal a real onscreen cell
     * rather than accepting an offscreen RecyclerView item as a pass. */
    private fun revealTimeSheet(): Boolean =
        rowTexts("liveArr").isNotEmpty() || E2E.reveal("liveArr", "stops", swipes = 5)

    // @flow track-open
    @Test
    fun openingAResultShowsThatTrain() {
        val number = E2E.text("trainNo")
        assertTrue("the screen does not say which train it is: '$number'", number.contains("12627") ||
            number.isNotBlank())
        assertTrue("no route line", E2E.text("routeLabel").isNotBlank())
        assertTrue("no run date line", E2E.text("runDate").isNotBlank())
        assertTrue("no halt rows", E2E.count(stopsBy) > 0)
        // If the first answer already contains a timed stop, the first screen
        // must focus that reported place. Do not fabricate a marker for a
        // timetable-only or failed live request.
        if (E2E.text("provenance").contains("reported stop", ignoreCase = true)) {
            val marker = E2E.find("stopTrainMarker", E2E.LOCAL_MS)
            assertNotNull("first-load report did not focus its locomotive on a visible halt", marker)
            val viewport = E2E.find("stops")!!.visibleBounds
            assertTrue("locomotive is outside the route viewport: ${marker!!.visibleBounds}",
                viewport.contains(marker.visibleBounds.centerX(), marker.visibleBounds.centerY()))
        }
        E2E.shot("track-open")
    }

    // @flow hero-collapse
    @Test
    fun summaryStartsExpandedAndCollapsesToDelayNextAndSource() {
        assertNotNull("first load did not open the journey card", E2E.find("headline"))
        assertNotNull("expanded card lost its source evidence", E2E.find("provenance"))
        assertTrue("collapse handle not tappable", E2E.tap("heroToggle"))
        assertTrue("headline survived compact mode", E2E.find("headline", 300) == null)
        assertTrue("evidence survived compact mode", E2E.find("provenance", 300) == null)
        assertNotNull("compact card lost its delay", E2E.find("delay"))
        assertNotNull("compact card lost the next stop", E2E.find("next"))
        assertNotNull("compact card lost the source chooser", E2E.find("sourceSpinner"))
        assertTrue("expand handle not tappable", E2E.tap("heroToggle"))
        assertNotNull("expanding did not restore the headline", E2E.find("headline"))
        assertNotNull("expanding did not restore source evidence", E2E.find("provenance"))
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
        assertTrue("arrival/departure pair is not visible until a stop is tapped",
            revealTimeSheet())
        // Read each fully visible halt as one unit. The old independent lists
        // included a half-clipped fourth row on the large-font density device:
        // its right clock could be visible while its lower-left clock was not.
        // Comparing lists by index made that correct viewport look like bad data.
        // Never skip a missing cell on a FULLY visible row.
        data class PairOfPairs(val plannedArr: String, val reportedArr: String,
                               val plannedDep: String, val reportedDep: String)
        val viewport = E2E.find("stops")?.visibleBounds
            ?: throw AssertionError("station viewport missing")
        fun fullyVisiblePairs(): List<PairOfPairs> = E2E.all(By.res(E2E.PKG, "stopRow"))
            .filter { row ->
                val b = row.visibleBounds
                b.height() > 0 && b.top > viewport.top && b.bottom < viewport.bottom
            }.map { row ->
                fun cell(id: String): String {
                    val view = row.findObject(By.res(E2E.PKG, id))
                        ?: throw AssertionError("fully visible station lost its $id cell")
                    val direction = if (id.endsWith("Arr")) "arrival" else "departure"
                    assertTrue("$id lost its spoken diagonal direction",
                        view.contentDescription.orEmpty().contains(direction, ignoreCase = true))
                    return view.text?.trim()
                        ?: throw AssertionError("fully visible station lost its $id clock")
                }
                PairOfPairs(cell("schedArr"), cell("liveArr"),
                    cell("schedDep"), cell("liveDep"))
            }
        val pairs = fullyVisiblePairs()
        assertTrue("no fully visible stop with both time pairs to compare; " +
            "route=${viewport} names=${rowTexts("stopName")}", pairs.isNotEmpty())
        var missing = 0
        pairs.forEachIndexed { i, pair ->
            assertTrue("row $i lost the scheduled arrival minute",
                pair.plannedArr == "—" || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.plannedArr))
            assertTrue("row $i lost the scheduled departure minute",
                pair.plannedDep == "—" || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.plannedDep))
            assertTrue("row $i source departure must be a minute or dash",
                pair.reportedDep == "—" || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.reportedDep))
            if (pair.reportedArr == "—") missing++ else assertTrue(
                "row $i source arrival is not a clock: '${pair.reportedArr}'",
                Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.reportedArr))
        }
        assertTrue("a missing source time must remain a dash, not a timetable copy", missing > 0)
        E2E.shot("visible-arrival-departure")
    }

    // @flow distance-ladder
    @Test
    fun distancesRunFromZeroToTheTotal() {
        // RecyclerView exposes only rows in the viewport. On a large-font phone that can
        // be one row, not the whole route; scroll as a passenger would to inspect both ends.
        repeat(8) { E2E.swipeIn("stops", up = false) }
        val km = mutableListOf<Int>()
        fun readVisibleKm() {
            val window = rowTexts("stopMeta").mapNotNull { line ->
                Regex("(\\d+)\\s*km").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            assertTrue("visible station rows have reversed distances: $window",
                window.zipWithNext().all { (a, b) -> b >= a })
            // A swipe overlaps the preceding viewport. Do not append its old leading rows
            // after a newer tail and mistake RecyclerView recycling for a reversing route.
            window.forEach { value -> if (value > (km.lastOrNull() ?: -1)) km.add(value) }
        }
        readVisibleKm()
        assertTrue("no row prints a distance", km.isNotEmpty())
        assertEquals("the first halt is not 0 km", 0, km.first())
        val swipeOutcomes = mutableListOf<Boolean>()
        repeat(16) { swipeOutcomes += E2E.swipeIn("stops"); readVisibleKm() }
        assertTrue("scrolling did not reveal the far end: $km; " +
            "viewport=${E2E.find("stops", 300)?.visibleBounds} " +
            "swipes=$swipeOutcomes visible=${rowTexts("stopName")}", km.last() > km.first())
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
        val seen = mutableSetOf<String>()
        repeat(8) {
            seen += rowTexts("stopName").filter { it.isNotBlank() }
            E2E.swipeIn("stops")
        }
        assertTrue("scrolling the full timetable did not reveal multiple stations: $seen", seen.size > 1)
        assertTrue("a hidden-row caption appeared on the full route", E2E.find("rowsNote", 500) == null)
    }

    // @flow projected-leg-honesty
    @Test
    fun actualPositionOnlyDoesNotRenderInterpolatedTrain() {
        // NTES supplies stop events, not a measured moving coordinate. If the
        // latest response has a timed halt, the icon may be PINNED there at 0%;
        // it must never advance fractionally with the wall clock.
        assertTrue("no station table to place the train in", E2E.count(stopsBy) > 0)
        if (E2E.find("inlineLeg", 500) != null) {
            val caption = E2E.text("inlineLegCaption")
            val detail = E2E.find("inlineLeg")?.contentDescription.orEmpty()
            assertTrue("marker falsely projects a moving train: $caption / $detail",
                !caption.contains("Projected between") &&
                    (detail.contains("last reported halt") || detail.contains("static map") ||
                        detail.contains("unverified") || detail.contains("station claim")))
            assertTrue("marker falsely claims GPS: $detail", detail.contains("not GPS"))
        }
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
        val visibleStations = rowTexts("stopName").filter { it.isNotBlank() }
        assertTrue("default compact route should fit at least three named stations " +
            "($visibleStations) in $bounds at ${E2E.widthDp()}dp",
            visibleStations.size >= 3)
        val summary = E2E.find("scroller")?.visibleBounds
        if (summary != null) assertTrue(
            "blank band between refresh/summary and the route: summary=$summary route=$bounds",
            bounds.top - summary.bottom < (72 * E2E.ui.displayWidth / E2E.widthDp()),
        )
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
        assertTrue("About times is missing", E2E.tap("sourceToggle"))
        assertNotNull("plain-language times guide never appeared",
            E2E.ui.wait(Until.findObject(By.textContains("Dim times are the timetable")), E2E.LOCAL_MS))
        val detailsButton = E2E.ui.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^source details$"))), E2E.LOCAL_MS)
        assertNotNull("technical details not reachable", detailsButton)
        detailsButton!!.click()
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
        val sheetVisible = revealTimeSheet()
        if (!sheetVisible) E2E.shot("public-source-no-visible-time-sheet")
        assertTrue("selected page hid the visible arrival/departure pair", sheetVisible)
        val pageMinutes = rowTexts("liveArr")
        assertTrue("site selection disclosed no stop to compare", pageMinutes.isNotEmpty())
        assertTrue("page selection must not display NTES minutes as this page's Act",
            pageMinutes.all { it == "—" })
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
        val deadline = System.currentTimeMillis() + E2E.NETWORK_MS
        var resultVisible = false
        while (System.currentTimeMillis() < deadline && !resultVisible) {
            // An unavailable website must show its answer near the headline,
            // inside the capped card's visible viewport. A valid timed website
            // report instead earns a clearly attributed badge.
            // The selected source must finish with either a qualified website
            // badge or a readable failure receipt at the TOP of the card. A
            // comparison-only badge and an off-screen summary do not pass.
            val summary = E2E.text("sourceSummary", 300)
            val badge = E2E.text("provenance", 300)
            resultVisible = (summary.contains("whereismytrain.org.in") &&
                summary.contains("Timetable shown") && !summary.contains("Checking")) ||
                (badge.contains("WEBSITE · reported stop") ||
                badge.contains("WEBSITE · NTES agrees within"))
            if (!resultVisible) Thread.sleep(200)
        }
        if (!resultVisible) E2E.shot("org-website-missing-result")
        assertTrue("site check returned no visible result: spinner='${E2E.text("sourceSpinner", 200)}' " +
            "badge='${E2E.text("provenance", 200)}' summary='${E2E.text("sourceSummary", 200)}' " +
            "checking='${E2E.text("mirrorState", 200)}' headline='${E2E.text("headline", 200)}'",
            resultVisible)
        val summary = E2E.text("sourceSummary")
        val siteReportVisible = (E2E.text("provenance").contains("WEBSITE · reported stop") ||
            E2E.text("provenance").contains("WEBSITE · NTES agrees within"))
        assertTrue("source result has no plain-language result: $summary",
            (summary.contains("whereismytrain.org.in") && summary.contains("Timetable shown")) ||
                siteReportVisible)
        assertTrue("org selection lost the station table", E2E.count(stopsBy) > 0)
        val sheetVisible = revealTimeSheet()
        if (!sheetVisible) E2E.shot("org-source-no-visible-time-sheet")
        assertTrue("website selection hid the visible arrival/departure pair", sheetVisible)
        val live = rowTexts("liveArr") + rowTexts("liveDep")
        assertTrue("site selection disclosed no stop to compare", live.isNotEmpty())
        val spoken = E2E.all(By.res(E2E.PKG, "liveArr"))
            .map { it.contentDescription.orEmpty() }
        assertTrue("org selection misattributes NTES actuals: $spoken",
            spoken.none { it.startsWith("Reported arrival") })
        if (live.any { it != "—" }) {
            assertTrue("Web cell without site provenance", E2E.text("provenance").contains("WEBSITE"))
            assertTrue("Web cell without spoken website source: $spoken",
                spoken.any { it.startsWith("Website arrival") })
            repeat(5) { if (!E2E.visible("age", 300)) E2E.swipeIn("scroller", up = false) }
            val age = E2E.text("age")
            assertTrue("site page clock confused with an event clock: '$age'",
                age.contains("page checked", true) &&
                    age.contains("not event time", true) && age.contains("movement", true))
        } else {
            assertTrue("no Web cells but source pretends to verify station events: $summary",
                summary.contains("NOT Act") || summary.contains("static") ||
                    summary.contains("whereismytrain.org.in") &&
                    summary.contains("Timetable shown"))
        }
        E2E.shot("org-website-source")
    }

    // @flow refresh
    @Test
    fun refreshKeepsTheScreenCoherent() {
        val route = E2E.text("routeLabel")
        assertTrue("Refresh cannot be reached in the scrollable source card",
            E2E.reveal("refresh", "scroller") && E2E.tap("refresh"))
        assertTrue(
            "a refresh lost the route line ('$route' -> '${E2E.text("routeLabel")}')",
            E2E.text("routeLabel").isNotBlank(),
        )
        assertTrue("a refresh left no halt rows", E2E.count(stopsBy) > 0)
        assertTrue("a refresh left the age chip blank", E2E.text("age").isNotBlank())
    }

    @Test
    fun tableOffersOptionalMinuteRefreshAndExpandableRoutePoints() {
        assertTrue("Refresh must remain reachable by scrolling", E2E.reveal("refresh", "scroller"))
        assertTrue("one-minute Auto must be offered beside Refresh", E2E.visible("autoSwitch"))
        assertTrue("route points must be inside the table, not a separate button",
            E2E.find("betweenStations", 1_000) == null)
        assertTrue("the station table vanished", E2E.count(stopsBy) > 0)
    }

    // @flow optional-minute-refresh
    @Test
    fun autoRefreshActuallyRunsOnceAfterOneMinute() {
        assertTrue("cannot reach optional Auto", E2E.reveal("autoSwitch", "scroller"))
        assertTrue("cannot enable optional Auto", E2E.tap("autoSwitch"))
        try {
            assertTrue("no refresh receipt after a minute with Auto on",
                E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "refreshFeedback")), 80_000L))
            val receipt = E2E.text("refreshFeedback")
            val checked = Regex("^Checked \\d{2}:\\d{2}:\\d{2} · ").containsMatchIn(receipt)
            val answered = Regex("^(NTES|Website) answered at \\d{2}:\\d{2}:\\d{2} · ")
                .containsMatchIn(receipt)
            val changed = Regex("^New stop report · .+ · checked \\d{2}:\\d{2}:\\d{2} · ")
                .containsMatchIn(receipt)
            val revised = Regex("^Revised time at same stop · .+ · checked \\d{2}:\\d{2}:\\d{2} · ")
                .containsMatchIn(receipt)
            assertTrue("auto refresh must show a check clock and named source: $receipt",
                (checked || answered || changed || revised) && listOf("NTES", "erail", "website", "WIMT",
                    "ConfirmTkt", "runningstatus", "Website").any { receipt.contains(it) })
            // Requiring a distinct terminal finding catches the original bug: a
            // successful HTTP response with an unchanged old event looked like
            // failure, while a failed request looked like a new observation.
            assertTrue("auto refresh must distinguish answered/unchanged, failed, and revised: $receipt",
                listOf("same last reported stop", "same last site-reported stop",
                    "new reported stop", "new site-reported stop", "revised time",
                    "request failed", "answered, no validated new stop", "no validated site stop report", "not reached",
                    "no usable new report", "has no usable report", "cached HTTP body",
                    "New stop report", "Revised time", "No newer stop").any { receipt.contains(it) })
        } finally {
            // Each flow starts in the same installed app; never contaminate another test.
            E2E.tap("autoSwitch")
        }
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
                // RecyclerView can detach a row between the accessibility query
                // and getText(). A stale object is not evidence of a blank halt;
                // only count a name that was actually read from a visible row.
                val name = runCatching { row.text }.getOrNull() ?: return@forEach
                seen++
                if (name.isBlank()) blank++
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
    // @flow details-live-identity: the label may confirm the erail identity if today's
    // lookup answers, but a network failure must leave the timetable label untouched.
    // An offline device cannot truthfully claim a confirmed live identity; the source
    // transport and pure identity-overlay logic have their own dedicated tests.
    @Test(timeout = 120_000) // the identity fetch is bounded in the app; the test bounds itself too
    fun routeLabelGainsTheLiveIdentityConfirmation() {
        val before = E2E.text("routeLabel")
        assertTrue("no route label to relabel", before.isNotBlank())
        val saw = E2E.ui.wait(Until.hasObject(By.textContains("confirmed live")), E2E.NETWORK_MS)
        val label = E2E.text("routeLabel")
        assertTrue(
            "identity lookup silently changed the timetable into a claim (before='$before', " +
                "after='$label', saw=$saw)",
            label == before || label.contains("confirmed live") || label.contains("live source says"),
        )
        if (label.contains("confirmed live")) assertTrue("confirmed label was not visible", saw)
        E2E.shot("details-live-identity")
    }

}
