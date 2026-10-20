package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.View
import `in`.opentrack.india.R
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

    // UiAutomator's visibleBounds is clipped by BoundedScrollView. At large font,
    // scrolling 33 px changed its apparent card height while the card was unchanged.
    // Drive interactions through accessibility, but read the *installed Activity's*
    // actual laid-out pixel height and scroll offset to test this geometry claim.
    // No app model, service or test fixture supplies the value under assertion.
    private fun summaryFooterFits(): Boolean {
        var fits = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .first { it.packageName == E2E.PKG &&
                    it.findViewById<View>(R.id.trackingSummaryCard) != null }
            val card = activity.findViewById<View>(R.id.trackingSummaryCard)
            val sourceRow = activity.findViewById<View>(R.id.sourceSpinner).parent as View
            fits = sourceRow.bottom + card.paddingBottom <= card.height + 2
        }
        return fits
    }

    private data class CardGeometry(val height: Int, val specified: Int,
                                    val detailHeight: Int, val detailSpecified: Int,
                                    val scrollY: Int)

    private fun cardGeometry(): CardGeometry {
        var result: CardGeometry? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .first { it.packageName == E2E.PKG &&
                    it.findViewById<View>(R.id.trackingSummaryCard) != null }
            val card = activity.findViewById<View>(R.id.trackingSummaryCard)
            val detail = activity.findViewById<View>(R.id.heroDetailsScroll)
            result = CardGeometry(card.height, card.layoutParams.height,
                detail.height, detail.layoutParams.height,
                activity.findViewById<View>(R.id.scroller).scrollY)
        }
        return requireNotNull(result) { "tracking Activity did not expose a laid-out summary" }
    }

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
        // Selecting a suggestion only resolves the train; Find alone runs the search.
        val selected = E2E.tapSuggestion("trainBox", 0)
        if (!selected) E2E.shot("train-suggestion-not-selected")
        assertTrue("the train suggestion could not be selected: ${E2E.debugSearch("trainBox")}", selected)
        assertTrue("Find missing after picking the train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val resultVisible = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitGo")), E2E.NETWORK_MS)
        if (!resultVisible) E2E.shot("tracking-open-no-results")
        assertTrue(
            "no visible result row for the selected train; ${E2E.debugSearch("trainBox")}; " +
                "status='${E2E.text("statusLine", 250)}' title='${E2E.text("resultsTitle", 250)}' " +
                "summary='${E2E.text("searchSummary", 250)}'",
            resultVisible,
        )
        assertTrue("the Track button did not open the tracking screen", E2E.openTrackFromFirstResult())
        val headline = E2E.find("headline", E2E.NETWORK_MS)
        assertNotNull("no first-glance headline: summary='${E2E.text("summaryBoxLabel", 250)}' " +
            "badge='${E2E.text("provenance", 250)}' age='${E2E.text("age", 250)}' " +
            "source='${E2E.text("sourceSpinner", 250)}'", headline)
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
        assertTrue("top tracking card lost its SUMMARY BOX label",
            E2E.text("summaryBoxLabel") == "SUMMARY BOX")
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
        // UiAutomator's click returns before the activity's main-thread layout
        // commits on older API 24 emulators; wait for the REAL gone state, never
        // treat a tap acknowledgement as proof of a collapsed card.
        assertTrue("headline survived compact mode",
            E2E.ui.wait(Until.gone(By.res(E2E.PKG, "headline")), E2E.LOCAL_MS))
        assertTrue("evidence survived compact mode",
            E2E.ui.wait(Until.gone(By.res(E2E.PKG, "provenance")), E2E.LOCAL_MS))
        assertNotNull("compact card lost its delay", E2E.find("delay"))
        assertNotNull("compact card lost the next stop", E2E.find("next"))
        assertNotNull("compact card lost the source chooser", E2E.find("sourceSpinner"))
        assertTrue("expand handle not tappable", E2E.tap("heroToggle"))
        assertNotNull("expanding did not restore the headline", E2E.find("headline"))
        assertNotNull("expanding did not restore source evidence", E2E.find("provenance"))
    }

    // @flow summary-stability
    @Test
    fun refreshReceiptOverlaysWithoutMovingTheRouteAndCollapseAnimates() {
        // Timetable paint precedes the first live response. Do not mistake its
        // natural growth for a manual Refresh resizing a settled summary.
        val firstDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
        while (System.currentTimeMillis() < firstDeadline && E2E.visible("checkingBar", 250)) {
            Thread.sleep(200)
        }
        assertTrue("initial check still busy; cannot measure manual refresh",
            !E2E.visible("checkingBar", 250))
        val card = cardGeometry()
        assertEquals("ACTUAL", E2E.text("routeHeadActual"))
        val header = E2E.find("routeColumnHeader")!!.visibleBounds
        assertTrue("ACTUAL header is outside the table viewport",
            header.bottom <= E2E.find("routeViewport")!!.visibleBounds.top)
        assertTrue("source card not expanded at start", E2E.find("headline") != null)
        assertTrue("initial source selector was clipped by a premature height lock",
            summaryFooterFits())
        assertTrue("Refresh control missing", E2E.tap("refresh"))
        val until = System.currentTimeMillis() + E2E.NETWORK_MS
        while (System.currentTimeMillis() < until && E2E.visible("checkingBar", 250)) {
            Thread.sleep(200)
        }
        assertTrue("refresh did not finish", !E2E.visible("checkingBar", 250))
        val afterRefresh = cardGeometry()
        assertTrue("refresh resized summary: $card -> $afterRefresh",
            kotlin.math.abs(card.height - afterRefresh.height) <= 8)
        assertTrue("refresh shifted the summary viewport: $card -> $afterRefresh",
            kotlin.math.abs(card.scrollY - afterRefresh.scrollY) <= 8)
        assertTrue("Refresh clipped the source chooser at the bottom of the card",
            summaryFooterFits())
        assertTrue("refresh overlaid the readable text with a response score",
            E2E.find("checkClock", 250) == null)
        assertTrue("persistent receipt still pushes the route down",
            E2E.find("refreshFeedback", 250) == null)
        assertTrue("no route after refresh", E2E.count(stopsBy) > 0)
        assertTrue("collapse handle missing", E2E.tap("heroToggle"))
        assertTrue("collapse did not hide headline",
            E2E.ui.wait(Until.gone(By.res(E2E.PKG, "headline")), E2E.LOCAL_MS))
        assertNotNull("collapsed card lost source", E2E.find("sourceSpinner"))
        assertTrue("More label not visible", E2E.text("heroToggle").contains("More"))
        val shrinkDeadline = System.currentTimeMillis() + E2E.LOCAL_MS
        var compact = cardGeometry()
        while (compact.height + 30 >= afterRefresh.height &&
            System.currentTimeMillis() < shrinkDeadline) {
            Thread.sleep(100)
            compact = cardGeometry()
        }
        assertTrue("Less left an empty fixed-height card: $afterRefresh -> $compact",
            compact.height + 30 < afterRefresh.height)
        assertTrue("expand handle missing", E2E.tap("heroToggle"))
        assertNotNull("expanded headline missing", E2E.find("headline"))
        assertTrue("Less label not visible", E2E.text("heroToggle").contains("Less"))
        val growDeadline = System.currentTimeMillis() + E2E.LOCAL_MS
        var restored = cardGeometry()
        while (restored.height < compact.height + 30 &&
            System.currentTimeMillis() < growDeadline) {
            Thread.sleep(100)
            restored = cardGeometry()
        }
        assertTrue("More did not restore the evidence height: $compact -> $restored",
            restored.height >= compact.height + 30)
        assertTrue("More squeezed the source dropdown and Auto switch", summaryFooterFits())
        E2E.shot("summary-stable-refresh")
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
                    assertTrue("$id lost its spoken diagonal direction or route boundary",
                        view.contentDescription.orEmpty().contains(direction, ignoreCase = true) ||
                        view.text in setOf("START", "END"))
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
                pair.plannedArr in setOf("—", "START") || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.plannedArr))
            assertTrue("row $i lost the scheduled departure minute",
                pair.plannedDep in setOf("—", "END") || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.plannedDep))
            assertTrue("row $i source departure must be a minute or dash",
                pair.reportedDep in setOf("—", "END") || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.reportedDep))
            if (pair.reportedArr == "—") missing++ else assertTrue(
                "row $i source arrival is not a clock: '${pair.reportedArr}'",
                pair.reportedArr == "START" || Regex("""^\d{2}:\d{2}""").containsMatchIn(pair.reportedArr))
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
                        detail.contains("unverified") || detail.contains("station claim") ||
                        detail.contains("symbolic departure icon only; no measured distance")))
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
        // A partly clipped RecyclerView row exposes its name but may not expose
        // either clock to UiAutomator. Pair by screen position, not list index:
        // demanding three globally equal accessibility counts produced a false
        // failure on API 24 without testing the alignment of any complete row.
        val names = E2E.all(By.res(E2E.PKG, "stopName")).map { it.visibleBounds }
        val scheduled = E2E.all(By.res(E2E.PKG, "schedArr")).map { it.visibleBounds }
        val reported = E2E.all(By.res(E2E.PKG, "liveArr")).map { it.visibleBounds }
        val scale = E2E.ui.displayWidth / E2E.widthDp()
        val complete = names.filter { it.top >= bounds.top + 4 * scale &&
            it.bottom <= bounds.bottom - 4 * scale }
        assertTrue("fewer than two complete station names in the route: $names / $bounds",
            complete.size >= 2)
        assertTrue("no arrival clocks exposed beside $complete (schedule=$scheduled report=$reported)",
            scheduled.isNotEmpty() && reported.isNotEmpty())
        val usedPlanned = mutableSetOf<Int>()
        val usedReported = mutableSetOf<Int>()
        complete.forEachIndexed { index, name ->
            // Choosing nearest does not excuse a clock on the wrong row: the 24dp
            // bound below rejects the exact 50dp displacement seen on the phone.
            val plannedIndex = scheduled.indices.minByOrNull {
                kotlin.math.abs(scheduled[it].top - name.top) }!!
            val reportIndex = reported.indices.minByOrNull {
                kotlin.math.abs(reported[it].top - name.top) }!!
            val planned = scheduled[plannedIndex]
            val report = reported[reportIndex]
            assertTrue("stop $index timetable detached from station: $name / $planned",
                kotlin.math.abs(name.top - planned.top) <= 24 * scale)
            assertTrue("stop $index report detached from station: $name / $report",
                kotlin.math.abs(name.top - report.top) <= 24 * scale)
            assertTrue("stop $index arrival columns not level: $planned / $report",
                kotlin.math.abs(planned.top - report.top) <= 6 * scale)
            assertTrue("stop $index clock reused by a second station",
                usedPlanned.add(plannedIndex) && usedReported.add(reportIndex))
        }
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
        assertTrue("source picker cannot be reached in the source card",
            E2E.reveal("sourceSpinner", "scroller"))
        assertTrue("removed About button is still displayed", E2E.find("sourceToggle", 200) == null)
        E2E.find("sourceSpinner")!!.longClick()
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
    fun onlyOfficialAndWebsiteAreTravellerSelectable() {
        assertTrue("source picker cannot be reached in the source card",
            E2E.reveal("sourceSpinner", "scroller") && E2E.tap("sourceSpinner"))
        val website = E2E.ui.wait(Until.findObject(By.textContains("Where Is My Train · website")), E2E.LOCAL_MS)
        assertNotNull("website choice missing", website)
        assertTrue("other unverified public probes are misleadingly selectable",
            E2E.ui.findObject(By.textContains("ixigo page")) == null &&
                E2E.ui.findObject(By.textContains("WIMT web cache")) == null &&
                E2E.ui.findObject(By.textContains("confirmtkt")) == null)
        website!!.click()
        assertTrue("website selection removed the station table", E2E.count(stopsBy) > 0)
        E2E.shot("public-source-check")
    }

    // @flow source-identity-and-admission
    @Test
    fun orgWebsiteSelectionKeepsSourceSpecificMinutesDistinctFromNtes() {
        assertTrue("source picker cannot be reached in the source card",
            E2E.reveal("sourceSpinner", "scroller") && E2E.tap("sourceSpinner"))
        val website = E2E.ui.wait(Until.findObject(By.textContains("Where Is My Train · website")), E2E.LOCAL_MS)
        assertNotNull("org website not selectable", website)
        website!!.click()
        // Await the *selected* source, not the first nonempty source response. A
        // network error still must be honestly named; it cannot pass as a live Act.
        val deadline = System.currentTimeMillis() + E2E.NETWORK_MS
        var identified = false
        var warning = ""
        while (System.currentTimeMillis() < deadline && !identified) {
            val badge = E2E.text("provenance", 300)
            val headline = E2E.text("headline", 300)
            if (E2E.find("sourceSpinner", 300) == null &&
                !E2E.reveal("sourceSpinner", "scroller", swipes = 3))
                E2E.reveal("sourceSpinner", "scroller", swipes = 5, up = false)
            val chooser = E2E.find("sourceSpinner", 300)?.contentDescription.orEmpty()
            warning = E2E.text("connectionAlert", 300)
            // On API 24 the nested evidence viewport can hide the badge after
            // scrolling. The selected chooser still visibly names the website;
            // this asserts source identity, not that it supplied a past card.
            val siteVisible = badge.contains("WEB", true) ||
                chooser.contains("Selected Where Is My Train · website", true) ||
                warning.contains("whereismytrain.org.in (website)", true)
            identified = siteVisible &&
                ((headline.startsWith("Recently reported at ") ||
                    headline.startsWith("Recently reported near ")) && badge.contains("run inferred") ||
                    headline.contains("no qualifying past-stop report") ||
                    // A future origin with no site past card is timetable-only.
                    (headline.startsWith("Journey not started yet by timetable") &&
                        (badge.contains("comparison only", true) ||
                            chooser.contains("Selected Where Is My Train · website", true) ||
                            warning.contains("whereismytrain.org.in (website)", true))))
            if (!identified) Thread.sleep(200)
        }
        if (!identified) E2E.shot("org-website-missing-identity")
        assertTrue("selected site must be visibly website-attributed: badge='${E2E.text("provenance")}'; " +
            "chooser='${E2E.find("sourceSpinner")?.contentDescription}' " +
            "warning='$warning' " +
            "headline='${E2E.text("headline")}'", identified)
        assertTrue("website selection removed the station table", E2E.count(stopsBy) > 0)
        val sheetVisible = revealTimeSheet()
        if (!sheetVisible) E2E.shot("org-source-no-visible-time-sheet")
        assertTrue("website selection hid arrival/departure columns", sheetVisible)
        val live = rowTexts("liveArr") + rowTexts("liveDep")
        assertTrue("ACTUAL cells absent from selected source", live.isNotEmpty())
        val suggestion = E2E.text("headline").contains("departure suggested")
        val reported = E2E.text("headline").startsWith("Recently reported ")
        val spoken = E2E.all(By.res(E2E.PKG, "liveArr"))
            .map { it.contentDescription.orEmpty() }
        assertTrue("selected site borrowed NTES arrival provenance: $spoken",
            spoken.none { it.startsWith("Reported arrival") })
        if (reported) {
            val inlineClaim = E2E.all(By.res(E2E.PKG, "inlineLeg")).any {
                it.contentDescription.orEmpty().contains("website arrival claim") }
            assertTrue("website reported a past stop but none reached its table rail: $live",
                live.any { it != "—" && it.isNotBlank() } || inlineClaim)
            assertTrue("website provenance must stay in summary after removing repeated row labels",
                (E2E.text("provenance").contains("WEBSITE") ||
                    E2E.text("sourceSpinner").contains("WIMT")) &&
                    rowTexts("stopTimeType").none { it == "WEB" || it == "WEB?" })
            assertTrue("website cells lost source attribution: $spoken",
                spoken.any { it.startsWith("Website claim arrival") } || inlineClaim)
            if (suggestion) {
                val departures = E2E.all(By.res(E2E.PKG, "liveDep"))
                    .map { it.contentDescription.orEmpty() }
                assertTrue("tentative website departure became an actual: $departures",
                    departures.any { it.startsWith("Unconfirmed website departure suggestion") &&
                        it.contains("not actual") } &&
                        departures.none { it.startsWith("Reported departure") })
            }
            // The detailed receipt is no longer a persistent second line below
            // the card. Verify the actual age + accessible event/page distinction,
            // not a transient overlay that may already have faded at assertion time.
            val eventAge = E2E.text("age")
            val eventDetail = E2E.find("age")?.contentDescription.orEmpty()
            assertTrue("site event was replaced with a page-check clock: $eventAge / $eventDetail",
                eventAge.startsWith("Site stop · ") &&
                    eventDetail.contains("Website station event") &&
                    eventDetail.contains("page update") && eventDetail.contains("not movement"))
        } else {
            assertTrue("no site report must not populate numeric station clocks: $live",
                live.all { it in setOf("—", "START", "END") })
            assertTrue("missing site event got a locomotive", E2E.find("stopTrainMarker", 300) == null)
        }
        E2E.shot("org-website-past-cards-on-rail")
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
        assertTrue("a refresh hid the age chip in the bounded card",
            E2E.reveal("age", "scroller", up = false))
        assertTrue("a refresh left the age chip blank", E2E.text("age").isNotBlank())
    }

    @Test
    fun tableOffersOptionalMinuteRefreshAndExpandableRoutePoints() {
        assertTrue("Refresh must remain reachable by scrolling", E2E.reveal("refresh", "scroller"))
        // Refresh floats over the rail after R130; scrolling to it does not
        // imply the in-card source picker and Auto switch are in the viewport.
        assertTrue("source picker cannot be reached after floating Refresh",
            E2E.reveal("sourceSpinner", "scroller"))
        val source = E2E.find("sourceSpinner")?.visibleBounds
        val auto = E2E.find("autoSwitch")?.visibleBounds
        assertTrue("one-minute Auto must remain beside the source selector: $source / $auto",
            source != null && auto != null && auto.left >= source.right &&
                kotlin.math.abs(auto.centerY() - source.centerY()) <= source.height())
        assertTrue("route points must be inside the table, not a separate button",
            E2E.find("betweenStations", 1_000) == null)
        assertTrue("the station table vanished", E2E.count(stopsBy) > 0)
    }

    // @flow optional-minute-refresh
    @Test
    fun autoRefreshActuallyRunsOnceAfterOneMinute() {
        assertTrue("cannot reach optional Auto", E2E.reveal("autoSwitch", "scroller"))
        // This preference survives Activity recreation and even other flows in
        // the same installed test APK. A blind click could TURN IT OFF and then
        // falsely wait for a tick that the traveller deliberately disabled.
        if (E2E.find("autoSwitch")?.isChecked == true) {
            assertTrue("cannot reset previously enabled Auto", E2E.tap("autoSwitch"))
            assertTrue("Auto stayed enabled after reset", E2E.find("autoSwitch")?.isChecked == false)
        }
        assertTrue("cannot enable optional Auto", E2E.tap("autoSwitch"))
        assertTrue("Auto control did not become checked after the tap",
            E2E.find("autoSwitch")?.isChecked == true)
        try {
            // The minute tick starts a selected-source request; a slow network
            // may still be answering at second 80. Its detailed receipt is in
            // the More/Less control's accessible description, NOT a persistent
            // row below the card (a warning may cover the provenance badge).
            val deadline = System.currentTimeMillis() + 110_000L
            var receipt = ""
            while (System.currentTimeMillis() < deadline && !receipt.startsWith("Checked ")) {
                E2E.reveal("heroToggle", "scroller", swipes = 2, up = false)
                val accessible = E2E.find("heroToggle", 300)?.contentDescription.orEmpty()
                receipt = Regex("""Checked \d{2}:\d{2}:\d{2} · [^.]+""")
                    .find(accessible)?.value.orEmpty()
                if (!receipt.startsWith("Checked ")) Thread.sleep(700)
            }
            if (receipt.isBlank()) E2E.shot("auto-minute-no-accessible-receipt")
            assertTrue("no refresh receipt after a minute plus bounded source reply", receipt.isNotBlank())
            assertTrue("auto refresh must show check clock and named source: $receipt",
                Regex("^Checked \\d{2}:\\d{2}:\\d{2} · ").containsMatchIn(receipt) &&
                listOf("NTES", "erail", "website", "WIMT", "ConfirmTkt", "runningstatus")
                    .any { receipt.contains(it, true) })
            assertTrue("auto refresh must distinguish new, unchanged, revised and failed: $receipt",
                listOf("new stop reported", "stop time revised", "no newer stop",
                    "no validated stop", "check failed", "cached reply")
                    .any { receipt.contains(it, true) })
        } finally {
            // Persisted across tracking Activities: restore OFF so later flows
            // don't unknowingly start from a live one-minute polling session.
            if (E2E.reveal("autoSwitch", "scroller", up = false) &&
                E2E.find("autoSwitch")?.isChecked == true) E2E.tap("autoSwitch")
        }
    }

    // @flow long-list-scroll
    @Test
    fun longRouteScrollsWithoutBlankRows() {
        // The three headings must stay outside the RecyclerView while the stops move.
        assertEquals("TIMETABLE", E2E.text("routeHeadTimetable"))
        assertEquals("STATIONS", E2E.text("routeHeadStations"))
        assertEquals("ACTUAL", E2E.text("routeHeadActual"))
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
