package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** An HTTP 200 alone is not a reported stop. NTES and source-specific live providers may
 * supply run-bound actual clocks only after their response is parsed against the requested train,
 * route and run. The WIMT cache and public web pages remain comparison-only; they are never promoted
 * into the live API source or NTES actuals. */
object SourceEvidencePolicy {
    fun comparisonOnly(source: String?): Boolean =
        source in PublicPageChecks.names || source == WimtCandidate.SOURCE

    fun primaryActuals(source: String?, runIdentified: Boolean, verifiedStopMinutes: Boolean): Boolean =
        runIdentified && verifiedStopMinutes && source in setOf(
            "NTES (official)", TrackingSources.WIMT, "erail", RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "last known",
        )
}
