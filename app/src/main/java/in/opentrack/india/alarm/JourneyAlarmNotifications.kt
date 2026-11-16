package `in`.opentrack.india.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.opentrack.india.R
import `in`.opentrack.india.ui.AlertsActivity

internal object JourneyAlarmNotifications {
    /** One foreground-service summary for the monitor; alert notices are ID-scoped. */
    const val MONITOR_ID = 7201
    const val MONITOR_CHANNEL = "journey_monitor"
    const val NOTIFICATION_CHANNEL = "journey_alert_notification"
    const val VIBRATION_CHANNEL = "journey_alert_vibration"
    const val RING_CHANNEL = "journey_alert_ring"
    const val MISSED_CHANNEL = "journey_alert_missed"
    const val CHECK_WARNING_CHANNEL = "journey_alert_check_warning"

    private const val CHECK_WARNING_TAG = "arrival-check-warning"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            MONITOR_CHANNEL, "Arrival alert monitoring", NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL, "Arrival alert · ping", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Ping for an arrival alert; Turn off or Snooze from its notification"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(
            VIBRATION_CHANNEL, "Arrival alert · vibration", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Vibration for an arrival alert; Turn off or Snooze from its notification"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 450L, 180L, 450L)
        })
        manager.createNotificationChannel(NotificationChannel(
            RING_CHANNEL, "Arrival alert · ring until stopped", NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            MISSED_CHANNEL, "Arrival alert · status", NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            CHECK_WARNING_CHANNEL, "Arrival alert · source check status", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent status only when all qualified live-source attempts have no usable data"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun monitoring(context: Context, snapshots: List<JourneyAlarmSnapshot>): Notification {
        val armed = snapshots.count { it.state == AlertState.ARMED }
        val alerting = snapshots.count { it.state == AlertState.ALERTING }
        val ringing = snapshots.count { it.state == AlertState.RINGING }
        val detail = buildString {
            append("${snapshots.size} alert${if (snapshots.size == 1) "" else "s"} active")
            if (armed > 0) append(" · $armed monitoring")
            if (alerting > 0) append(" · $alerting alerting")
            if (ringing > 0) append(" · $ringing ringing")
        }
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle("Arrival alerts · TrackTrain")
            .setContentText(detail.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAlerts(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification_train, "View alerts", openAlerts(context))
            .build()
    }

    fun monitoring(context: Context, snapshot: JourneyAlarmSnapshot): Notification =
        monitoring(context, listOf(snapshot))

    /** One persistent, alert-ID notification for ping, vibration, or ring; repeat calls re-alert only when requested. */
    fun activeAlert(context: Context, snapshot: JourneyAlarmSnapshot, soundNow: Boolean): Notification {
        val config = snapshot.config
        val channel = when (config.feedback) {
            AlertFeedback.NOTIFICATION -> NOTIFICATION_CHANNEL
            AlertFeedback.VIBRATION -> VIBRATION_CHANNEL
            AlertFeedback.RING_UNTIL_STOPPED -> RING_CHANNEL
        }
        val snoozed = snapshot.snoozedUntilMillis > System.currentTimeMillis()
        val title = when {
            config.feedback == AlertFeedback.RING_UNTIL_STOPPED -> "Train ${config.trainNo} · ring alarm"
            config.feedback == AlertFeedback.VIBRATION -> "Train ${config.trainNo} · vibration alert"
            else -> "Train ${config.trainNo} · ping alert"
        }
        val detail = buildString {
            append(snapshot.status)
            if (snoozed) append("\nSnoozed · resumes around ")
                .append(`in`.opentrack.india.core.RailTime.formatClockSeconds(snapshot.snoozedUntilMillis))
                .append(" IST")
            else when (config.feedback) {
                AlertFeedback.NOTIFICATION -> append(
                    if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
                        "\nOne ping sent · Turn off or Snooze 2 min"
                    else "\nRepeats every ${repeatLabel(config.repeatIntervalSeconds)} · Turn off or Snooze 2 min")
                AlertFeedback.VIBRATION -> append(
                    if (config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE)
                        "\nOne vibration sent · Turn off or Snooze 2 min"
                    else "\nRepeats every ${repeatLabel(config.repeatIntervalSeconds)} · Turn off or Snooze 2 min")
                AlertFeedback.RING_UNTIL_STOPPED -> append("\nRings until turned off · Turn off or Snooze 2 min")
            }
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle(title)
            .setContentText(snapshot.status.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAlertsForAlert(context, snapshot.id))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!soundNow)
            .setShowWhen(true)
            .setWhen(snapshot.evidenceAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            .addAction(R.drawable.ic_notification_train, "Turn off", serviceAction(context,
                JourneyAlarmService.ACTION_STOP_ALERT, snapshot.id, snapshot.id))
            .addAction(R.drawable.ic_notification_train, "Snooze 2 min", serviceAction(context,
                JourneyAlarmService.ACTION_SNOOZE, snapshot.id, snapshot.id))
        if (!soundNow || config.feedback == AlertFeedback.RING_UNTIL_STOPPED || snoozed)
            builder.setSilent(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && soundNow && !snoozed) {
            when (config.feedback) {
                AlertFeedback.NOTIFICATION -> builder.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                AlertFeedback.VIBRATION -> builder.setVibrate(longArrayOf(0L, 450L, 180L, 450L))
                AlertFeedback.RING_UNTIL_STOPPED -> Unit
            }
        }
        return builder.build()
    }

    /** Terminal missed notices are quiet status, never an arrival sound. */
    fun triggered(context: Context, snapshot: JourneyAlarmSnapshot, title: String,
                  detail: String, missed: Boolean = false) {
        val notification = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle(if (missed) "Arrival alert · not reported in time" else "Train ${snapshot.config.trainNo} · $title")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAlerts(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        NotificationManagerCompat.from(context).notify(snapshot.id, notification)
    }

    /** One silent, ID/tag-keyed notice per consecutive no-usable-source outage. */
    fun checkWarning(context: Context, snapshot: JourneyAlarmSnapshot, detail: String) {
        val config = snapshot.config
        val message = detail.take(500)
        val notification = NotificationCompat.Builder(context, CHECK_WARNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle("Arrival alert check issue · train ${config.trainNo}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$message\nThe alert remains armed. TrackTrain will retry at its configured interval."))
            .setContentIntent(openAlerts(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        NotificationManagerCompat.from(context).notify(CHECK_WARNING_TAG, snapshot.id, notification)
    }

    fun cancelCheckWarning(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(CHECK_WARNING_TAG, id)
    }

    fun cancelAlert(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
        cancelCheckWarning(context, id)
    }

    fun cancelMonitor(context: Context) {
        NotificationManagerCompat.from(context).cancel(MONITOR_ID)
    }

    /** Legacy/global cancel helper. */
    fun cancel(context: Context) {
        JourneyAlarmStore.readAll(context).forEach { cancelAlert(context, it.id) }
        cancelMonitor(context)
    }

    private fun openAlerts(context: Context): PendingIntent {
        val intent = Intent(context, AlertsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 7200, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAlertsForAlert(context: Context, alertId: Int): PendingIntent {
        val intent = Intent(context, AlertsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlertsActivity.EXTRA_ALERT_ID, alertId)
        }
        return PendingIntent.getActivity(context, alertId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceAction(context: Context, action: String, alertId: Int, requestCode: Int): PendingIntent {
        val intent = Intent(context, JourneyAlarmService::class.java).setAction(action)
            .putExtra(JourneyAlarmService.EXTRA_ALERT_ID, alertId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        else PendingIntent.getService(context, requestCode, intent, flags)
    }

    private fun repeatLabel(seconds: Int): String = when {
        seconds == JourneyAlarmSchedule.REPEAT_ONCE -> "once"
        seconds < 60 -> "$seconds seconds"
        seconds % 60 == 0 -> "${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"
        else -> "$seconds seconds"
    }
}
