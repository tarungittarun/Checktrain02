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
        assertTrue(reminder.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            .contains("Monitoring continues"))
        assertEquals(Notification.CATEGORY_REMINDER, reminder.category)
        assertEquals("Train 16228 · Tiptur reported",
            reached.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, reminder.actions.size)
        assertEquals(2, reached.actions.size)
    }
}
