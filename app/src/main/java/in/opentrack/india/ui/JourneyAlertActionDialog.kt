package `in`.opentrack.india.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import `in`.opentrack.india.R
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.JourneyAlarmSchedule
import `in`.opentrack.india.alarm.JourneyAlarmSnapshot

/** In-app action window also used by the alert notification's content intent. */
internal object JourneyAlertActionDialog {
    fun show(
        activity: AppCompatActivity,
        snapshot: JourneyAlarmSnapshot,
        onStop: () -> Unit,
        onSnooze: () -> Unit,
    ): AlertDialog {
        val config = snapshot.config
        val message = buildString {
            append(snapshot.status.ifBlank { "The selected station alert is active." })
            append("\n\n")
            append(when (config.feedback) {
                AlertFeedback.NOTIFICATION -> if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
                    "One ping was sent; Snooze sends one more in 2 minutes."
                    else "Ping repeats every ${repeatLabel(config.repeatIntervalSeconds)}."
                AlertFeedback.VIBRATION -> if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
                    "One vibration was sent; Snooze sends one more in 2 minutes."
                    else "Vibration repeats every ${repeatLabel(config.repeatIntervalSeconds)}."
                AlertFeedback.RING_UNTIL_STOPPED -> "The alarm rings until you turn it off; snooze pauses it for 2 minutes."
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Train ${config.trainNo} · ${config.toName}")
            .setMessage(message)
            .setNegativeButton("Turn off") { _, _ -> onStop() }
            .setPositiveButton("Snooze 2 min") { _, _ -> onSnooze() }
            .create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).contentDescription =
            "Turn off · train ${config.trainNo} · alert ${snapshot.id}"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).contentDescription =
            "Snooze 2 min · train ${config.trainNo} · alert ${snapshot.id}"
        if (BatteryMode.enabled(activity.findViewById(android.R.id.content)))
            BatteryMode.paint(dialog.window?.decorView ?: activity.findViewById(android.R.id.content))
        return dialog
    }

    private fun repeatLabel(seconds: Int) = when {
        seconds < 60 -> "$seconds seconds"
        seconds % 60 == 0 -> "${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"
        else -> "$seconds seconds"
    }
}
