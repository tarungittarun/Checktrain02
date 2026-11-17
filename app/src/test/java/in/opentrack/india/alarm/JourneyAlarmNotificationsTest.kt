package `in`.opentrack.india.alarm

import android.app.Notification
import org.junit.Assert.assertEquals
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
