package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DepartureCueTest {
    private val day = LocalDate.of(2026, 10, 19).toEpochDay()
    private val departure = 20 * 60 + 21
    private val checked = RailTime.railMinuteToEpochMillis(day, 20 * 60 + 26) + 35_000L
    private val report = OrgWebsiteStatus.Report(
        rows = mapOf("RRB" to LiveStopFields(actualArrivalMin = 20 * 60 + 15)),
        anchorCode = "RRB", departed = false, eventMinute = 20 * 60 + 15,
        pageUpdatedAt = checked, provisionalDeparture = departure)

    @Test fun websiteSuggestionIsDistinctFromAnActualAndNamesFirstMappedPoint() {
        assertEquals(departure, DepartureCue.websiteSuggestion(report, day, checked + 1_000L))
        assertEquals("Between Birur Jn and Sivapur",
            DepartureCue.between("Birur Jn", "Sivapur", "Tarikere Jn"))
        assertEquals("Between Birur Jn and Tarikere Jn",
            DepartureCue.between("Birur Jn", null, "Tarikere Jn"))
    }

    @Test fun noMovementFromArrivalFutureReversalPageClockOrStaleSuggestion() {
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = null), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = 20 * 60 + 14), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = 20 * 60 + 25), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(pageUpdatedAt = checked - 301_000), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report, day, checked + 601_000))
        assertNull(DepartureCue.websiteSuggestion(report.copy(intermediate =
            OrgWebsiteStatus.Intermediate("SPV", "Sivapur", "RRB", "TKE", 215, 1216)), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(rows = emptyMap()), day, checked))
    }
}
