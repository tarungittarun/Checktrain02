package `in`.opentrack.india.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import `in`.opentrack.india.R
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertState
import `in`.opentrack.india.alarm.AlertTrigger
import `in`.opentrack.india.alarm.JourneyAlarmConfig
import `in`.opentrack.india.alarm.JourneyAlarmStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertsActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = JourneyAlarmStore.clear(context)
    @After fun cleanup() = JourneyAlarmStore.clear(context)

    private fun config(train: String, from: String, to: String, trigger: AlertTrigger,
                       createdAt: Long) = JourneyAlarmConfig(
        trainNo = train, trainName = "Test Express", fromCode = from,
        toCode = to, toName = "Destination", toSeq = 3,
        trigger = trigger,
        triggerCode = if (trigger == AlertTrigger.PHONE_DISTANCE) to else "KGI",
        triggerName = if (trigger == AlertTrigger.PHONE_DISTANCE) "Destination" else "Kengeri",
        triggerSeq = if (trigger == AlertTrigger.PHONE_DISTANCE) 3 else 2,
        distanceKm = if (trigger == AlertTrigger.PHONE_DISTANCE) 5 else 0,
        feedback = AlertFeedback.VIBRATION, intervalMinutes = 5,
        originEpochDay = 20_751, baselineSeq = 1, baselineEventMinute = null,
        createdAtMillis = createdAt,
    )

    private fun textContent(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { textContent(view.getChildAt(it)) }
        else -> ""
    }

    @Test fun alertsScreenSeparatesCurrentAlertsFromRetainedHistory() {
        val active = JourneyAlarmStore.arm(context, config("12345", "MYS", "SBC",
            AlertTrigger.PREVIOUS_STOP_REPORT, 1_793_000_000_000L))
        val past = JourneyAlarmStore.arm(context, config("54321", "SBC", "MYS",
            AlertTrigger.PHONE_DISTANCE, 1_793_000_000_100L))
        JourneyAlarmStore.update(context, past, AlertState.FIRED,
            "Destination report · source claim", evidenceAtMillis = 1_793_000_000_200L)

        val controller = Robolectric.buildActivity(AlertsActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<ViewGroup>(R.id.alertsContainer)
        val rendered = textContent(content)
        assertTrue(rendered.contains("ACTIVE ALERTS · 1"))
        assertTrue(rendered.contains("PAST ALERT HISTORY · 1"))
        assertTrue(rendered.contains("12345"))
        assertTrue(rendered.contains("At preceding station"))
        assertTrue(rendered.contains("54321"))
        assertTrue(rendered.contains("Alert sent"))
        assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(
            R.id.clearHistory).visibility)
        controller.pause().stop().destroy()
        assertEquals(AlertState.ARMED, JourneyAlarmStore.find(context, active)?.state)
    }

    @Test fun alertsIconImmediatelyPrecedesSettingsInHomeHeader() {
        val main = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        val alerts = main.findViewById<android.view.View>(R.id.alerts)
        val settings = main.findViewById<android.view.View>(R.id.settings)
        val header = alerts.parent as ViewGroup
        assertEquals(header.indexOfChild(alerts) + 1, header.indexOfChild(settings))
        assertEquals("Alerts", alerts.contentDescription)
        assertEquals("Settings", settings.contentDescription)
    }
}
