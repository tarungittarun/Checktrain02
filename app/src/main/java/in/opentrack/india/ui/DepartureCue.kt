package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus

/** Website's last-card departure is a revisable suggestion, NEVER an Act cell.
 * Require a separate non-schedule departure, a prior arrival, two full minutes
 * since the suggested departure, and a recent page for the selected origin run.
 * The page's clock establishes publication, not motion. */
object DepartureCue {
    fun websiteSuggestion(report: OrgWebsiteStatus.Report?, originDay: Long,
                          now: Long): Int? {
        if (report == null || report.intermediate != null ||
            now - report.pageUpdatedAt !in 0L..300_000L) return null
        val departure = report.provisionalDeparture ?: return null
        val arrival = report.rows[report.anchorCode]?.actualArrivalMin ?: return null
        if (departure <= arrival) return null
        val stamp = RailTime.railMinuteToEpochMillis(originDay, departure)
        if (report.pageUpdatedAt - stamp < 120_000L || now - stamp !in 0L..600_000L)
            return null
        return departure
    }

    fun between(from: String, firstMapped: String?, nextHalt: String): String =
        "Between ${RowText.of(from)} and ${RowText.of(firstMapped ?: nextHalt)}"

    fun isCue(kind: `in`.opentrack.india.domain.RouteBetween.MarkerKind?): Boolean =
        kind == `in`.opentrack.india.domain.RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE ||
            kind == `in`.opentrack.india.domain.RouteBetween.MarkerKind.SITE_DEPARTURE_SUGGESTION
}
