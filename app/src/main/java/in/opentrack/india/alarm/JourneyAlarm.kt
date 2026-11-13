package `in`.opentrack.india.alarm

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** What an arrival alert is waiting for. Website past cards and timetable clocks never qualify. */
enum class AlertTrigger { DESTINATION_REPORT, PREVIOUS_STOP_REPORT, PHONE_DISTANCE }

enum class AlertFeedback { NOTIFICATION, VIBRATION, RING_UNTIL_STOPPED }

enum class AlertState { ARMED, FIRED, RINGING, MISSED, DISMISSED }

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
    /** Period for the foreground monitor. The UI offers 1, 2, or 5 minutes. */
    val intervalMinutes: Int,
    val originEpochDay: Long,
    /** Last route stop already reported when the user armed the alert. */
    val baselineSeq: Int,
    val baselineEventMinute: Int?,
    val createdAtMillis: Long,
)

data class JourneyAlarmSnapshot(
    val config: JourneyAlarmConfig,
    val state: AlertState,
    val status: String,
    val lastCheckedAtMillis: Long,
    val evidenceAtMillis: Long,
    /** Stable local ID used to scope updates, cancellation, and notifications. */
    val id: Int = 0,
)

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
        require(config.intervalMinutes in 1..5)
        require(config.trigger != AlertTrigger.PHONE_DISTANCE || config.distanceKm in setOf(2, 5, 10))
        val items = loadLocked(context)
        val id = allocateId(context, items)
        items += JourneyAlarmSnapshot(config, AlertState.ARMED, "Starting background monitor",
            0L, 0L, id)
        saveLocked(context, items, nextId = if (id == Int.MAX_VALUE) FIRST_ID else id + 1)
        id
    }

    fun update(
        context: Context,
        id: Int,
        state: AlertState,
        status: String,
        checkedAtMillis: Long = System.currentTimeMillis(),
        evidenceAtMillis: Long? = null,
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
        )
        saveLocked(context, items)
    }

    /** Compatibility overload for old callers/tests; scoped to the most recent saved alert. */
    fun update(context: Context, state: AlertState, status: String,
               checkedAtMillis: Long = System.currentTimeMillis(),
               evidenceAtMillis: Long? = null) {
        read(context)?.let { update(context, it.id, state, status, checkedAtMillis, evidenceAtMillis) }
    }

    /** User cancellation becomes a visible history entry instead of disappearing. */
    fun dismiss(context: Context, id: Int, reason: String = "Cancelled by you") =
        update(context, id, AlertState.DISMISSED, reason)

    /** Remove a just-created record only when starting its foreground service failed. */
    fun remove(context: Context, id: Int) = synchronized(lock) {
        val items = loadLocked(context).filterNot { it.id == id }
        saveLocked(context, items)
    }

    /** Backwards-compatible explicit reset used by tests and the old global cancel action. */
    fun clear(context: Context) = synchronized(lock) { prefs(context).edit().clear().apply() }

    fun dismissActive(context: Context, reason: String = "Cancelled from alert controls") = synchronized(lock) {
        val items = loadLocked(context).map { snapshot ->
            if (snapshot.state == AlertState.ARMED || snapshot.state == AlertState.RINGING)
                snapshot.copy(state = AlertState.DISMISSED, status = reason.take(500),
                    lastCheckedAtMillis = System.currentTimeMillis())
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
        state == AlertState.ARMED || state == AlertState.RINGING

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

    private fun decodeSnapshot(o: JSONObject): JourneyAlarmSnapshot {
        val config = decodeConfig(o.getJSONObject("config").toString())
        return JourneyAlarmSnapshot(config,
            AlertState.valueOf(o.getString("state")),
            o.optString("status", "Waiting for a qualifying report"),
            o.optLong("checkedAt", 0L), o.optLong("evidenceAt", 0L),
            o.getInt("id").also { require(it >= FIRST_ID) })
    }

    private fun encodeConfig(c: JourneyAlarmConfig): JSONObject = JSONObject()
        .put("trainNo", c.trainNo).put("trainName", c.trainName)
        .put("fromCode", c.fromCode).put("toCode", c.toCode).put("toName", c.toName)
        .put("toSeq", c.toSeq).put("trigger", c.trigger.name)
        .put("triggerCode", c.triggerCode).put("triggerName", c.triggerName)
        .put("triggerSeq", c.triggerSeq).put("distanceKm", c.distanceKm)
        .put("feedback", c.feedback.name).put("intervalMinutes", c.intervalMinutes)
        .put("originEpochDay", c.originEpochDay).put("baselineSeq", c.baselineSeq)
        .put("baselineEventMinute", c.baselineEventMinute ?: JSONObject.NULL)
        .put("createdAtMillis", c.createdAtMillis)

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
            intervalMinutes = o.optInt("intervalMinutes", 1).coerceIn(1, 5),
            originEpochDay = o.getLong("originEpochDay"), baselineSeq = o.optInt("baselineSeq", 0),
            baselineEventMinute = if (o.isNull("baselineEventMinute")) null
                else o.optInt("baselineEventMinute", -1).takeIf { it >= 0 },
            createdAtMillis = o.getLong("createdAtMillis"),
        ).also { c ->
            require(c.trainNo.isNotBlank() && c.fromCode.isNotBlank() && c.toCode.isNotBlank())
            require(c.triggerCode.isNotBlank() && c.triggerSeq > 0 && c.toSeq > 0)
            require(c.originEpochDay > 0L && c.createdAtMillis > 0L && c.baselineSeq >= 0)
            require(c.intervalMinutes in 1..5)
            require(c.trigger != AlertTrigger.PHONE_DISTANCE || c.distanceKm in setOf(2, 5, 10))
        }
    }
}
