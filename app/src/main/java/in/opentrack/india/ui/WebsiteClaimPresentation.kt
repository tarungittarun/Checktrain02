package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** A selected website's past cards are usable *comparisons*, not run-verified actuals.
 * Do not treat an answered comparison as a failed HTTP request or a movement fallback. */
object WebsiteClaimPresentation {
    fun selectedSourceAnswered(source: String, state: String?, websiteAnswered: Boolean,
                               hasWebsiteReport: Boolean, hasWebPoint: Boolean): Boolean =
        state == "live" ||
            source == PublicPageChecks.ORG && state == "comparison" &&
                websiteAnswered && hasWebsiteReport ||
            source == WimtCandidate.SOURCE && state == "comparison" && hasWebPoint ||
            // Older candidate-state responses still have to identify their evidence.
            state == "candidate" && (source == PublicPageChecks.ORG &&
                websiteAnswered && hasWebsiteReport ||
                source == WimtCandidate.SOURCE && hasWebPoint)

    /** Printed within the station's rail row, NOT under the ACTUAL column. */
    fun stationClaim(fields: LiveStopFields?): String? {
        if (fields == null) return null
        val arrival = fields.actualArrivalMin?.let { "↘${RailTime.formatRailMinute(it)}" }
        val departure = fields.actualDepartureMin?.let { "↙${RailTime.formatRailMinute(it)}" }
        if (arrival == null && departure == null) return null
        return "Web claim ${listOfNotNull(arrival, departure).joinToString(" ")} · run date inferred"
    }
}
