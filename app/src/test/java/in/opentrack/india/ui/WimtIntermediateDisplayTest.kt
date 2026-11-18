package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.remote.WimtLiveProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WimtIntermediateDisplayTest {
    private val route = TrainRoute(
        trainNo = "16581", trainName = "YPR SMET EXP",
        srcCode = "YPR", srcName = "Yesvantpur", dstCode = "SMET", dstName = "Shimoga Town",
        totalKm = 269, totalMin = null, runDays = null, returnNo = null,
        stops = listOf("YPR", "ASK", "DRU", "RRB", "SMET").mapIndexed { index, code ->
            StopRow(index + 1, code, code, null, null, null, null, null, 0,
                index * 50, true)
        },
        segments = emptyList(),
    )
    private val originDay = LocalDate.of(2026, 11, 19).toEpochDay()
    private val askEventAt = RailTime.railMinuteToEpochMillis(originDay, 156)

    private fun report(
        code: String = "BLKR",
        before: String = "ASK",
        after: String = "DRU",
        minute: Int? = 187,
    ) = WimtLiveProvider.WimtIntermediate(
        code = code, name = "Ballekere Halt", precedingHalt = before,
        followingHalt = after, actualMinute = minute, eventMinute = minute,
    )

    @Test fun newerDatedIntermediateEventWinsOnlyAsTheLatestReport() {
        val intermediateAt = RailTime.railMinuteToEpochMillis(originDay, 187)
        val now = intermediateAt + 11 * 60_000L
        val event = WimtIntermediateDisplay.newerEvent(report(), route, originDay,
            askEventAt, now)

        assertEquals("BLKR", event?.code)
        assertEquals("ASK", event?.precedingHalt)
        assertEquals("DRU", event?.followingHalt)
        assertEquals(intermediateAt, event?.eventAtMillis)
        assertEquals(11 * 60_000L, event?.ageMillis)
    }

    @Test fun routeStopsAndNonAdjacentOrOlderClaimsDoNotReplaceTheRouteEvent() {
        val now = RailTime.railMinuteToEpochMillis(originDay, 200)
        assertNull(WimtIntermediateDisplay.newerEvent(report(code = "DRU"), route,
            originDay, askEventAt, now))
        assertNull(WimtIntermediateDisplay.newerEvent(report(before = "YPR", after = "DRU"),
            route, originDay, askEventAt, now))
        assertNull(WimtIntermediateDisplay.newerEvent(report(minute = 150), route,
            originDay, askEventAt, now))
    }

    @Test fun futureIntermediateClockIsUnknownNotARecentEvent() {
        val now = RailTime.railMinuteToEpochMillis(originDay, 180)
        assertNull(WimtIntermediateDisplay.newerEvent(report(minute = 187), route,
            originDay, askEventAt, now))
    }
}
