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
        val strings = listOf(
            shot.text(R.id.headline), shot.text(R.id.provenance), shot.text(R.id.age), shot.text(R.id.delay),
            shot.text(R.id.next), shot.text(R.id.progressLabel), shot.text(R.id.trainNo),
            shot.text(R.id.routeLabel), shot.text(R.id.runDate), shot.text(R.id.chartLegend),
        )
        strings.forEach { assertTrue("unfilled or empty label: '$it'", it.isNotBlank() && !it.contains("%")) }
        assertTrue("headline too short to be a sentence: '${shot.text(R.id.headline)}'",
            shot.text(R.id.headline).length > 20)
        assertEquals(m.trainNo, shot.text(R.id.trainNo))
        assertNotClipped(shot.root.findViewById(R.id.headline))

        assertEquals("every stop must be in the list", 9, Render.rowCount(shot.root))
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            assertTrue("row $i has no station name", text(row, R.id.stopName).isNotBlank())
            listOf(R.id.schedArr, R.id.schedDep, R.id.liveArr, R.id.liveDep).forEach { id ->
                assertTrue("row $i is missing a time column", text(row, id).isNotBlank())
            }
            // The word under the live column is what says whether that minute was reported or projected.
            assertTrue("row $i does not name what its live column holds", text(row, R.id.liveTag).isNotBlank())
            assertTrue("row $i is missing its distance/platform line", text(row, R.id.stopMeta).isNotBlank())
        }
        // The distance ladder is what the user asked for: the first row reads 0 km, the last one is the
        // train's total, and the middle rows must therefore be strictly increasing.
        val km = (0 until list.childCount).map { text(list.getChildAt(it), R.id.stopMeta) }
            .mapNotNull { Regex("""· (\d+) km""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertEquals("rows should each carry a km value", list.childCount, km.size)
        assertTrue("distances must increase along the route: $km", km.zipWithNext().all { (a, b) -> b > a })

        // The chart has to contain the live polyline: `TrackInsightView` paints the accent colour only
        // when a position exists, so this fails on an empty frame or a zero-height view.
        val chart = shot.bounds(R.id.chart)
        assertTrue("chart has no height: $chart", chart.height() > Render.dp(shot.context, 90f).toInt())
        assertTrue("chart painted ${shot.distinct(chart)} colours", shot.distinct(chart) > 40)
        assertTrue("no live polyline in the chart", shot.paintedWith(shot.context.getColor(R.color.accent), chart))
        assertTrue("progress bar unpainted", shot.distinct(shot.bounds(R.id.progressBar)) > 3)
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
            Render.text(root, R.id.sourceText,
                "LIVE · erail.in\nfetched 1 min ago\n6,775 trains · 141,908 stops on device")
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
        // Without a position the accent polyline is absent; the timetable line is drawn in text2/warn.
        assertFalse("a schedule-only view painted a live polyline",
            shot.paintedWith(shot.context.getColor(R.color.accent), shot.bounds(R.id.chart)))
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
            Render.text(root, R.id.statusLine, "Timetable ready · 6,775 trains on device")
            Render.text(root, R.id.dataChip, "Built-in timetable · 11 Oct 2026")
            Render.text(root, R.id.resultsTitle, "5 trains between these stations")
            Render.text(root, R.id.resultsSub, "sorted by departure · 4 ms on device")
            root.findViewById<RecyclerView>(R.id.results)?.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply {
                    nowMinute = 300L
                    // setCurrentList, not submitList: the setter is synchronous, and a background diff
                    // would never land in a test that does not idle the looper.
                    setCurrentList(hits)
                }
            }
        }
        assertTrue("screen painted ${shot.distinct()} colours", shot.distinct() > 12)
        listOf(R.id.fromBox, R.id.toBox, R.id.trainBox, R.id.find, R.id.swap, R.id.dayNow, R.id.dayBack,
            R.id.dayFwd, R.id.modeStations, R.id.modeTrain).forEach { id ->
            assertNotNull("control ${name(shot, id)} missing from the layout", shot.root.findViewById<View>(id))
        }
        assertNotNull("the type-ahead input is not inside the box", shot.root.findViewById<View>(R.id.suggestInput))
        assertTrue("search button has no label", shot.text(R.id.find).isNotBlank())
        assertEquals(3, shot.root.findViewById<RecyclerView>(R.id.results)?.childCount)
        val list = shot.root.findViewById<RecyclerView>(R.id.results)!!
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            assertTrue("result $i has no train number", text(row, R.id.hitNo).matches(Regex("""\d{5,6}""")))
            listOf(R.id.hitName, R.id.hitMeta, R.id.hitDep, R.id.hitArr, R.id.hitTag).forEach { id ->
                assertTrue("result $i missing ${name(shot, id)}", text(row, id).isNotBlank())
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
            Render.text(root, R.id.state, "Built-in timetable\n6,775 trains · 141,908 stops · 8,990 " +
                "stations\ninstalled 11 Oct 2026 · schema 7")
        }
        listOf(R.id.state, R.id.overrideUrl, R.id.columnHint, R.id.refreshChoices, R.id.save, R.id.clear,
            R.id.back, R.id.about).forEach { id ->
            assertNotNull("SettingsActivity binds ${name(shot, id)}, the layout does not have it",
                shot.root.findViewById<View>(id))
        }
        assertTrue("settings painted ${shot.distinct()} colours", shot.distinct() > 8)
        Render.save("04-settings", shot.bitmap)
    }

    // ------------------------------------------------------------------ helpers

    private fun text(root: View, id: Int): String = Render.read(root, id)

    private fun name(shot: Render.Shot, id: Int): String =
        shot.root.resources.getResourceEntryName(id)

    /** A label truncated by its own box is a layout bug, not a small-screen problem. */
    private fun assertNotClipped(tv: TextView?) {
        assertNotNull("no headline view to measure", tv)
        val layout = tv!!.layout
        assertNotNull("headline was never laid out")
        for (line in 0 until layout.lineCount) {
            assertEquals("headline clipped at line $line of '${tv.text}'", 0, layout.getEllipsisCount(line))
        }
    }
}
