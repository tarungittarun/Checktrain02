package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.ui.LiveViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The columns are fed by the WITNESS, not by the official chain - the gate paste #28 caught ("the
 * mirror answered while the screen said schedule only") and paste #29 proved twice more. These tests
 * pin what may print as Act and what must stay Est, because under-claiming beats fabricating.
 */
class MirrorOverlayTest {

    private fun stop(seq: Int, code: String, arr: Int?, dep: Int?) = StopRow(
        seq = seq, code = code, name = code, state = null, lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = 0, schedKm = (seq - 1) * 10, isHalt = true,
    )

    private val route = TrainRoute(
        trainNo = "16228", trainName = "TLGP MYS EXP",
        srcCode = "AAA", srcName = "AAA", dstCode = "CCC", dstName = "CCC",
        totalKm = 20, totalMin = 200, runDays = 0x7F, returnNo = null,
        stops = listOf(
            stop(1, "AAA", null, 600),
            stop(2, "BBB", 700, 705),
            stop(3, "CCC", 800, null),
        ),
        segments = emptyList(),
    )

    private fun pos() = TrainPosition(
        anchorSeq = 0, nextSeq = 0, fraction = 0f, kmAlong = 0f, status = StopStatus.UPCOMING,
        etaNextMinute = null, delayMinutes = null, remainingMin = null,
        provenance = SourceKind.OFFLINE_SCHEDULE,
        confidence = TrainPosition.Confidence.LOW, ageSeconds = 0L,
    )

    private fun model(per: Map<String, LiveStopFields> = emptyMap()) = TrackModel.build(
        route, pos(), per, nowRailMinute = 730, boardSeq = 1, alightSeq = 3,
        runEpochDay = 0L, note = null,
    )

    @Test
    fun coveredMinutePrintsAsActWithTheScreensArithmetic() {
        // The device clock has passed the witnessed minute; the displayed timetable is the baseline.
        val stops = listOf(RunningStatusMirror.Stop("BBB", 690, 695, 700, 735))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 740)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
        assertEquals(735, bbb.showDep)
        assertEquals("delay runs against the DISPLAYED schedule (705), not the mirror's pair", 30, bbb.delay)
        val verdict = LiveViewModel.mirrorVerdict(out)
        assertNotNull(verdict)
        assertTrue(
            "verdict must name the halt and the slip: '${verdict!!.first}'",
            verdict.first.contains("BBB") && verdict.first.contains("30 min late"),
        )
        assertEquals(30, verdict.second)
    }

    @Test
    fun aScheduleEqualMinuteIsActOnceCoveredByTheDeviceClock() {
        // R48: clock coverage, not comparison to the mirror's schedule, decides Act.
        val stops = listOf(RunningStatusMirror.Stop("BBB", 700, 705, 700, 705))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 730)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
        assertEquals(705, bbb.showDep)
        assertNotNull(LiveViewModel.mirrorVerdict(out))
    }

    @Test
    fun aFutureWitnessMinuteIsEstFromDisplayedScheduleAndCoveredDelay() {
        val stops = listOf(
            RunningStatusMirror.Stop("AAA", null, 600, null, 610),
            RunningStatusMirror.Stop("BBB", 700, 705, 770, 775),
        )
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 690)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.PROJECTED, bbb.source)
        assertEquals(715, bbb.showDep) // 705 + 10, NOT the future witness's 775
    }

    @Test
    fun haltsTheWitnessDoesNotCarryKeepTheirTimetableRowUntouched() {
        val stops = listOf(RunningStatusMirror.Stop("BBB", 700, 705, 700, 735))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 730)
        val aaa = out.rows.first { it.code == "AAA" }
        assertEquals(TrackModel.MinuteSource.SCHEDULE, aaa.source)
        assertNull(aaa.showDep)
        val ccc = out.rows.first { it.code == "CCC" }
        assertEquals(TrackModel.MinuteSource.PROJECTED, ccc.source)
        assertEquals(800, ccc.showArr)
    }

    @Test
    fun theDayWrapKeepsTheSlipInsideTheHonestBand() {
        // AAA dep scheduled 600; mirror reports 00:05 next day. Whatever the fold, the slip the
        // screen computes must stay inside the wrap-safe +/-720 band or the colour lies.
        val stops = listOf(RunningStatusMirror.Stop("AAA", null, 600, null, 1445))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 1500)
        val aaa = out.rows.first { it.code == "AAA" }
        assertTrue("slip must stay in the wrap-safe band: ${aaa.delay}", aaa.delay!! in -720..720)
    }

    @Test
    fun extensionHaltWithNoScheduleShowsWitnessAsEstimate() {
        val ext = model().copy(rows = model().rows + model().rows.last().copy(
            code = "EXT", schedArr = null, schedDep = null, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE,
        ))
        val out = LiveViewModel.overlayActuals(
            ext, listOf(RunningStatusMirror.Stop("EXT", null, null, 900, null)), 730,
        )
        assertEquals(TrackModel.MinuteSource.PROJECTED, out.rows.last().source)
        assertEquals(900, out.rows.last().showArr)
    }

    @Test
    fun theConfirmTktWalkFeedsTheColumnsFromTheSameCaptureTheLinesComeFrom() {
        val text = RunningStatusMirror.stripTags(
            java.io.File("src/test/resources/captures/confirmtkt-20651-2026-10-12.txt").readText(),
        )
        val stops = listOf(
            "SBC" to "KSR BENGALURU", "YPR" to "YASVANTPUR JN", "TK" to "Tumakuru",
            "ASK" to "Arsikere Jn", "SMET" to "Shivamogga Town", "TLGP" to "Talguppa",
        )
        val lines = ConfirmTktMirror.parse(text, stops)
        val structured = ConfirmTktMirror.stopsOf(text, stops)
        assertEquals(
            "same halts on both shapes",
            lines.keys.toList(),
            structured.map { it.stationCode },
        )
        assertTrue("fixture must carry halts", structured.isNotEmpty())
        for (st in structured) {
            val line = lines[st.stationCode] ?: continue
            if (st.actArr != null) assertTrue("arr must be in its line: '$line'", line.contains("arr"))
            if (st.actDep != null) assertTrue("dep must be in its line: '$line'", line.contains("dep"))
        }
    }

    @Test
    fun anErailReportedRowKeepsItsLiveFieldsWhenTheMirrorIsSilentForIt() {
        // erail's per-stop minute for BBB must survive an overlay that says nothing about BBB.
        val per = mapOf("BBB" to LiveStopFields(actualArrivalMin = 702, actualDepartureMin = 712))
        val stops = listOf(RunningStatusMirror.Stop("AAA", null, 600, null, 604))
        val out = LiveViewModel.overlayActuals(model(per), stops, nowRail = 730)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals("erail's minute survives", 712, bbb.showDep)
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
    }
}
