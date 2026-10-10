package `in`.opentrack.india.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The same screens, on a wider phone and with the largest system font.
 *
 * Why this is a separate file rather than more cases in [ScreenRenderTest]: the size a screen is painted at
 * comes from Robolectric's `qualifiers`, which are per-class, and every clipping bug found by eye this week
 * ("SBC → SMET · 274 km · …", "SMET · 274 km · you g…") was a *width* problem. Fixing those at 360 dp proves
 * they are fixed at 360 dp, which is the smallest phone this app claims to support and the one place a
 * one-line label survives by luck.
 *
 * 411 × 891 dp is a Pixel 6 / Galaxy S21 class viewport in portrait — the width most of the installed base
 * actually has — and [FONT] is 1.3, the top of AOSP's "Font size" slider (Settings → Display), with the
 * honesty note in [theBigFontRenderIsActuallyRenderingABigFont]: an assertion about big text is worthless
 * if the harness quietly renders small text, so that is measured first.
 *
 * What is asserted is only what a *layout* can be wrong about: no label was cut to an ellipsis at this size,
 * the columns still hold their times, the boarding and alighting notes still read in full, and the screens
 * paint. The facts themselves are pinned in `TrackModelTest` and `ShippedDataTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeScreenRenderTest {

    private val app: App get() = RuntimeEnvironment.getApplication() as App

    @Test
    fun theBigFontRenderIsActuallyRenderingABigFont() {
        val text = "Should be past Yesvantpur Jn, 6m into the 32 km leg"
        fun headlineSize(scale: Float): Float {
            val shot = Render.render(R.layout.activity_train) { root -> Render.text(root, R.id.headline, text) }
            return (shot.root.findViewById<TextView>(R.id.headline))?.textSize ?: -1f
        }
        val base = headlineSize(1f)
        val big = headlineSize(FONT)
        assertTrue(
            "fontScale $FONT changed nothing (base $base px, scaled $big px) — every picture in this file " +
                "would be a render of the default font wearing a label",
            base > 0f && big > base * 1.2f,
        )
    }

    @Test
    fun liveTrackingScreenKeepsEveryWordAt411dpAndTheLargestFont() {
        val route = shippedRoute()
        val anchor = (route.stops.size / 2).coerceAtLeast(1)
        val pos = TrainPosition(
            anchorSeq = anchor, nextSeq = anchor + 1, fraction = 0.4f,
            kmAlong = route.stops[anchor - 1].schedKm.toFloat(), status = StopStatus.DEPARTED,
            etaNextMinute = null, delayMinutes = 14, remainingMin = 95,
            provenance = SourceKind.PROVIDER, confidence = TrainPosition.Confidence.MEDIUM, ageSeconds = 60,
        )
        // Two reported cells, so the live columns hold real minutes rather than "—": a column that clips is
        // only interesting when something is in it.
        val per = mapOf(
            route.stops[anchor - 1].code to LiveStopFields(
                actualArrivalMin = 470, actualDepartureMin = 474,
                delayArrivalMin = 12, delayDepartureMin = 12, platform = "3",
            ),
            route.stops[anchor].code to LiveStopFields(
                actualArrivalMin = 505, delayArrivalMin = 14, departed = false,
            ),
        )
        val m = TrackModel.build(
            route, pos, per, nowRailMinute = 480, boardSeq = 1, alightSeq = route.stops.size,
            runEpochDay = RailTime.todayEpochDay(), note = null,
        )
        val shot = Render.render(R.layout.activity_train, widthDp = WIDTH, heightDp = 900f, fontScale = FONT) {
            Render.track(root = it, m = m, pos = pos, now = 480)
            it.findViewById<View>(R.id.sourceCard)?.visibility = View.VISIBLE
        }
        assertHeaderIntact(shot)
        val rows = assertTableIntact(shot)
        assertTrue("only $rows stop rows were laid out at ${WIDTH} dp", rows > 4)
        assertTrue("the delay cell is where a big font should have been caught",
            shot.text(R.id.delay).isNotBlank())
        Render.save("06-411dp-bigfont-track-live", shot.bitmap)
    }

    @Test
    fun offlineTrackingScreenKeepsItsHonestLabelsAtTheLargestFont() {
        // The offline strings are the long ones — "OFFLINE · schedule only", "no live feed", "no live fetch" —
        // and they are the ones a traveller has to be able to read: they are the app saying what it does not
        // know. A cut-off "no live f…" would turn a careful sentence into an unreadable one.
        val route = shippedRoute()
        val pos = TrainPosition(
            anchorSeq = 3, nextSeq = 4, fraction = 0.5f, kmAlong = 60f, status = StopStatus.APPROACHING,
            etaNextMinute = null, delayMinutes = null, remainingMin = null,
            provenance = SourceKind.OFFLINE_SCHEDULE, confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
        )
        val m = TrackModel.build(
            route, pos, emptyMap(), nowRailMinute = 432, boardSeq = 1, alightSeq = route.stops.size,
            runEpochDay = RailTime.todayEpochDay(), note = null,
        )
        val shot = Render.render(R.layout.activity_train, widthDp = WIDTH, heightDp = 900f, fontScale = FONT) {
            Render.track(root = it, m = m, pos = pos, now = 432)
        }
        assertHeaderIntact(shot)
        assertTableIntact(shot)
        assertEquals("an offline screen must still say it has no feed",
            "no live feed", shot.text(R.id.delay))
        assertEquals("and still refuse to call its own data live",
            "no live fetch", shot.text(R.id.age))
        Render.save("07-411dp-bigfont-track-offline", shot.bitmap)
    }

    @Test
    fun searchResultsKeepATrainNameAtTheLargestFont() {
        // Three names chosen to be the awkward ones: a long pair of places, an ALL-CAPS dump with a station
        // code in it, and a "SPECIAL" whose type must not be cut into "Spec…".
        val hits = listOf(
            hit("16999", "KSR BENGALURU - SHIVAMOGGA TOWN EXP", 330, 604, 274, 0x7F),
            hit("12627", "KARNATAKA EXPRESS", 1140, 1980, 842, 0xFF),
            hit("06268", "SBC SMET Passenger SPECIAL", 486, 810, 274, 0x2A),
        )
        val shot = Render.render(R.layout.activity_main, widthDp = WIDTH, fontScale = FONT) { root ->
            root.findViewById<SuggestBox>(R.id.fromBox)?.hint = "Board at — station name or code"
            root.findViewById<SuggestBox>(R.id.toBox)?.hint = "Get off at — station name or code"
            root.findViewById<SuggestBox>(R.id.trainBox)?.hint = "5-digit number or a name fragment"
            root.findViewById<View>(R.id.statusLine)?.visibility = View.GONE
            Render.text(root, R.id.dataChip, DATA_VERSION)
            Render.text(root, R.id.resultsTitle, "3 trains between these stations")
            Render.text(root, R.id.resultsSub, "sorted by departure · 5 ms on device")
            root.findViewById<RecyclerView>(R.id.results)?.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply {
                    nowMinute = 19949L * 1440L + 300L
                    submitList(hits)
                }
                Render.settle(this, hits.size)
            }
        }
        val list = shot.root.findViewById<RecyclerView>(R.id.results)
        assertNotNull("no results list at ${WIDTH} dp", list)
        assertEquals("a result row was lost to the layout", hits.size, list!!.childCount)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta, R.id.hitDep, R.id.hitArr, R.id.hitTag, R.id.hitGo)
                .forEach { Render.assertNothingCut(row.findViewById(it), "result $i · ${name(shot, it)}") }
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta).forEach {
                assertTrue("result $i has a blank ${name(shot, it)}", Render.read(row, it).isNotBlank())
            }
        }
        val names = (0 until list.childCount).map { Render.read(list.getChildAt(it), R.id.hitName) }
        assertEquals("a train name was cut in half, not wrapped: $names", 3, names.count { it.isNotBlank() })
        assertTrue("the ALL-CAPS name should read as a sentence, not a shout: $names",
            names.any { it.contains("Karnataka Express") })
        assertTrue("a terminal code inside a name must stay a code: $names",
            names.any { it.startsWith("SBC SMET") })
        Render.save("08-411dp-bigfont-search", shot.bitmap)
    }

    @Test
    fun settingsScreenPaintsWithoutCuttingALabelAtTheLargestFont() {
        val shot = Render.render(R.layout.activity_settings, widthDp = WIDTH, fontScale = FONT) { root ->
            Render.text(root, R.id.state, "Built-in timetable installed · offline search ready")
            Render.text(root, R.id.about, "OpenTrack India 1.0 · timetable: $DATA_VERSION\n\n" +
                "No accounts, no API keys, no ads, no analytics. Live status is fetched directly from a " +
                "public Indian Railways enquiry endpoint; everything else — station search, the ±24 h " +
                "train list and every timetable — is answered here.")
        }
        listOf(R.id.state, R.id.about).forEach { Render.assertNothingCut(shot.root.findViewById(it), name(shot, it)) }
        assertTrue("settings painted only ${shot.distinct()} colours at ${WIDTH} dp", shot.distinct() > 12)
        Render.save("09-411dp-bigfont-settings", shot.bitmap)
    }

    // ------------------------------------------------------------------ shared checks

    /** Every header cell on the tracking screen, measured rather than eyeballed. */
    private fun assertHeaderIntact(shot: Render.Shot) {
        listOf(R.id.trainNo, R.id.routeLabel, R.id.runDate, R.id.headline, R.id.provenance, R.id.age,
            R.id.delay, R.id.next, R.id.progressLabel, R.id.chartLegend).forEach {
            val value = shot.text(it)
            assertTrue("unfilled or empty label: '${name(shot, it)}' = '$value'",
                value.isNotBlank() && !value.contains("%"))
            Render.assertNothingCut(shot.root.findViewById(it), "${name(shot, it)} = '$value'")
        }
    }

    /** @return the number of rows the list laid out. */
    private fun assertTableIntact(shot: Render.Shot): Int {
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)
        assertNotNull("the stop table did not lay out at ${WIDTH} dp", list)
        for (i in 0 until list!!.childCount) {
            val row = list.getChildAt(i)
            listOf(R.id.stopName, R.id.stopMeta, R.id.schedArr, R.id.schedDep, R.id.liveArr, R.id.liveDep,
                R.id.liveTag).forEach {
                Render.assertNothingCut(row.findViewById(it), "row $i · ${name(shot, it)}")
            }
            assertTrue("row $i has no station name", Render.read(row, R.id.stopName).isNotBlank())
        }
        return list.childCount
    }

    private fun shippedRoute(): TrainRoute {
        val repo = runBlocking { app.repository() }
        assertNotNull("the shipped timetable could not be installed", repo)
        val route = runBlocking { repo!!.route(SHIPPED_TRAIN) }
        assertNotNull("$SHIPPED_TRAIN is missing from the shipped timetable", route)
        return route!!
    }

    private fun hit(no: String, nm: String, dep: Int, arr: Int, km: Int, days: Int) = TrainHit(
        trainNo = no, trainName = nm, trainType = "SUPERFAST_EXPRESS", originDateEpochDay = 19949L,
        boardMinute = dep, alightMinute = arr, fromCode = "SBC", toCode = "SMET", fromSeq = 1, toSeq = 9,
        distanceKm = km, runDays = days,
    )

    private fun name(shot: Render.Shot, id: Int): String =
        try {
            shot.root.resources.getResourceEntryName(id)
        } catch (e: Exception) {
            "0x" + id.toString(16)
        }

    private companion object {
        /** A Pixel 6 / Galaxy S21 class portrait width. */
        const val WIDTH = 411f

        /** The top of AOSP's "Font size" slider. */
        const val FONT = 1.3f

        /** One real train, so the names, kilometres and stop count are the ones a device would show. */
        const val SHIPPED_TRAIN = "16228"

        /** `RailRepository.dataVersion()` over the shipped DB — shared with the 360 dp renders. */
        const val DATA_VERSION = "6,775 trains · timetable 10 Oct 2026"
    }
}
