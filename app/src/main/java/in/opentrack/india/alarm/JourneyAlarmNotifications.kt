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
import `in`.opentrack.india.ui.TrainActivity

internal object JourneyAlarmNotifications {
    const val MONITOR_ID = 7201
    const val ALERT_ID = 7202
    const val MONITOR_CHANNEL = "journey_monitor"
    const val NOTIFICATION_CHANNEL = "journey_alert_notification"
    const val VIBRATION_CHANNEL = "journey_alert_vibration"
    const val RING_CHANNEL = "journey_alert_ring"
    const val MISSED_CHANNEL = "journey_alert_missed"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            MONITOR_CHANNEL, "Arrival alert monitoring", NotificationManager.IMPORTANCE_LOW,
        ).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL, "Arrival alert · notification", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "One notification sound when a selected train event is reported"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(
            VIBRATION_CHANNEL, "Arrival alert · vibration", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "One vibration pattern when a selected train event is reported"
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
    }

    fun monitoring(context: Context, snapshot: JourneyAlarmSnapshot): Notification {
        val c = snapshot.config
        val text = snapshot.status.ifBlank {
            "Waiting for a qualifying report at ${c.triggerName} · checks about every ${c.intervalMinutes} min"
        }
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle("Arrival alert · train ${c.trainNo}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openTrain(context, c))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification_train, "Cancel alert",
                serviceAction(context, JourneyAlarmService.ACTION_CANCEL, 7203))
            .build()
    }

    fun ringing(context: Context, config: JourneyAlarmConfig, title: String, detail: String): Notification =
        NotificationCompat.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle("TrackTrain alarm · ${config.trainNo}")
            .setContentText("$title · $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$detail\nRing stops when you tap Stop alarm."))
            .setContentIntent(openTrain(context, config))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification_train, "Stop alarm", serviceAction(context,
                JourneyAlarmService.ACTION_STOP_RINGING, 7204))
            .build()

    fun triggered(context: Context, config: JourneyAlarmConfig, title: String,
                  detail: String, missed: Boolean = false) {
        val channel = if (missed) MISSED_CHANNEL else when (config.feedback) {
            AlertFeedback.NOTIFICATION -> NOTIFICATION_CHANNEL
            AlertFeedback.VIBRATION -> VIBRATION_CHANNEL
            AlertFeedback.RING_UNTIL_STOPPED -> RING_CHANNEL
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle(if (missed) "Arrival alert · not reached in time" else "Train ${config.trainNo} · $title")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openTrain(context, config))
            .setCategory(if (missed) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !missed) {
            when (config.feedback) {
                AlertFeedback.NOTIFICATION -> builder.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                AlertFeedback.VIBRATION -> builder.setVibrate(longArrayOf(0L, 450L, 180L, 450L))
                AlertFeedback.RING_UNTIL_STOPPED -> Unit
            }
        }
        NotificationManagerCompat.from(context).notify(ALERT_ID, builder.build())
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(MONITOR_ID)
        NotificationManagerCompat.from(context).cancel(ALERT_ID)
    }

    private fun openTrain(context: Context, c: JourneyAlarmConfig): PendingIntent {
        val intent = Intent(context, TrainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TrainActivity.EXTRA_TRAIN_NO, c.trainNo)
            putExtra(TrainActivity.EXTRA_FROM, c.fromCode)
            putExtra(TrainActivity.EXTRA_TO, c.toCode)
            putExtra(TrainActivity.EXTRA_ORIGIN_EPOCH_DAY, c.originEpochDay)
        }
        return PendingIntent.getActivity(context, 7205, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, JourneyAlarmService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        else PendingIntent.getService(context, requestCode, intent, flags)
    }
}
