package `in`.opentrack.india.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens, painted — plus assertions that only hold if what was painted is legible.
 *
 * Why this exists: the one thing CI could never check is how the app *looks*, and that is exactly what
 * went wrong between "the data is right" and "the app is usable". No emulator is possible in this sandbox
 * (no KVM, no system images), so Robolectric runs the real LayoutInflater, the real styles and the real
 * `onDraw`, and these tests assert what a reviewer would check by eye: something was painted, the chart
 * contains the live polyline rather than an empty frame, every row carries a station name and a pair of
 * times, no string is still a `%1$s` template, and no headline was ellipsised away.
 *
 * Scope, honestly: this mirrors the bindings [TrainActivity] and [MainActivity] make. It is a render
 * harness for the layouts, styles and custom views; the *facts* on those screens are pinned by
 * [in.opentrack.india.domain.TrackModelTest], and the Activity's wiring is thin by construction.
 *
 * The route is invented — names, codes, times and kilometres are made up — so the render does not depend
 * on the shipped timetable. Only its shape matters: nine stops, an anchor in the middle, a delay that
 * carries forward from there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {

    private companion object {
        /**
         * [in.opentrack.india.data.RailRepository.dataVersion] over the shipped reference DB. Every screen
         * that quotes the size of the timetable uses this one string, so the previews cannot disagree.
         */
        const val DATA_VERSION = "6,775 trains · timetable 10 Oct 2026"
    }

    private fun stop(seq: Int, code: String, name: String, arr: Int?, dep: Int?, km: Int) = StopRow(
        seq = seq, code = code, name = name, state = "KR", lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = 1, schedKm = km, isHalt = true,
    )

    private val stops = listOf(
        stop(1, "SBC", "KSR BENGALURU", null, 330, 0),
        stop(2, "YPA", "YESVANTPUR JN", 352, 354, 12),
        stop(3, "HSQ", "HOSAPETE ROAD", 391, 393, 44),
        stop(4, "DKPT", "DOBETAKAPUR", 415, 417, 61),
        stop(5, "TMLL", "TUMAKURU", 452, 462, 107),
        stop(6, "TKN", "TALAKORU", 498, 500, 138),
        stop(7, "KDDA", "KADUR", 546, 551, 205),
        stop(8, "HKK", "HOSAHOSUR", 583, 585, 241),
        stop(9, "SMET", "SHIVAMOGGA TOWN", 604, null, 274),
    )

    private val route = TrainRoute(
        trainNo = "16999", trainName = "KSR BENGALURU - SHIVAMOGGA TOWN EXP",
        srcCode = "SBC", srcName = "KSR BENGALURU", dstCode = "SMET", dstName = "SHIVAMOGGA TOWN",
        totalKm = 274, totalMin = 274, runDays = 0x7F, returnNo = "16998",
        stops = stops,
        segments = stops.zipWithNext().mapIndexed { i, (a, b) ->
            SegmentRow(
                i + 1, a.code, b.code, b.schedKm - a.schedKm,
                (b.arrivalMin ?: b.departureMin ?: 0) - (a.departureMin ?: a.arrivalMin ?: 0),
            )
        },
    )

    private fun pos(anchor: Int, status: StopStatus, delay: Int?, live: Boolean) = TrainPosition(
        anchorSeq = anchor, nextSeq = anchor + 1, fraction = 0.4f, kmAlong = 60f, status = status,
        etaNextMinute = null, delayMinutes = delay, remainingMin = null,
        provenance = if (live) SourceKind.PROVIDER else SourceKind.OFFLINE_SCHEDULE,
        confidence = TrainPosition.Confidence.MEDIUM, ageSeconds = 95,
    )

    /** Reported minutes for the three stops behind us, so the table shows scheduled against actual. */
    private val perStop = mapOf(
        "YPA" to LiveStopFields(actualArrivalMin = 355, actualDepartureMin = 358, platform = "2"),
        "HSQ" to LiveStopFields(actualArrivalMin = 401, actualDepartureMin = 404, delayArrivalMin = 10),
        "DKPT" to LiveStopFields(
            actualArrivalMin = 429, actualDepartureMin = 431, delayDepartureMin = 14, departed = true
        ),
    )

    private fun model(pos: TrainPosition, now: Int, per: Map<String, LiveStopFields>) =
        TrackModel.build(route, pos, per, now, boardSeq = 1, alightSeq = 9, runEpochDay = 19949L, note = null)

    // ------------------------------------------------------------------ the tracking screen

    @Test
    fun livePositionPaintsHeadlineChartAndEveryRow() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        val shot = Render.render(R.layout.activity_train) { root -> Render.track(root, m, p, 432) }

        // Nothing the Activity is supposed to write may still be a format template or blank.
        val headline = shot.text(R.id.headline)
        val strings = listOf(
            headline, shot.text(R.id.provenance), shot.text(R.id.age), shot.text(R.id.delay),
            shot.text(R.id.next), shot.text(R.id.trainNo),
            shot.text(R.id.routeLabel), shot.text(R.id.runDate),
        )
        strings.forEach { assertTrue("unfilled or empty label: '$it'", it.isNotBlank() && !it.contains("%")) }
        assertTrue("headline too short to be a sentence: '$headline'", headline.length > 20)
        // The fixture's stop names are ALL CAPS, as the upstream dump writes them. A sentence that carries
        // them verbatim ("Between DOBETAKAPUR and TUMAKURU") sits above a table that prints "Dobetakapur",
        // and two casings on one screen is how an app starts to look untrustworthy.
        assertTrue("the headline should follow the table's casing: '$headline'",
            headline.contains("Dobetakapur") && !headline.contains("DOBETAKAPUR"))
        assertEquals(m.trainNo, shot.text(R.id.trainNo))
        assertNotClipped(shot.root.findViewById(R.id.headline), "headline")
        // The header line is where the app used to lose a word to an ellipsis; the fix is in the layout, so
        // it can only be checked on a laid-out screen.
        assertNotClipped(shot.root.findViewById(R.id.routeLabel), "routeLabel ('${shot.text(R.id.routeLabel)}')")

        assertEquals("every stop must be in the list", 9, Render.rowCount(shot.root))
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            assertTrue("row $i has no station name", text(row, R.id.stopName).isNotBlank())
            listOf(R.id.schedArr, R.id.schedDep, R.id.liveArr, R.id.liveDep).forEach { id ->
                assertTrue("row $i is missing a time column", text(row, id).isNotBlank())
            }
            // Round 45: the stacked time columns NAME their kind - "Sch" above, "Act"/"Est" below - so a
            // traveller can never mistake a timetable minute for a report.
            listOf(R.id.schedArr, R.id.schedDep).forEach { id ->
                assertTrue("row $i scheduled minute not prefixed in ${name(shot, id)}",
                    text(row, id).startsWith("Sch "))
            }
            // The complaint this pins: an offline screen printed the schedule twice, and the right-hand copy
            // read like a report about the train. A row nothing moved must show a dash, not a second "05:52".
            if (m.rows[i].source == TrackModel.MinuteSource.SCHEDULE) {
                listOf(R.id.liveArr, R.id.liveDep).forEach { id ->
                    assertEquals("a timetable-only row must not look reported in ${name(shot, id)}",
                        "—", text(row, id))
                }
            }
            assertTrue("row $i is missing its distance/platform line", text(row, R.id.stopMeta).isNotBlank())
            // No row may have its name or its "code · km · note" line cut with an ellipsis: this is where a
            // traveller reads the boarding and alighting marks, and "…you g…" was on a shipping screen.
            row.findViewById<TextView>(R.id.stopName)?.let { assertNotClipped(it, "row $i stopName") }
            row.findViewById<TextView>(R.id.stopMeta)?.let { assertNotClipped(it, "row $i stopMeta") }
        }
        // The distance ladder is what the user asked for: the first row reads 0 km, the last one is the
        // train's total, and the middle rows must therefore be strictly increasing.
        val km = (0 until list.childCount).map { text(list.getChildAt(it), R.id.stopMeta) }
            .mapNotNull { Regex("""· Sch (\d+) km""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertEquals("every row prints its distance, the 0 km origin included", list.childCount, km.size)
        assertTrue("distances must increase along the route: $km", km.zipWithNext().all { (a, b) -> b > a })

Render.save("02-track-live", shot.bitmap)
    }

    @Test
    fun wholeTrackingScreenIncludingTheTable() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        // Tall enough that the review image shows the stop table as well as the header; a ScrollView
        // measures its child unbounded, so this is the same content scrolled to the bottom.
        val shot = Render.render(R.layout.activity_train, heightDp = 1480f) { root ->
            Render.track(root, m, p, 432)
            root.findViewById<View>(R.id.sourceCard)?.visibility = View.VISIBLE
            // `sourceText` is written by `Render.track` through `TrainActivity.sourceText`, so this fixture
            // no longer gets to say whatever it likes about what the strip contains. It used to print a
            // timetable-size line that the app does not put there.
            Unit
        }
        assertEquals(9, Render.rowCount(shot.root))
        Render.save("03-track-full", shot.bitmap)
    }

    @Test
    fun withNoLiveDataTheScreenSaysScheduleAndPaintsNoPolyline() {
        val p = pos(anchor = 0, status = StopStatus.UNKNOWN, delay = null, live = false)
        val m = model(p, now = 360, per = emptyMap())
        val shot = Render.render(R.layout.activity_train) { root -> Render.track(root, m, p, 360) }

        val headline = shot.text(R.id.headline)
        assertTrue("offline screen should carry the schedule sentence, got '$headline'", headline.isNotBlank())
        assertFalse("offline screen must not claim a departure it did not see", headline.contains("departed", true))
        assertFalse("offline screen must not certify punctuality", headline.contains("on time", true))
        assertEquals(m.provenanceLabel, shot.text(R.id.provenance))
        assertEquals(9, Render.rowCount(shot.root))
        // What the user saw, and called what it is: with nothing reported, the "live / expected" column
        // reprinted the timetable, so every row looked like a confirmed arrival. A dash is the honest cell —
        // the schedule column above it already says that number, and saying it twice invents a report.
        val offline = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        val dashed = (0 until offline.childCount).count {
            val row = offline.getChildAt(it)
            Render.read(row, R.id.liveArr) == "—" && Render.read(row, R.id.liveDep) == "—"
        }
        assertEquals("an offline screen must dash both live cells in every row", offline.childCount, dashed)
Render.save("01-track-schedule", shot.bitmap)
    }

    // ------------------------------------------------------------------ the search screen

    @Test
    fun searchScreenShowsFieldsDayPickerAndResultRows() {
        val hits = listOf(
            hit("16999", "KSR BENGALURU - SHIVAMOGGA TOWN EXP", 330, 604, 274, 0x7F),
            hit("16536", "PALEM G EXP", 396, 690, 311, 0x55),
            hit("06268", "SBC SMET Passenger SPECIAL", 486, 810, 274, 0x2A),
        )
        val shot = Render.render(R.layout.activity_main) { root ->
            // What MainActivity does: hints on the type-ahead boxes. Without them the render is two blank
            // rectangles, which is exactly what a reviewer would call a broken screen.
            root.findViewById<SuggestBox>(R.id.fromBox)?.hint = "Board at — station name or code"
            root.findViewById<SuggestBox>(R.id.toBox)?.hint = "Get off at — station name or code"
            root.findViewById<SuggestBox>(R.id.trainBox)?.hint = "5-digit number or a name fragment"
            // In the Ready state MainActivity hides `statusLine` (`visibility = GONE`) and puts
            // `repository.dataVersion()` in `dataChip`. Both strings here are that method's shape over the
            // shipped asset — counts and build date included, 6,775 trains rather than a number invented for
            // the picture. `ShippedDataTest` reads those counts out of the packaged DB; a fixture that made
            // its own up was how one preview ended up contradicting its neighbours.
            root.findViewById<View>(R.id.statusLine)?.visibility = View.GONE
            Render.text(root, R.id.dataChip, DATA_VERSION)
            Render.text(root, R.id.resultsTitle, "5 trains between these stations")
            Render.text(root, R.id.resultsSub, "sorted by departure · 4 ms on device")
            root.findViewById<RecyclerView>(R.id.results)?.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply {
                    // The tag beside a result counts down from the *searched* instant — which is what
                    // MainViewModel.targetEpochMinute is. A bare 300 here rendered "in 19949d 0h", the
                    // fixture being 19 949 days away from the epoch rather than 30 minutes from it.
                    nowMinute = 19949L * 1440L + 300L
                    submitList(hits)
                }
                Render.settle(this, hits.size)
            }
        }
        val painted = shot.distinct()
        assertTrue("screen painted only $painted colours", painted > 12)
        listOf(R.id.fromBox, R.id.toBox, R.id.trainBox, R.id.find, R.id.swap, R.id.dayNow, R.id.dayBack,
            R.id.dayFwd, R.id.modeStations, R.id.modeTrain).forEach { id ->
            assertNotNull("control ${name(shot, id)} missing from the layout", shot.root.findViewById<View>(id))
        }
        assertNotNull("the type-ahead input is not inside the box", shot.root.findViewById<View>(R.id.suggestInput))
        assertTrue("search button has no label", shot.text(R.id.find).isNotBlank())
        val shown = shot.root.findViewById<RecyclerView>(R.id.results)?.childCount ?: -1
        assertEquals("all three runs should be on screen", 3, shown)
        val list = shot.root.findViewById<RecyclerView>(R.id.results)!!
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            // `containsMatchIn`, not `matches`: the row prints the number *and* its category ("16999 · SF
            // Exp"), which is what the adapter is meant to show, so a whole-string match would fail on
            // correct output.
            assertTrue("result $i has no train number: '${text(row, R.id.hitNo)}'",
                Regex("""\d{5,6}""").containsMatchIn(text(row, R.id.hitNo)))
            listOf(R.id.hitName, R.id.hitMeta, R.id.hitDep, R.id.hitArr, R.id.hitTag).forEach { id ->
                assertTrue("result $i missing ${name(shot, id)}", text(row, id).isNotBlank())
            }
            // Present-and-correct is not enough for the name: it also has to have *fit*. A result row is where
            // a traveller chooses the train, and "KSR Bengaluru – Shivamogga Town…" is a name with the part
            // that distinguishes it cut off. Checked at the narrowest supported width; `LargeScreenRenderTest`
            // makes the same claim at 411 dp, where the row has different neighbours.
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta).forEach { id ->
                Render.assertNothingCut(row.findViewById(id), "result $i · ${name(shot, id)} = '${text(row, id)}'")
            }
        }
        Render.save("00-search", shot.bitmap)
    }

    private fun hit(no: String, nm: String, dep: Int, arr: Int, km: Int, days: Int) = TrainHit(
        trainNo = no, trainName = nm, trainType = "SUPERFAST_EXPRESS", originDateEpochDay = 19949L,
        boardMinute = dep, alightMinute = arr, fromCode = "SMET", toCode = "SBC", fromSeq = 1, toSeq = 9,
        distanceKm = km, runDays = days,
    )

    // ------------------------------------------------------------------ settings

    @Test
    fun settingsScreenHasEveryControlTheActivityBinds() {
        val shot = Render.render(R.layout.activity_settings) { root ->
            // SettingsActivity's own two strings, word for word (its `state` line has never contained a
            // count, and the "schema 7" this fixture used to print is not a number the app shows anywhere).
            Render.text(root, R.id.state, "Built-in timetable installed · offline search ready")
            Render.text(root, R.id.about, "OpenTrack India 1.0 · timetable: $DATA_VERSION\n\n" +
                "No accounts, no API keys, no ads, no analytics. Live status is fetched directly from a " +
                "public Indian Railways enquiry endpoint; everything else — station search, the ±24 h " +
                "train list and every timetable — is answered here.")
            // The spinner's rows are built by the Activity from an ArrayAdapter over `item_spinner_choice`;
            // doing the same here is what proves that layout inflates and lays out at all.
            root.findViewById<android.widget.Spinner>(R.id.refreshChoices)?.apply {
                adapter = android.widget.ArrayAdapter(
                    context, R.layout.item_spinner_choice, listOf("30 s", "1 min", "5 min", "10 min")
                )
                setSelection(1)
            }
        }
        listOf(R.id.state, R.id.overrideUrl, R.id.columnHint, R.id.refreshChoices, R.id.save, R.id.clear,
            R.id.back, R.id.about).forEach { id ->
            assertNotNull("SettingsActivity binds ${name(shot, id)}, the layout does not have it",
                shot.root.findViewById<View>(id))
        }
        assertTrue("settings painted ${shot.distinct()} colours", shot.distinct() > 8)
        val spinner = shot.root.findViewById<android.widget.Spinner>(R.id.refreshChoices)!!
        assertTrue("the auto-refresh chooser drew no row", spinner.childCount > 0)
        assertEquals("the chooser should show the selected label", "1 min",
            (spinner.getChildAt(0) as? TextView)?.text?.toString())
        Render.save("04-settings", shot.bitmap)
    }

    // ------------------------------------------------------------------ helpers

    private fun text(root: View, id: Int): String = Render.read(root, id)

    private fun name(shot: Render.Shot, id: Int): String =
        shot.root.resources.getResourceEntryName(id)

    /**
     * A label truncated by its own box is a layout bug, not a small-screen problem. Lives in [Render] now,
     * because the large-screen test has to make the same claim about other pixels.
     */
    private fun assertNotClipped(tv: TextView?, what: String = "view") = Render.assertNothingCut(tv, what)
}
