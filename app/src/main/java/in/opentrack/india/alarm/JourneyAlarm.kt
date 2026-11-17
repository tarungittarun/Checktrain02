package `in`.opentrack.india.alarm

import android.content.Context
import android.content.SharedPreferences
import `in`.opentrack.india.core.RailTime
import org.json.JSONArray
import org.json.JSONObject

/** What an arrival alert is waiting for. Website past cards and timetable clocks never qualify. */
enum class AlertTrigger { DESTINATION_REPORT, PREVIOUS_STOP_REPORT, PHONE_DISTANCE }

enum class AlertFeedback { NOTIFICATION, VIBRATION, RING_UNTIL_STOPPED }

enum class AlertState { ARMED, FIRED, ALERTING, RINGING, MISSED, DISMISSED }

data class JourneyAlarmConfig(
    val trainNo: String,
    val trainName: String,
    val fromCode: String,
    val toCode: String,
    val toName: String,
    val toSeq: Int,
    val trigger: AlertTrigger,
    val triggerCode: String,
    val triggerName: String,
    val triggerSeq: Int,
    /** Used only by PHONE_DISTANCE, and explicitly measured as straight-line distance. */
    val distanceKm: Int,
    val feedback: AlertFeedback,
    /** Poll interval in minutes. Presets are 2, 5, and 10; custom values are bounded to 1–60. */
    val intervalMinutes: Int,
    /** 0 sends once (a snooze sends one more); otherwise ping/vibration repeats at this interval. */
    val repeatIntervalSeconds: Int = 60,
    val originEpochDay: Long,
    /** Last route stop already reported when the user armed the alert. */
    val baselineSeq: Int,
    val baselineEventMinute: Int?,
    val createdAtMillis: Long,
    /** User-selected lead before the published timetable clock; never presented as a live ETA. */
    val leadSeconds: Int = 120,
    /** Station's published arrival (or departure fallback) minute on the run's rail-time axis. */
    val scheduledMinute: Int? = null,
    /** Selected alert destination's published time, used as a safety cutoff for lingering monitors. */
    val journeyEndMinute: Int? = null,
)

data class JourneyAlarmSnapshot(
    val config: JourneyAlarmConfig,
    val state: AlertState,
    val status: String,
    val lastCheckedAtMillis: Long,
    val evidenceAtMillis: Long,
    /** Stable local ID used to scope updates, cancellation, and notifications. */
    val id: Int = 0,
    /** Last source that actually returned an accepted report for this run, if any. */
    val lastSourceLabel: String? = null,
    /** Source evidence for the terminal event; null for a timetable-only reminder. */
    val evidenceSourceLabel: String? = null,
    /** A silent warning was already shown for this consecutive no-usable-source outage. */
    val checkFailed: Boolean = false,
    /** The one-time scheduled-time status reminder has actually been sent. */
    val scheduledReminderSent: Boolean = false,
    /** A fired alert resumes after this wall-clock time; zero means it is not snoozed. */
    val snoozedUntilMillis: Long = 0L,
    /** Next ping/vibration repeat, or ring resume after snooze; zero when no timer is needed. */
    val nextRepeatAtMillis: Long = 0L,
)

/** Lead timer and conservative end-of-run cutoff shared by the service and UI. */
object JourneyAlarmSchedule {
    const val MAX_LEAD_SECONDS = 7_200
    const val REPEAT_ONCE = 0
    const val MIN_REPEAT_SECONDS = 5
    const val MAX_REPEAT_SECONDS = 3_600
    const val MAX_CHECK_INTERVAL_MINUTES = 60
    const val SNOOZE_MILLIS = 2 * 60_000L
    const val RUN_END_GRACE_MILLIS = 90 * 60_000L

    fun dueAtMillis(config: JourneyAlarmConfig): Long? {
        if (config.trigger == AlertTrigger.PHONE_DISTANCE) return null
        val minute = config.scheduledMinute ?: return null
        return RailTime.railMinuteToEpochMillis(config.originEpochDay, minute) -
            config.leadSeconds * 1_000L
    }

    /** A reminder is due only after its scheduled boundary and only until it is claimed once. */
    fun reminderDue(config: JourneyAlarmConfig, alreadySent: Boolean,
                    checkedAtMillis: Long): Boolean =
        !alreadySent && dueAtMillis(config)?.let { checkedAtMillis >= it } == true

    fun expiryAtMillis(config: JourneyAlarmConfig): Long {
        val minute = config.journeyEndMinute ?: config.scheduledMinute
        val scheduledEnd = if (minute != null)
            RailTime.railMinuteToEpochMillis(config.originEpochDay, minute) + RUN_END_GRACE_MILLIS
        else RailTime.railMinuteToEpochMillis(config.originEpochDay + 2L, 0) +
            RUN_END_GRACE_MILLIS
        // A delayed stop can still be upcoming when the traveller arms the monitor after
        // its timetable clock. Give that newly verified monitor a full grace window too.
        return maxOf(scheduledEnd, config.createdAtMillis + RUN_END_GRACE_MILLIS)
    }
}

/**
 * Device-local multi-alert store. Active alerts and a bounded terminal history share one compact
 * JSON value. The previous one-alert preference keys are lazily migrated on the first read.
 */
object JourneyAlarmStore {
    const val PREFS_NAME = "journey_arrival_alert"
    private const val KEY_ALERTS = "alerts"
    private const val KEY_NEXT_ID = "nextId"
    // Legacy single-alert keys, retained only for migration from prior app versions.
    private const val KEY_CONFIG = "config"
    private const val KEY_STATE = "state"
    private const val KEY_STATUS = "status"
    private const val KEY_CHECKED_AT = "checkedAt"
    private const val KEY_EVIDENCE_AT = "evidenceAt"
    private const val FIRST_ID = 10_000
    private const val HISTORY_LIMIT = 100
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Compatibility view for the former one-alert screen; new UI should use [readAll]. */
    fun read(context: Context): JourneyAlarmSnapshot? = readAll(context).firstOrNull()

    fun readAll(context: Context): List<JourneyAlarmSnapshot> = synchronized(lock) {
        loadLocked(context).sortedByDescending { it.id }
    }

    fun find(context: Context, id: Int): JourneyAlarmSnapshot? =
        readAll(context).firstOrNull { it.id == id }

    /** Append rather than replace: more than one alert may watch the same or another train. */
    fun arm(context: Context, config: JourneyAlarmConfig): Int = synchronized(lock) {
        require(config.trainNo.isNotBlank() && config.fromCode.isNotBlank() && config.toCode.isNotBlank())
        require(config.triggerCode.isNotBlank() && config.triggerSeq > 0 && config.toSeq > 0)
        require(config.originEpochDay > 0L && config.createdAtMillis > 0L && config.baselineSeq >= 0)
        validateConfig(config)
        val items = loadLocked(context)
        val id = allocateId(context, items)
        items += JourneyAlarmSnapshot(config, AlertState.ARMED, "Starting background monitor",
            0L, 0L, id)
        saveLocked(context, items, nextId = if (id == Int.MAX_VALUE) FIRST_ID else id + 1)
        id
    }

    private fun validateConfig(config: JourneyAlarmConfig) {
        require(config.trainNo.isNotBlank() && config.fromCode.isNotBlank() && config.toCode.isNotBlank())
        require(config.triggerCode.isNotBlank() && config.triggerSeq > 0 && config.toSeq > 0)
        require(config.originEpochDay > 0L && config.createdAtMillis > 0L && config.baselineSeq >= 0)
        require(config.intervalMinutes in 1..JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES)
        require(config.repeatIntervalSeconds == JourneyAlarmSchedule.REPEAT_ONCE ||
            config.repeatIntervalSeconds in JourneyAlarmSchedule.MIN_REPEAT_SECONDS..
                JourneyAlarmSchedule.MAX_REPEAT_SECONDS)
        require(config.leadSeconds in 0..JourneyAlarmSchedule.MAX_LEAD_SECONDS)
        require(config.scheduledMinute == null || config.scheduledMinute in 0..4_320)
        require(config.journeyEndMinute == null || config.journeyEndMinute in 0..4_320)
        require(config.trigger != AlertTrigger.PHONE_DISTANCE || config.distanceKm in setOf(2, 5, 10))
    }

    fun update(
        context: Context,
        id: Int,
        state: AlertState,
        status: String,
        checkedAtMillis: Long = System.currentTimeMillis(),
        evidenceAtMillis: Long? = null,
        lastSourceLabel: String? = null,
        evidenceSourceLabel: String? = null,
        checkFailed: Boolean? = null,
    ) = synchronized(lock) {
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized
        val previous = items[index]
        items[index] = previous.copy(
            state = state,
            status = status.take(500),
            lastCheckedAtMillis = checkedAtMillis,
            evidenceAtMillis = evidenceAtMillis ?: previous.evidenceAtMillis,
            lastSourceLabel = lastSourceLabel ?: previous.lastSourceLabel,
            evidenceSourceLabel = evidenceSourceLabel ?: previous.evidenceSourceLabel,
            checkFailed = checkFailed ?: previous.checkFailed,
            snoozedUntilMillis = if (isActive(state)) previous.snoozedUntilMillis else 0L,
            nextRepeatAtMillis = if (isActive(state)) previous.nextRepeatAtMillis else 0L,
        )
        saveLocked(context, items)
    }

    /** A stale poll must not resurrect a cancelled or reconfigured alert as ARMED. */
    fun updateArmed(
        context: Context,
        id: Int,
        expectedConfig: JourneyAlarmConfig,
        status: String,
        checkedAtMillis: Long = System.currentTimeMillis(),
        lastSourceLabel: String? = null,
        checkFailed: Boolean = false,
    ): Boolean = synchronized(lock) {
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val previous = items[index]
        if (previous.state != AlertState.ARMED || previous.config != expectedConfig)
            return@synchronized false
        items[index] = previous.copy(
            status = status.take(500),
            lastCheckedAtMillis = checkedAtMillis,
            lastSourceLabel = lastSourceLabel ?: previous.lastSourceLabel,
            checkFailed = checkFailed,
        )
        saveLocked(context, items)
        true
    }

    data class CheckStatusTransition(
        val snapshot: JourneyAlarmSnapshot,
        val outageStarted: Boolean,
        val recovered: Boolean,
    )

    /** Atomically records one check and reports only the edge into/out of an outage streak. */
    fun updateArmedCheck(
        context: Context,
        id: Int,
        expectedConfig: JourneyAlarmConfig,
        status: String,
        checkedAtMillis: Long,
        lastSourceLabel: String?,
        hasUsableSourceData: Boolean,
    ): CheckStatusTransition? = synchronized(lock) {
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val previous = items[index]
        if (previous.state != AlertState.ARMED || previous.config != expectedConfig ||
            checkedAtMillis < previous.lastCheckedAtMillis) return@synchronized null
        val failed = !hasUsableSourceData
        val updated = previous.copy(
            status = status.take(500),
            lastCheckedAtMillis = checkedAtMillis,
            lastSourceLabel = lastSourceLabel ?: previous.lastSourceLabel,
            checkFailed = failed,
        )
        items[index] = updated
        saveLocked(context, items)
        CheckStatusTransition(updated, outageStarted = failed && !previous.checkFailed,
            recovered = !failed && previous.checkFailed)
    }

    /** Claims the one-time schedule reminder while leaving the live monitor ARMED. */
    fun markScheduledReminderSent(
        context: Context,
        id: Int,
        expectedConfig: JourneyAlarmConfig,
        status: String,
        sentAtMillis: Long,
    ): JourneyAlarmSnapshot? = synchronized(lock) {
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val previous = items[index]
        if (previous.state != AlertState.ARMED || previous.config != expectedConfig ||
            previous.scheduledReminderSent) return@synchronized null
        val updated = previous.copy(
            status = status.take(500),
            lastCheckedAtMillis = maxOf(previous.lastCheckedAtMillis, sentAtMillis),
            scheduledReminderSent = true,
        )
        items[index] = updated
        saveLocked(context, items)
        updated
    }

    /** Compare-and-transition prevents a source result racing the scheduled timer into double alerts. */
    fun transitionFromArmed(
        context: Context,
        id: Int,
        expectedConfig: JourneyAlarmConfig,
        state: AlertState,
        status: String,
        checkedAtMillis: Long = System.currentTimeMillis(),
        evidenceAtMillis: Long? = null,
        lastSourceLabel: String? = null,
        evidenceSourceLabel: String? = null,
        nextRepeatAtMillis: Long = 0L,
    ): Boolean = synchronized(lock) {
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val previous = items[index]
        if (previous.state != AlertState.ARMED || previous.config != expectedConfig)
            return@synchronized false
        items[index] = previous.copy(
            state = state,
            status = status.take(500),
            lastCheckedAtMillis = checkedAtMillis,
            evidenceAtMillis = evidenceAtMillis ?: previous.evidenceAtMillis,
            lastSourceLabel = lastSourceLabel ?: previous.lastSourceLabel,
            evidenceSourceLabel = evidenceSourceLabel,
            checkFailed = false,
            snoozedUntilMillis = 0L,
            nextRepeatAtMillis = nextRepeatAtMillis,
        )
        saveLocked(context, items)
        true
    }

    /** Compare-and-update snooze/repeat deadlines without changing source-check evidence. */
    fun updateActiveTiming(
        context: Context,
        id: Int,
        expectedConfig: JourneyAlarmConfig,
        expectedState: AlertState,
        snoozedUntilMillis: Long,
        nextRepeatAtMillis: Long,
    ): Boolean = synchronized(lock) {
        if (expectedState !in setOf(AlertState.ALERTING, AlertState.RINGING))
            return@synchronized false
        val items = loadLocked(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val previous = items[index]
        if (previous.state != expectedState || previous.config != expectedConfig)
            return@synchronized false
        items[index] = previous.copy(
            snoozedUntilMillis = snoozedUntilMillis.coerceAtLeast(0L),
            nextRepeatAtMillis = nextRepeatAtMillis.coerceAtLeast(0L),
        )
        saveLocked(context, items)
        true
    }

    /** Replace the editable settings for one armed alert without touching other alerts. */
    fun replaceConfig(context: Context, id: Int, config: JourneyAlarmConfig): Boolean =
        synchronized(lock) {
            validateConfig(config)
            val items = loadLocked(context)
            val index = items.indexOfFirst { it.id == id }
            if (index < 0 || items[index].state != AlertState.ARMED) return@synchronized false
            items[index] = items[index].copy(config = config,
                status = "Settings updated · restarting monitor",
                lastCheckedAtMillis = 0L, lastSourceLabel = null, checkFailed = false,
                scheduledReminderSent = false,
                snoozedUntilMillis = 0L, nextRepeatAtMillis = 0L)
            saveLocked(context, items)
            true
        }

    /** Compatibility overload for old callers/tests; scoped to the most recent saved alert. */
    fun update(context: Context, state: AlertState, status: String,
               checkedAtMillis: Long = System.currentTimeMillis(),
               evidenceAtMillis: Long? = null) {
        read(context)?.let { update(context, it.id, state, status, checkedAtMillis, evidenceAtMillis) }
    }

    /** User cancellation becomes a visible history entry instead of disappearing. */
    fun dismiss(context: Context, id: Int, reason: String = "Cancelled by you") =
        update(context, id, AlertState.DISMISSED, reason, checkFailed = false)

    /** Remove a just-created record only when starting its foreground service failed. */
    fun remove(context: Context, id: Int) = synchronized(lock) {
        val items = loadLocked(context).filterNot { it.id == id }
        saveLocked(context, items)
    }

    /** Backwards-compatible explicit reset used by tests and the old global cancel action. */
    fun clear(context: Context) = synchronized(lock) { prefs(context).edit().clear().apply() }

    fun dismissActive(context: Context, reason: String = "Cancelled from alert controls") = synchronized(lock) {
        val items = loadLocked(context).map { snapshot ->
            if (isActive(snapshot.state))
                snapshot.copy(state = AlertState.DISMISSED, status = reason.take(500),
                    lastCheckedAtMillis = System.currentTimeMillis(), checkFailed = false,
                    snoozedUntilMillis = 0L, nextRepeatAtMillis = 0L)
            else snapshot
        }
        saveLocked(context, items)
    }

    fun clearHistory(context: Context) = synchronized(lock) {
        saveLocked(context, loadLocked(context).filter { isActive(it.state) })
    }

    fun register(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregister(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun isActive(state: AlertState): Boolean =
        state == AlertState.ARMED || state == AlertState.ALERTING || state == AlertState.RINGING

    /** Must be called under [lock]. */
    private fun loadLocked(context: Context): MutableList<JourneyAlarmSnapshot> {
        val p = prefs(context)
        val raw = p.getString(KEY_ALERTS, null)
        if (raw != null) {
            val restored = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        runCatching { decodeSnapshot(array.getJSONObject(i)) }
                            .getOrNull()?.let(::add)
                    }
                }.toMutableList()
            }.getOrDefault(mutableListOf())
            return pruneHistory(restored)
        }
        // Preserve the old one-alert record and its state/age exactly once. An explicit
        // empty array means all alerts were removed and must not resurrect the legacy value.
        val legacyRaw = p.getString(KEY_CONFIG, null) ?: return mutableListOf()
        val migrated = runCatching {
            val config = decodeConfig(legacyRaw)
            val state = runCatching { AlertState.valueOf(p.getString(KEY_STATE, "ARMED").orEmpty()) }
                .getOrDefault(AlertState.ARMED)
            JourneyAlarmSnapshot(config, state,
                p.getString(KEY_STATUS, "Waiting for a qualifying report").orEmpty(),
                p.getLong(KEY_CHECKED_AT, 0L), p.getLong(KEY_EVIDENCE_AT, 0L),
                allocateId(context, emptyList()))
        }.getOrNull() ?: return mutableListOf()
        saveLocked(context, mutableListOf(migrated), nextId = migrated.id + 1)
        return mutableListOf(migrated)
    }

    private fun allocateId(context: Context, items: List<JourneyAlarmSnapshot>): Int {
        val p = prefs(context)
        var id = p.getInt(KEY_NEXT_ID, FIRST_ID).coerceAtLeast(FIRST_ID)
        val used = items.asSequence().map { it.id }.toHashSet()
        repeat(used.size + 1) {
            if (id !in used) return id
            id = if (id == Int.MAX_VALUE) FIRST_ID else id + 1
        }
        error("No alert notification IDs are available")
    }

    private fun pruneHistory(items: List<JourneyAlarmSnapshot>): MutableList<JourneyAlarmSnapshot> {
        val active = items.filter { isActive(it.state) }
        val history = items.filterNot { isActive(it.state) }
            .sortedByDescending { maxOf(it.evidenceAtMillis, it.lastCheckedAtMillis, it.config.createdAtMillis) }
            .take(HISTORY_LIMIT)
        return (active + history).toMutableList()
    }

    private fun saveLocked(context: Context, values: List<JourneyAlarmSnapshot>, nextId: Int? = null) {
        val kept = pruneHistory(values)
        val array = JSONArray()
        kept.sortedBy { it.id }.forEach { array.put(encodeSnapshot(it)) }
        val editor = prefs(context).edit().putString(KEY_ALERTS, array.toString())
        nextId?.let { editor.putInt(KEY_NEXT_ID, it.coerceAtLeast(FIRST_ID)) }
        editor.apply()
    }

    private fun encodeSnapshot(snapshot: JourneyAlarmSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.id)
        .put("config", encodeConfig(snapshot.config))
        .put("state", snapshot.state.name)
        .put("status", snapshot.status)
        .put("checkedAt", snapshot.lastCheckedAtMillis)
        .put("evidenceAt", snapshot.evidenceAtMillis)
        .put("lastSource", snapshot.lastSourceLabel ?: JSONObject.NULL)
        .put("evidenceSource", snapshot.evidenceSourceLabel ?: JSONObject.NULL)
        .put("checkFailed", snapshot.checkFailed)
        .put("scheduledReminderSent", snapshot.scheduledReminderSent)
        .put("snoozedUntilMillis", snapshot.snoozedUntilMillis)
        .put("nextRepeatAtMillis", snapshot.nextRepeatAtMillis)

    private fun decodeSnapshot(o: JSONObject): JourneyAlarmSnapshot {
        val config = decodeConfig(o.getJSONObject("config").toString())
        return JourneyAlarmSnapshot(config,
            AlertState.valueOf(o.getString("state")),
            o.optString("status", "Waiting for a qualifying report"),
            o.optLong("checkedAt", 0L), o.optLong("evidenceAt", 0L),
            o.getInt("id").also { require(it >= FIRST_ID) },
            lastSourceLabel = o.optString("lastSource").takeIf { it.isNotBlank() && it != "null" },
            evidenceSourceLabel = o.optString("evidenceSource").takeIf { it.isNotBlank() && it != "null" },
            checkFailed = o.optBoolean("checkFailed", false),
            scheduledReminderSent = o.optBoolean("scheduledReminderSent", false),
            snoozedUntilMillis = o.optLong("snoozedUntilMillis", 0L).coerceAtLeast(0L),
            nextRepeatAtMillis = o.optLong("nextRepeatAtMillis", 0L).coerceAtLeast(0L))
    }

    private fun encodeConfig(c: JourneyAlarmConfig): JSONObject = JSONObject()
        .put("trainNo", c.trainNo).put("trainName", c.trainName)
        .put("fromCode", c.fromCode).put("toCode", c.toCode).put("toName", c.toName)
        .put("toSeq", c.toSeq).put("trigger", c.trigger.name)
        .put("triggerCode", c.triggerCode).put("triggerName", c.triggerName)
        .put("triggerSeq", c.triggerSeq).put("distanceKm", c.distanceKm)
        .put("feedback", c.feedback.name).put("intervalMinutes", c.intervalMinutes)
        .put("repeatIntervalSeconds", c.repeatIntervalSeconds)
        .put("originEpochDay", c.originEpochDay).put("baselineSeq", c.baselineSeq)
        .put("baselineEventMinute", c.baselineEventMinute ?: JSONObject.NULL)
        .put("createdAtMillis", c.createdAtMillis)
        .put("leadSeconds", c.leadSeconds)
        .put("scheduledMinute", c.scheduledMinute ?: JSONObject.NULL)
        .put("journeyEndMinute", c.journeyEndMinute ?: JSONObject.NULL)

    private fun decodeConfig(raw: String): JourneyAlarmConfig {
        val o = JSONObject(raw)
        return JourneyAlarmConfig(
            trainNo = o.getString("trainNo"), trainName = o.optString("trainName"),
            fromCode = o.getString("fromCode"), toCode = o.getString("toCode"),
            toName = o.getString("toName"), toSeq = o.getInt("toSeq"),
            trigger = AlertTrigger.valueOf(o.getString("trigger")),
            triggerCode = o.getString("triggerCode"), triggerName = o.getString("triggerName"),
            triggerSeq = o.getInt("triggerSeq"), distanceKm = o.optInt("distanceKm", 0),
            feedback = AlertFeedback.valueOf(o.getString("feedback")),
            intervalMinutes = o.optInt("intervalMinutes", 2).coerceIn(1,
                JourneyAlarmSchedule.MAX_CHECK_INTERVAL_MINUTES),
            repeatIntervalSeconds = o.optInt("repeatIntervalSeconds", 60).let { value ->
                if (value == JourneyAlarmSchedule.REPEAT_ONCE) value else value.coerceIn(
                    JourneyAlarmSchedule.MIN_REPEAT_SECONDS,
                    JourneyAlarmSchedule.MAX_REPEAT_SECONDS)
            },
            originEpochDay = o.getLong("originEpochDay"), baselineSeq = o.optInt("baselineSeq", 0),
            baselineEventMinute = if (o.isNull("baselineEventMinute")) null
                else o.optInt("baselineEventMinute", -1).takeIf { it >= 0 },
            createdAtMillis = o.getLong("createdAtMillis"),
            leadSeconds = o.optInt("leadSeconds", 120).coerceIn(0, JourneyAlarmSchedule.MAX_LEAD_SECONDS),
            scheduledMinute = if (o.isNull("scheduledMinute")) null
                else o.optInt("scheduledMinute", -1).takeIf { it in 0..4_320 },
            journeyEndMinute = if (o.isNull("journeyEndMinute")) null
                else o.optInt("journeyEndMinute", -1).takeIf { it in 0..4_320 },
        ).also(::validateConfig)
    }
}
