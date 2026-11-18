package `in`.opentrack.india.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JourneyAlarmNotificationsTest {
    @Test fun scheduledReminderAndReachedAlertHaveDistinctMinimalCardsAndControls() {
        val context = RuntimeEnvironment.getApplication()
        val config = JourneyAlarmConfig(
            trainNo = "16228", trainName = "Test Express", fromCode = "TLGP",
            toCode = "TCR", toName = "Tiptur", toSeq = 8,
            trigger = AlertTrigger.DESTINATION_REPORT, triggerCode = "TCR",
            triggerName = "Tiptur", triggerSeq = 8, distanceKm = 0,
            feedback = AlertFeedback.NOTIFICATION, intervalMinutes = 5,
            originEpochDay = 20_751L, baselineSeq = 5, baselineEventMinute = 780,
            createdAtMillis = 1_793_000_000_000L, scheduledMinute = 803,
            journeyEndMinute = 803,
        )
        val armed = JourneyAlarmSnapshot(config, AlertState.ARMED,
            "Scheduled reminder sent · monitoring continues", 1_793_000_000_000L,
            0L, id = 10_001, scheduledReminderSent = true)
        val dueAt = JourneyAlarmSchedule.dueAtMillis(config)!!
        val reminder = JourneyAlarmNotifications.scheduledReminder(context, armed, dueAt)
        val reached = JourneyAlarmNotifications.activeAlert(context,
            armed.copy(state = AlertState.ALERTING,
                status = "Tiptur reported · NTES reports arrival at Tiptur 13:23 IST"),
            soundNow = false)

        assertEquals("Scheduled reminder · Tiptur",
            reminder.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val reminderText = reminder.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(reminderText.contains("Monitoring continues"))
        assertTrue(reminderText.contains("no qualifying arrival report yet"))
        assertTrue(reminderText.contains("Delay is not confirmed"))
        assertTrue(reminder.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            .contains("Checked at"))
        assertEquals(JourneyAlarmNotifications.SCHEDULED_CHANNEL, reminder.channelId)
        assertEquals(Notification.CATEGORY_REMINDER, reminder.category)
        assertEquals("Train 16228 · Tiptur reported",
            reached.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, reminder.actions.size)
        assertEquals(2, reached.actions.size)
    }

    @Test fun scheduledReminderUsesOneSoundOrVibrationAndNeverStartsTheLongRingMode() {
        val context = RuntimeEnvironment.getApplication()
        JourneyAlarmNotifications.ensureChannels(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        val config = JourneyAlarmConfig(
            trainNo = "16228", trainName = "Test Express", fromCode = "TLGP",
            toCode = "TCR", toName = "Tiptur", toSeq = 8,
            trigger = AlertTrigger.DESTINATION_REPORT, triggerCode = "TCR",
            triggerName = "Tiptur", triggerSeq = 8, distanceKm = 0,
            feedback = AlertFeedback.NOTIFICATION, intervalMinutes = 5,
            originEpochDay = 20_751L, baselineSeq = 5, baselineEventMinute = 780,
            createdAtMillis = 1_793_000_000_000L, scheduledMinute = 803,
            journeyEndMinute = 803,
        )
        val snapshot = JourneyAlarmSnapshot(config, AlertState.ARMED, "Monitoring",
            config.createdAtMillis, 0L, id = 10_002)
        val dueAt = JourneyAlarmSchedule.dueAtMillis(config)!!
        val ping = JourneyAlarmNotifications.scheduledReminder(context, snapshot, dueAt)
        val vibration = JourneyAlarmNotifications.scheduledReminder(context,
            snapshot.copy(config = config.copy(feedback = AlertFeedback.VIBRATION)), dueAt)
        val ringModeReminder = JourneyAlarmNotifications.scheduledReminder(context,
            snapshot.copy(config = config.copy(feedback = AlertFeedback.RING_UNTIL_STOPPED)), dueAt)
        val pingChannel = checkNotNull(notificationManager.getNotificationChannel(
            JourneyAlarmNotifications.SCHEDULED_CHANNEL))
        val vibrationChannel = checkNotNull(notificationManager.getNotificationChannel(
            JourneyAlarmNotifications.SCHEDULED_VIBRATION_CHANNEL))

        assertEquals(JourneyAlarmNotifications.SCHEDULED_CHANNEL, ping.channelId)
        assertEquals(JourneyAlarmNotifications.SCHEDULED_VIBRATION_CHANNEL, vibration.channelId)
        assertEquals("ring mode must not start a long ring for an unconfirmed schedule time",
            JourneyAlarmNotifications.SCHEDULED_CHANNEL, ringModeReminder.channelId)
        assertTrue("scheduled transition should make one audible ping", pingChannel.sound != null)
        assertTrue(!pingChannel.shouldVibrate())
        assertNull(vibrationChannel.sound)
        assertTrue("the selected vibration feedback should vibrate once", vibrationChannel.shouldVibrate())
        assertTrue("ring-until-stopped is reserved for a qualifying live arrival",
            ping.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .contains("final arrival alert still requires a fresh station event"))
    }

    @Test fun outageNoticeIsSilentEdgeStatusWithTheCheckTimestamp() {
        val context = RuntimeEnvironment.getApplication()
        val checkedAt = 1_793_000_000_000L
        val config = JourneyAlarmConfig(
            trainNo = "20652", trainName = "Test Express", fromCode = "TLGP",
            toCode = "TKE", toName = "Tarikere Jn", toSeq = 8,
            trigger = AlertTrigger.DESTINATION_REPORT, triggerCode = "TKE",
            triggerName = "Tarikere Jn", triggerSeq = 8, distanceKm = 0,
            feedback = AlertFeedback.NOTIFICATION, intervalMinutes = 5,
            originEpochDay = 20_751L, baselineSeq = 4, baselineEventMinute = 450,
            createdAtMillis = checkedAt, scheduledMinute = 467, journeyEndMinute = 1_100,
        )
        val snapshot = JourneyAlarmSnapshot(config, AlertState.ARMED,
            "No usable live source data · retrying", checkedAt, 0L, id = 10_052,
            checkFailed = true)

        val warning = JourneyAlarmNotifications.checkWarning(context, snapshot,
            "NTES timed out · WIMT .in could not resolve host")
        val timeText = `in`.opentrack.india.core.RailTime.formatClockSeconds(checkedAt)

        assertEquals(JourneyAlarmNotifications.CHECK_WARNING_CHANNEL, warning.channelId)
        assertTrue(warning.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            .contains("checked $timeText IST"))
        assertTrue(warning.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            .contains("Checked at $timeText IST"))
        assertEquals(checkedAt, warning.`when`)
        assertTrue("source-check status must not make an arrival sound", warning.sound == null)
    }
}
