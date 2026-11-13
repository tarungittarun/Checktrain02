package `in`.opentrack.india.alarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JourneyAlarmStoreTest {
    private val context by lazy { RuntimeEnvironment.getApplication() }

    @Before fun clearBefore() = JourneyAlarmStore.clear(context)
    @After fun clearAfter() = JourneyAlarmStore.clear(context)

    @Test fun armedConfigurationAndStatusSurviveStoreRecreation() {
        val config = JourneyAlarmConfig(
            trainNo = "12345", trainName = "Test Express", fromCode = "MYS",
            toCode = "SBC", toName = "KSR Bengaluru", toSeq = 3,
            trigger = AlertTrigger.PREVIOUS_STOP_REPORT, triggerCode = "KGI",
            triggerName = "Kengeri", triggerSeq = 2, distanceKm = 0,
            feedback = AlertFeedback.RING_UNTIL_STOPPED, intervalMinutes = 5,
            originEpochDay = 20_751L, baselineSeq = 1, baselineEventMinute = 645,
            createdAtMillis = 1_793_000_000_000L,
        )
        JourneyAlarmStore.arm(context, config)
        JourneyAlarmStore.update(context, AlertState.ARMED,
            "Waiting for a fresh route-matched running-status event", checkedAtMillis = 1_793_000_000_100L)

        val restored = JourneyAlarmStore.read(RuntimeEnvironment.getApplication())
        assertNotNull(restored)
        assertEquals(config, restored!!.config)
        assertEquals(AlertState.ARMED, restored.state)
        assertEquals("Waiting for a fresh route-matched running-status event", restored.status)
        assertEquals(1_793_000_000_100L, restored.lastCheckedAtMillis)
    }

    @Test fun cancelRemovesThePersistedAlert() {
        val config = JourneyAlarmConfig(
            trainNo = "12345", trainName = "Test Express", fromCode = "MYS",
            toCode = "SBC", toName = "KSR Bengaluru", toSeq = 3,
            trigger = AlertTrigger.DESTINATION_REPORT, triggerCode = "SBC",
            triggerName = "KSR Bengaluru", triggerSeq = 3, distanceKm = 0,
            feedback = AlertFeedback.VIBRATION, intervalMinutes = 2,
            originEpochDay = 20_751L, baselineSeq = 1, baselineEventMinute = null,
            createdAtMillis = 1_793_000_000_000L,
        )
        JourneyAlarmStore.arm(context, config)

        JourneyAlarmStore.clear(context)

        assertNull(JourneyAlarmStore.read(context))
    }
}
