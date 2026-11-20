package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SourceEventSummaryTest {
    private val originDay = LocalDate.of(2026, 11, 19).toEpochDay()

    private fun reported(
        code: String,
        name: String,
        arrival: Int?,
        departure: Int?,
        source: TrackModel.MinuteSource = TrackModel.MinuteSource.REPORTED,
    ) = TrackModel.Stop(
        seq = 1, code = code, name = name,
        schedArr = null, schedDep = null,
        showArr = arrival, showDep = departure,
        delay = null, source = source, platform = null,
        state = TrackModel.State.PASSED, isAnchor = false,
        kmFromOrigin = 0, kmFromBoard = 0, kmRemaining = 0,
        isBoarding = false, isAlighting = false, minutesFromNow = null,
    )

    @Test fun firstGlanceUsesTheLatestStationEventNotTheFreshProviderStamp() {
        val eventAt = RailTime.railMinuteToEpochMillis(originDay, 5 * 60 + 39)
        val now = eventAt + 14 * 60_000L
        val event = SourceEventSummary.latestReported(
            listOf(reported("BDVT", "Bhadravati", 5 * 60 + 39, null)), originDay, now)

        assertNotNull(event)
        assertEquals("BDVT", event?.code)
        assertEquals(5 * 60 + 39, event?.minute)
        assertEquals(14 * 60_000L, event?.ageMillis)
        val line = SourceEventSummary.line("WIMT .in", event, now, "feed stamp fresh, not event")
        assertTrue(line.contains("BDVT arrival 05:39"))
        assertTrue(line.contains("14m old"))
        assertTrue(line.contains("feed stamp fresh, not event"))
        assertTrue(line.endsWith("feed stamp fresh, not event"))
        assertFalse("the request stamp must never replace the event clock", line.contains("05:53"))
    }

    @Test fun latestDepartureWinsAndScheduleOrFutureRowsAreNotPresentedAsEvents() {
        val eventAt = RailTime.railMinuteToEpochMillis(originDay, 5 * 60 + 35)
        val now = eventAt + 18 * 60_000L
        val event = SourceEventSummary.latestReported(listOf(
            reported("BDVT", "Bhadravati", 5 * 60 + 33, 5 * 60 + 35),
            reported("SMET", "Shimoga Town", 6 * 60, null, TrackModel.MinuteSource.SCHEDULE),
            reported("TLGP", "Talguppa", null, 7 * 60),
        ), originDay, now)

        assertEquals("BDVT", event?.code)
        assertEquals("departure", event?.kind)
        assertEquals(18 * 60_000L, event?.ageMillis)
        assertNull(SourceEventSummary.latestReported(
            listOf(reported("BDVT", "Bhadravati", 6 * 60, null)), originDay,
            RailTime.railMinuteToEpochMillis(originDay, 5 * 60 + 53)))
    }
}
