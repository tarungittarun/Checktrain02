package `in`.opentrack.india.alarm

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun config(train: String = "12345", from: String = "MYS", to: String = "SBC",
                       trigger: AlertTrigger = AlertTrigger.DESTINATION_REPORT,
                       createdAt: Long = 1_793_000_000_000L) = JourneyAlarmConfig(
        trainNo = train, trainName = "Test Express", fromCode = from,
        toCode = to, toName = "KSR Bengaluru", toSeq = 3,
        trigger = trigger,
        triggerCode = if (trigger == AlertTrigger.PREVIOUS_STOP_REPORT) "KGI" else to,
        triggerName = if (trigger == AlertTrigger.PREVIOUS_STOP_REPORT) "Kengeri" else "KSR Bengaluru",
        triggerSeq = if (trigger == AlertTrigger.PREVIOUS_STOP_REPORT) 2 else 3,
        distanceKm = if (trigger == AlertTrigger.PHONE_DISTANCE) 5 else 0,
        feedback = AlertFeedback.NOTIFICATION,
        intervalMinutes = 2, originEpochDay = 20_751L,
        baselineSeq = 1, baselineEventMinute = 645, createdAtMillis = createdAt,
    )

    @Test fun armedConfigurationAndStatusSurviveStoreRecreation() {
        val expected = config(trigger = AlertTrigger.PREVIOUS_STOP_REPORT).copy(
            feedback = AlertFeedback.RING_UNTIL_STOPPED, intervalMinutes = 5)
        val id = JourneyAlarmStore.arm(context, expected)
        JourneyAlarmStore.update(context, id, AlertState.ARMED,
            "Waiting for a fresh route-matched running-status event", checkedAtMillis = 1_793_000_000_100L)

        val restored = JourneyAlarmStore.find(RuntimeEnvironment.getApplication(), id)
        assertNotNull(restored)
        assertEquals(expected, restored!!.config)
        assertEquals(AlertState.ARMED, restored.state)
        assertEquals("Waiting for a fresh route-matched running-status event", restored.status)
        assertEquals(1_793_000_000_100L, restored.lastCheckedAtMillis)
    }

    @Test fun multipleAlertsForTheSameTrainAndOtherTrainsPersistAndUpdateById() {
        val stationAlert = JourneyAlarmStore.arm(context,
            config(trigger = AlertTrigger.PREVIOUS_STOP_REPORT))
        val distanceAlert = JourneyAlarmStore.arm(context,
            config(trigger = AlertTrigger.PHONE_DISTANCE, createdAt = 1_793_000_000_010L))
        val otherTrainAlert = JourneyAlarmStore.arm(context,
            config(train = "54321", from = "SBC", to = "MYS", createdAt = 1_793_000_000_020L))

        assertNotEquals(stationAlert, distanceAlert)
        assertNotEquals(distanceAlert, otherTrainAlert)
        assertEquals(3, JourneyAlarmStore.readAll(context).size)
        assertEquals(2, JourneyAlarmStore.readAll(context).count { it.config.trainNo == "12345" })

        JourneyAlarmStore.update(context, stationAlert, AlertState.FIRED,
            "Destination report · source claim", evidenceAtMillis = 1_793_000_000_100L)
        JourneyAlarmStore.update(context, otherTrainAlert, AlertState.MISSED,
            "Earlier stop not reported", evidenceAtMillis = 1_793_000_000_200L)
        JourneyAlarmStore.dismiss(context, distanceAlert, "Cancelled by you")

        assertEquals(AlertState.FIRED, JourneyAlarmStore.find(context, stationAlert)?.state)
        assertEquals(AlertState.MISSED, JourneyAlarmStore.find(context, otherTrainAlert)?.state)
        assertEquals(AlertState.DISMISSED, JourneyAlarmStore.find(context, distanceAlert)?.state)
        assertEquals("Cancelled by you", JourneyAlarmStore.find(context, distanceAlert)?.status)
        assertEquals("every terminal alert remains in history", 3, JourneyAlarmStore.readAll(context).size)
    }

    @Test fun leadScheduleSourceAndCheckWarningStateSurviveStoreRecreation() {
        val configured = config().copy(leadSeconds = 137, scheduledMinute = 755,
            journeyEndMinute = 1_500)
        val id = JourneyAlarmStore.arm(context, configured)
        JourneyAlarmStore.update(context, id, AlertState.ARMED, "NTES checked · waiting",
            checkedAtMillis = 1_793_000_000_100L, lastSourceLabel = "NTES running status",
            checkFailed = true)

        val restored = JourneyAlarmStore.find(context, id)!!
        assertEquals(configured, restored.config)
        assertEquals("NTES running status", restored.lastSourceLabel)
        assertTrue(restored.checkFailed)
        assertEquals(1_793_000_000_100L, restored.lastCheckedAtMillis)
        assertTrue(JourneyAlarmStore.updateArmed(context, id, configured,
            "Recovered · waiting", checkFailed = false))
        assertTrue(!JourneyAlarmStore.find(context, id)!!.checkFailed)
    }

    @Test fun scheduledReminderKeepsAlertArmedAndOutageWarningIsOnePerFailureStreak() {
        val created = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(20_751L, 700)
        val configured = config(createdAt = created).copy(
            scheduledMinute = 755, leadSeconds = 60, journeyEndMinute = 1_500)
        val id = JourneyAlarmStore.arm(context, configured)
        val dueAt = JourneyAlarmSchedule.dueAtMillis(configured)!!
        assertTrue(!JourneyAlarmStore.find(context, id)!!.scheduledReminderSent)

        val firstFailure = JourneyAlarmStore.updateArmedCheck(context, id, configured,
            "No usable NTES/WIMT data", dueAt, null, hasUsableSourceData = false)!!
        val repeatedFailure = JourneyAlarmStore.updateArmedCheck(context, id, configured,
            "Still no usable NTES/WIMT data", dueAt + 5_000L, null,
            hasUsableSourceData = false)!!
        assertTrue(firstFailure.outageStarted)
        assertTrue(!repeatedFailure.outageStarted)
        assertTrue(repeatedFailure.snapshot.checkFailed)

        val recovered = JourneyAlarmStore.updateArmedCheck(context, id, configured,
            "Fresh NTES report", dueAt + 10_000L, "NTES running status",
            hasUsableSourceData = true)!!
        assertTrue(recovered.recovered)
        val newFailure = JourneyAlarmStore.updateArmedCheck(context, id, configured,
            "Another continuous outage", dueAt + 15_000L, null,
            hasUsableSourceData = false)!!
        assertTrue(newFailure.outageStarted)

        val reminder = JourneyAlarmStore.markScheduledReminderSent(context, id, configured,
            "Scheduled reminder sent · still monitoring", dueAt + 20_000L)
        assertNotNull(reminder)
        assertTrue(reminder!!.scheduledReminderSent)
        assertNull(JourneyAlarmStore.markScheduledReminderSent(context, id, configured,
            "Duplicate reminder", dueAt + 25_000L))
        val persisted = JourneyAlarmStore.find(context, id)!!
        assertEquals(AlertState.ARMED, persisted.state)
        assertTrue(persisted.scheduledReminderSent)
        assertTrue(persisted.checkFailed)
    }

    @Test fun dueTimeUsesTheSelectedTimetableMinuteAndConfiguredSeconds() {
        val scheduled = config().copy(scheduledMinute = 755, leadSeconds = 137)
        assertEquals(`in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(
            scheduled.originEpochDay, 755) - 137_000L,
            JourneyAlarmSchedule.dueAtMillis(scheduled))
    }

    @Test fun aStalePollCannotResurrectACancelledOrReconfiguredAlert() {
        val configured = config()
        val id = JourneyAlarmStore.arm(context, configured)
        JourneyAlarmStore.dismiss(context, id)
        assertTrue(!JourneyAlarmStore.updateArmed(context, id, configured,
            "late source response", checkFailed = true))
        assertEquals(AlertState.DISMISSED, JourneyAlarmStore.find(context, id)!!.state)

        val secondId = JourneyAlarmStore.arm(context, configured)
        val changed = configured.copy(leadSeconds = 300)
        assertTrue(JourneyAlarmStore.replaceConfig(context, secondId, changed))
        assertTrue(!JourneyAlarmStore.updateArmed(context, secondId, configured,
            "stale settings response"))
        assertEquals(changed, JourneyAlarmStore.find(context, secondId)!!.config)
    }

    @Test fun expiryUsesTheSelectedDestinationRatherThanTheTrainTerminal() {
        val selectedEnd = config().copy(
            createdAtMillis = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(20_751L, 700),
            scheduledMinute = 755, journeyEndMinute = 820)
        assertEquals(`in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(
            selectedEnd.originEpochDay, 820) + JourneyAlarmSchedule.RUN_END_GRACE_MILLIS,
            JourneyAlarmSchedule.expiryAtMillis(selectedEnd))
    }

    @Test fun anAlertSetAfterScheduleGetsAGraceWindowToMonitorTheDelayedStop() {
        val createdAt = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(20_751L, 1_100)
        val delayed = config(createdAt = createdAt).copy(
            scheduledMinute = 755, journeyEndMinute = 820)

        assertEquals(createdAt + JourneyAlarmSchedule.RUN_END_GRACE_MILLIS,
            JourneyAlarmSchedule.expiryAtMillis(delayed))
    }

    @Test fun aSourceAndTimerRaceCanOnlyTransitionAnAlertOnce() {
        val config = config()
        val id = JourneyAlarmStore.arm(context, config)
        assertTrue(JourneyAlarmStore.transitionFromArmed(context, id, config,
            AlertState.FIRED, "Source report", evidenceAtMillis = 1_793_000_000_500L,
            lastSourceLabel = "NTES running status", evidenceSourceLabel = "NTES running status"))
        assertTrue(!JourneyAlarmStore.transitionFromArmed(context, id, config,
            AlertState.FIRED, "Scheduled reminder", evidenceAtMillis = 1_793_000_000_600L))
        val saved = JourneyAlarmStore.find(context, id)!!
        assertEquals("Source report", saved.status)
        assertEquals("NTES running status", saved.evidenceSourceLabel)
    }

    @Test fun legacySingleAlertIsMigratedWithItsStateAndStatus() {
        val originalConfig = config(trigger = AlertTrigger.PREVIOUS_STOP_REPORT)
        JourneyAlarmStore.arm(context, originalConfig)
        val prefs = context.getSharedPreferences(JourneyAlarmStore.PREFS_NAME, 0)
        val stored = JSONArray(prefs.getString("alerts", "[]"))
            .getJSONObject(0).getJSONObject("config").toString()
        prefs.edit().clear()
            .putString("config", stored)
            .putString("state", AlertState.MISSED.name)
            .putString("status", "Older single-alert status")
            .putLong("checkedAt", 1_793_000_000_300L)
            .putLong("evidenceAt", 1_793_000_000_200L)
            .commit()

        val restored = JourneyAlarmStore.read(context)
        assertNotNull(restored)
        assertEquals(originalConfig, restored!!.config)
        assertEquals(AlertState.MISSED, restored.state)
        assertEquals("Older single-alert status", restored.status)
        assertEquals(1_793_000_000_300L, restored.lastCheckedAtMillis)
        assertEquals(1, JourneyAlarmStore.readAll(context).size)
        assertTrue("migration writes the new format", prefs.contains("alerts"))
    }

    @Test fun repeatingAndOneShotAlertTimingSurvivesPersistenceAndSnoozeIsPerAlert() {
        val onceConfig = config().copy(repeatIntervalSeconds = JourneyAlarmSchedule.REPEAT_ONCE)
        val onceId = JourneyAlarmStore.arm(context, onceConfig)
        assertTrue(JourneyAlarmStore.transitionFromArmed(context, onceId, onceConfig,
            AlertState.ALERTING, "One ping sent", nextRepeatAtMillis = 0L))

        val snoozedUntil = 1_793_000_120_000L
        assertTrue(JourneyAlarmStore.updateActiveTiming(context, onceId, onceConfig,
            AlertState.ALERTING, snoozedUntilMillis = snoozedUntil,
            nextRepeatAtMillis = snoozedUntil))
        val restored = JourneyAlarmStore.find(context, onceId)!!
        assertEquals(AlertState.ALERTING, restored.state)
        assertEquals(JourneyAlarmSchedule.REPEAT_ONCE, restored.config.repeatIntervalSeconds)
        assertEquals(snoozedUntil, restored.snoozedUntilMillis)
        assertEquals(snoozedUntil, restored.nextRepeatAtMillis)

        val otherId = JourneyAlarmStore.arm(context, config(train = "54321"))
        JourneyAlarmStore.dismiss(context, onceId, "Alert stopped by you")
        assertEquals(AlertState.DISMISSED, JourneyAlarmStore.find(context, onceId)?.state)
        assertEquals(AlertState.ARMED, JourneyAlarmStore.find(context, otherId)?.state)
    }

    @Test fun terminalHistoryIsBoundedButActiveAlertsAreNeverPruned() {
        repeat(105) { index ->
            val id = JourneyAlarmStore.arm(context, config(createdAt = 1_793_000_000_000L + index))
            JourneyAlarmStore.update(context, id, AlertState.FIRED, "Done $index",
                evidenceAtMillis = 1_793_000_000_000L + index)
        }
        assertEquals(100, JourneyAlarmStore.readAll(context).count {
            it.state != AlertState.ARMED && it.state != AlertState.ALERTING &&
                it.state != AlertState.RINGING
        })
        val active = JourneyAlarmStore.arm(context, config(createdAt = 1_793_000_000_500L))
        assertEquals(101, JourneyAlarmStore.readAll(context).size)
        assertEquals(AlertState.ARMED, JourneyAlarmStore.find(context, active)?.state)
        JourneyAlarmStore.clearHistory(context)
        assertEquals(listOf(active), JourneyAlarmStore.readAll(context).map { it.id })
    }

    @Test fun userCancellationBecomesHistoryAndGlobalResetClearsTheStore() {
        val id = JourneyAlarmStore.arm(context, config())
        JourneyAlarmStore.dismiss(context, id)
        assertEquals(AlertState.DISMISSED, JourneyAlarmStore.find(context, id)?.state)

        JourneyAlarmStore.clear(context)

        assertNull(JourneyAlarmStore.read(context))
        assertTrue(JourneyAlarmStore.readAll(context).isEmpty())
    }
}
