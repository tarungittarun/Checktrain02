package `in`.opentrack.india.data

import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsAlertDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("opentrack", 0)

    @Before fun resetAlertDefaults() {
        prefs.edit().remove("defaultAlertTrigger").remove("defaultAlertFeedback")
            .remove("defaultAlertInterval").remove("defaultAlertDistance").remove("batteryMode").commit()
    }

    @After fun restoreBatteryMode() { Prefs(context).batteryMode = false }

    @Test fun alertDefaultsPersistAndBatteryModePrefersLowerWorkWhenUnset() {
        val defaults = Prefs(context)
        assertEquals(AlertTrigger.DESTINATION_REPORT, defaults.defaultAlertTrigger)
        assertEquals(AlertFeedback.NOTIFICATION, defaults.defaultAlertFeedback)
        assertEquals(2, defaults.defaultAlertIntervalMinutes)
        assertEquals(5, defaults.defaultAlertDistanceKm)

        defaults.defaultAlertTrigger = AlertTrigger.PHONE_DISTANCE
        defaults.defaultAlertFeedback = AlertFeedback.RING_UNTIL_STOPPED
        defaults.defaultAlertIntervalMinutes = 1
        defaults.defaultAlertDistanceKm = 10
        val restored = Prefs(context)
        assertEquals(AlertTrigger.PHONE_DISTANCE, restored.defaultAlertTrigger)
        assertEquals(AlertFeedback.RING_UNTIL_STOPPED, restored.defaultAlertFeedback)
        assertEquals(1, restored.defaultAlertIntervalMinutes)
        assertEquals(10, restored.defaultAlertDistanceKm)

        prefs.edit().remove("defaultAlertInterval").commit()
        restored.batteryMode = true
        assertEquals("Battery-mode default reduces periodic work unless the traveller chose otherwise",
            5, restored.defaultAlertIntervalMinutes)
        restored.defaultAlertIntervalMinutes = 2
        assertEquals(2, Prefs(context).defaultAlertIntervalMinutes)
    }
}
