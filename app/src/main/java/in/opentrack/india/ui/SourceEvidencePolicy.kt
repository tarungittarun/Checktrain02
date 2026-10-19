package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** A successful transport is not an admitted train event. The selected source must own
 * the dated, validated minutes that drive the ACTUAL column, delay and train marker.
 * Undated HTML and cache points remain visible only in explicitly labelled comparisons. */
object SourceEvidencePolicy {
    fun comparisonOnly(source: String?): Boolean =
        source in PublicPageChecks.names || source == WimtCandidate.SOURCE

    fun primaryActuals(source: String?, runIdentified: Boolean, verifiedStopMinutes: Boolean): Boolean =
        runIdentified && verifiedStopMinutes && source in setOf(
            "NTES (official)", "erail", RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "last known",
        )
}
