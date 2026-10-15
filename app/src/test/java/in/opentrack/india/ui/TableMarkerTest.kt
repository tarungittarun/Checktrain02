package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableMarkerTest {
    private val day = 19949L
    private fun row(source: TrackModel.MinuteSource, minute: Int?) = TrackModel.Stop(
        seq = 1, code = "TK", name = "TUMKUR", schedArr = 488, schedDep = 491,
        showArr = minute, showDep = minute, delay = null, source = source,
        platform = null, state = TrackModel.State.PASSED, isAnchor = true,
        kmFromOrigin = 0, kmFromBoard = 0, kmRemaining = 0,
        isBoarding = false, isAlighting = false, minutesFromNow = null,
    )

    @Test fun onlyReportedHaltCanCarryAnIcon() {
        val now = RailTime.railMinuteToEpochMillis(day, 500)
        assertNull(TableMarker.atHalt(listOf(row(TrackModel.MinuteSource.SCHEDULE, 490)),
            "TK", day, now))
        assertNull(TableMarker.atHalt(listOf(row(TrackModel.MinuteSource.PROJECTED, 490)),
            "TK", day, now))
        assertNull(TableMarker.atHalt(listOf(row(TrackModel.MinuteSource.REPORTED, 490)),
            "OTHER", day, now))
        assertEquals(false, TableMarker.atHalt(listOf(row(TrackModel.MinuteSource.REPORTED, 500)),
            "TK", day, now)!!.website)
        assertTrue(TableMarker.atHalt(listOf(row(TrackModel.MinuteSource.SITE_REPORTED, 499)),
            "TK", day, now)!!.website)
    }

    @Test fun passageAgeAppearsOnlyFromOneMinuteNotFromPageRefresh() {
        val event = RailTime.railMinuteToEpochMillis(day, 500)
        val row = row(TrackModel.MinuteSource.SITE_REPORTED, 500)
        assertFalse(TableMarker.atHalt(listOf(row), "TK", day, event + 59_999)!!.caption.contains("ago"))
        assertTrue(TableMarker.atHalt(listOf(row), "TK", day, event + 60_000)!!.caption.contains("1m ago"))
        assertFalse(TableMarker.atHalt(listOf(row.copy(showArr = null, showDep = null)),
            "TK", day, event + 360_000)!!.caption.contains("ago"))
    }
}
