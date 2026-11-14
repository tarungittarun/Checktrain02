package `in`.opentrack.india.ui

import `in`.opentrack.india.alarm.AlertState

/** Text and tap destination for the journey-level Alerts control. */
internal data class JourneyAlertPresentation(
    val label: String,
    val contentDescription: String,
    /** Ringing alerts need an immediate route to their per-alert Stop action. */
    val openAlerts: Boolean,
)

internal fun journeyAlertPresentation(states: Iterable<AlertState>): JourneyAlertPresentation {
    val active = states.filter { it == AlertState.ARMED || it == AlertState.RINGING }
    val ringing = active.count { it == AlertState.RINGING }
    return when {
        ringing > 0 -> JourneyAlertPresentation(
            label = if (ringing == 1) "Ringing" else "Ringing · $ringing",
            contentDescription = "$ringing arrival alarm${if (ringing == 1) " is" else "s are"} " +
                "ringing for this journey; tap to open Alerts and stop each one independently",
            openAlerts = true,
        )
        active.isNotEmpty() -> JourneyAlertPresentation(
            label = if (active.size == 1) "Alert on" else "Alerts · ${active.size}",
            contentDescription = "${active.size} alert${if (active.size == 1) " is" else "s are"} " +
                "monitoring this journey; tap to add another or manage all alerts",
            openAlerts = false,
        )
        else -> JourneyAlertPresentation(
            label = "Alert",
            contentDescription = "Configure an arrival alert for this journey",
            openAlerts = false,
        )
    }
}
