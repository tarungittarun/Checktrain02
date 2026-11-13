package `in`.opentrack.india.alarm

import android.content.Context
import android.content.SharedPreferences
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
)

/** One active trip alert per device; all state is small, local, and cleared when the user cancels it. */
object JourneyAlarmStore {
    const val PREFS_NAME = "journey_arrival_alert"
    private const val KEY_CONFIG = "config"
    private const val KEY_STATE = "state"
    private const val KEY_STATUS = "status"
    private const val KEY_CHECKED_AT = "checkedAt"
    private const val KEY_EVIDENCE_AT = "evidenceAt"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): JourneyAlarmSnapshot? {
        val p = prefs(context)
        val raw = p.getString(KEY_CONFIG, null) ?: return null
        val config = runCatching { decode(raw) }.getOrNull() ?: return null
        val state = runCatching { AlertState.valueOf(p.getString(KEY_STATE, "ARMED").orEmpty()) }
            .getOrDefault(AlertState.ARMED)
        return JourneyAlarmSnapshot(config, state,
            p.getString(KEY_STATUS, "Waiting for a qualifying report").orEmpty(),
            p.getLong(KEY_CHECKED_AT, 0L), p.getLong(KEY_EVIDENCE_AT, 0L))
    }

    fun arm(context: Context, config: JourneyAlarmConfig) {
        prefs(context).edit()
            .putString(KEY_CONFIG, encode(config))
            .putString(KEY_STATE, AlertState.ARMED.name)
            .putString(KEY_STATUS, "Starting background monitor")
            .putLong(KEY_CHECKED_AT, 0L)
            .putLong(KEY_EVIDENCE_AT, 0L)
            .apply()
    }

    fun update(context: Context, state: AlertState, status: String,
               checkedAtMillis: Long = System.currentTimeMillis(),
               evidenceAtMillis: Long = 0L) {
        prefs(context).edit()
            .putString(KEY_STATE, state.name)
            .putString(KEY_STATUS, status.take(500))
            .putLong(KEY_CHECKED_AT, checkedAtMillis)
            .putLong(KEY_EVIDENCE_AT, evidenceAtMillis)
            .apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    fun register(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregister(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun encode(c: JourneyAlarmConfig): String = JSONObject()
        .put("trainNo", c.trainNo).put("trainName", c.trainName)
        .put("fromCode", c.fromCode).put("toCode", c.toCode).put("toName", c.toName)
        .put("toSeq", c.toSeq).put("trigger", c.trigger.name)
        .put("triggerCode", c.triggerCode).put("triggerName", c.triggerName)
        .put("triggerSeq", c.triggerSeq).put("distanceKm", c.distanceKm)
        .put("feedback", c.feedback.name).put("intervalMinutes", c.intervalMinutes)
        .put("originEpochDay", c.originEpochDay).put("baselineSeq", c.baselineSeq)
        .put("baselineEventMinute", c.baselineEventMinute ?: JSONObject.NULL)
        .put("createdAtMillis", c.createdAtMillis).toString()

    private fun decode(raw: String): JourneyAlarmConfig {
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
