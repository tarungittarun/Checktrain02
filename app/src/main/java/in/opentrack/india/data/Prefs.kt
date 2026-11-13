package `in`.opentrack.india.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import `in`.opentrack.india.alarm.AlertFeedback
import `in`.opentrack.india.alarm.AlertTrigger

/**
 * All persisted user state, in one file.
 *
 * Two reasons this is hand-rolled on SharedPreferences rather than DataStore: DataStore would add a
 * kotlinx-serialization/protobuf dependency to a 5 MB app for four scalars, and SharedPreferences'
 * synchronous read is genuinely what we want here — the values are consulted on the UI thread when a
 * screen binds, and they are small.
 *
 * The *reason these specific keys exist* is the resilience plan: `overrideUrl` and `columnHint` are
 * what let a user keep a working app after an upstream format or policy change, with no new release.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("opentrack", Context.MODE_PRIVATE)

    /** Stored separately from the installed Android theme so Follow system reacts to OS changes. */
    var appearance: Int
        get() = sp.getInt("appearance", 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt("appearance", value.coerceIn(0, 2)).apply()

    fun nightMode(): Int = if (batteryMode) AppCompatDelegate.MODE_NIGHT_YES else when (appearance) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Independent of Appearance. OLED-only visuals and reduced background work; off by default. */
    var batteryMode: Boolean
        get() = sp.getBoolean(KEY_BATTERY_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_MODE, value).apply()

    var overrideUrl: String
        get() = sp.getString(KEY_OVERRIDE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OVERRIDE, v).apply()

    /** Positional field map for the live feed, e.g. `1:code,2:name,3:sa,4:sd,5:aa,6:ad`. */
    var columnHint: String
        get() = sp.getString(KEY_COLUMN_HINT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_COLUMN_HINT, v).apply()

    /**
     * Whether the user has asked for a diagnostic log. Persisted, because a log left on across a restart is
     * how a bug that only happens on the first request after boot ever gets caught.
     */
    var diaryEnabled: Boolean
        get() = sp.getBoolean(KEY_DIARY, false)
        set(v) = sp.edit().putBoolean(KEY_DIARY, v).apply()

    /** The mirror's last known actuals, one JSON object keyed by train number - stale-while-revalidate. */
    var mirrorCache: String
        get() = sp.getString(KEY_MIRROR_CACHE, "{}").orEmpty().ifBlank { "{}" }
        set(v) = sp.edit().putString(KEY_MIRROR_CACHE, v).apply()

    /** The full-route halt list per train (erail's page), cached for a day - same SWR discipline. */
    var erailRouteCache: String
        get() = sp.getString(KEY_ERAIL_ROUTE_CACHE, "{}").orEmpty().ifBlank { "{}" }
        set(v) = sp.edit().putString(KEY_ERAIL_ROUTE_CACHE, v).apply()

    var lastFromCode: String
        get() = sp.getString(KEY_LAST_FROM, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_FROM, v).apply()

    var lastToCode: String
        get() = sp.getString(KEY_LAST_TO, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TO, v).apply()

    /** The in-progress search form, separate from completed recent journeys. */
    var hasSearchDraft: Boolean
        get() = sp.getBoolean(KEY_HAS_SEARCH_DRAFT, false)
        set(v) = sp.edit().putBoolean(KEY_HAS_SEARCH_DRAFT, v).apply()
    var searchFromText: String
        get() = sp.getString(KEY_SEARCH_FROM_TEXT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_FROM_TEXT, v).apply()
    var searchFromResolved: String
        get() = sp.getString(KEY_SEARCH_FROM_RESOLVED, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_FROM_RESOLVED, v).apply()
    var searchToText: String
        get() = sp.getString(KEY_SEARCH_TO_TEXT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_TO_TEXT, v).apply()
    var searchToResolved: String
        get() = sp.getString(KEY_SEARCH_TO_RESOLVED, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_TO_RESOLVED, v).apply()
    var searchTrainText: String
        get() = sp.getString(KEY_SEARCH_TRAIN_TEXT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_TRAIN_TEXT, v).apply()
    var searchTrainResolved: String
        get() = sp.getString(KEY_SEARCH_TRAIN_RESOLVED, "").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_TRAIN_RESOLVED, v).apply()
    var searchMode: String
        get() = sp.getString(KEY_SEARCH_MODE, "STATIONS").orEmpty()
        set(v) = sp.edit().putString(KEY_SEARCH_MODE, v).apply()
    var searchEpochDay: Long
        get() = sp.getLong(KEY_SEARCH_EPOCH_DAY, 0L)
        set(v) = sp.edit().putLong(KEY_SEARCH_EPOCH_DAY, v).apply()
    var searchMinuteOfDay: Int
        get() = sp.getInt(KEY_SEARCH_MINUTE, -1)
        set(v) = sp.edit().putInt(KEY_SEARCH_MINUTE, v).apply()

    fun saveSearchDraft(fromText: String, fromResolved: String?, toText: String,
                        toResolved: String?, trainText: String, trainResolved: String?,
                        mode: String, epochDay: Long, minuteOfDay: Int) = sp.edit()
        .putBoolean(KEY_HAS_SEARCH_DRAFT, true)
        .putString(KEY_SEARCH_FROM_TEXT, fromText)
        .putString(KEY_SEARCH_FROM_RESOLVED, fromResolved.orEmpty())
        .putString(KEY_SEARCH_TO_TEXT, toText)
        .putString(KEY_SEARCH_TO_RESOLVED, toResolved.orEmpty())
        .putString(KEY_SEARCH_TRAIN_TEXT, trainText)
        .putString(KEY_SEARCH_TRAIN_RESOLVED, trainResolved.orEmpty())
        .putString(KEY_SEARCH_MODE, mode)
        .putLong(KEY_SEARCH_EPOCH_DAY, epochDay)
        .putInt(KEY_SEARCH_MINUTE, minuteOfDay)
        .apply()

    /** Most recently opened train, as `trainNo|fromCode|toCode|originEpochDay`. */
    var lastTrain: String
        get() = sp.getString(KEY_LAST_TRAIN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TRAIN, v).apply()

    /** True once the user has seen (or dismissed) the first-launch data screen. */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /**
     * ADR-006 traveller reports. Shipped off-by-default per the ADR; flipped ON-by-default the same
     * day on the acceptance authority's explicit direction ("Priority is to get actual arrival and
     * departure time. Work for that, do anything") - reading is passive and renders nothing until a
     * real report exists, and the toggle still governs the publish controls outright.
     */
    var crowdReports: Boolean
        get() = sp.getBoolean(KEY_CROWD, true)
        set(v) = sp.edit().putBoolean(KEY_CROWD, v).apply()

    /** Default for the tracking screen; the train screen may change it as well. */
    var defaultTrackingSource: String
        get() = sp.getString(KEY_TRACK_SOURCE, "NTES (official)").orEmpty()
        set(v) = sp.edit().putString(KEY_TRACK_SOURCE, v).apply()

    /** Persistent starting choices; every journey can override these before it is armed. */
    var defaultAlertTrigger: AlertTrigger
        get() = runCatching { AlertTrigger.valueOf(sp.getString(KEY_ALERT_TRIGGER, "DESTINATION_REPORT").orEmpty()) }
            .getOrDefault(AlertTrigger.DESTINATION_REPORT)
        set(value) = sp.edit().putString(KEY_ALERT_TRIGGER, value.name).apply()

    var defaultAlertFeedback: AlertFeedback
        get() = runCatching { AlertFeedback.valueOf(sp.getString(KEY_ALERT_FEEDBACK, "NOTIFICATION").orEmpty()) }
            .getOrDefault(AlertFeedback.NOTIFICATION)
        set(value) = sp.edit().putString(KEY_ALERT_FEEDBACK, value.name).apply()

    var defaultAlertIntervalMinutes: Int
        get() {
            val fallback = if (batteryMode) 5 else 2
            return sp.getInt(KEY_ALERT_INTERVAL, fallback).takeIf { it in setOf(1, 2, 5) } ?: fallback
        }
        set(value) {
            val fallback = if (batteryMode) 5 else 2
            sp.edit().putInt(KEY_ALERT_INTERVAL,
                value.takeIf { it in setOf(1, 2, 5) } ?: fallback).apply()
        }

    var defaultAlertDistanceKm: Int
        get() = sp.getInt(KEY_ALERT_DISTANCE, 5).takeIf { it in setOf(2, 5, 10) } ?: 5
        set(value) = sp.edit().putInt(KEY_ALERT_DISTANCE,
            value.takeIf { it in setOf(2, 5, 10) } ?: 5).apply()

    var useLocation: Boolean
        get() = sp.getBoolean(KEY_USE_LOCATION, false)
        set(v) = sp.edit().putBoolean(KEY_USE_LOCATION, v).apply()

    fun recentTrains(): List<String> =
        sp.getString(KEY_RECENT, "")?.split(';')?.filter { it.isNotBlank() } ?: emptyList()

    fun pushRecent(trainNo: String, from: String, to: String, originEpochDay: Long) {
        val entry = "$trainNo|$from|$to|$originEpochDay"
        val list = (listOf(entry) + recentTrains()).distinct().take(6)
        sp.edit().putString(KEY_RECENT, list.joinToString(";")).apply()
    }

    fun clearCaches() = sp.edit()
        .remove(KEY_LAST_FROM).remove(KEY_LAST_TO).remove(KEY_LAST_TRAIN).remove(KEY_RECENT)
        .remove(KEY_HAS_SEARCH_DRAFT).remove(KEY_SEARCH_FROM_TEXT).remove(KEY_SEARCH_FROM_RESOLVED)
        .remove(KEY_SEARCH_TO_TEXT).remove(KEY_SEARCH_TO_RESOLVED)
        .remove(KEY_SEARCH_TRAIN_TEXT).remove(KEY_SEARCH_TRAIN_RESOLVED)
        .remove(KEY_SEARCH_MODE).remove(KEY_SEARCH_EPOCH_DAY).remove(KEY_SEARCH_MINUTE).apply()

    companion object {
        private const val KEY_BATTERY_MODE = "batteryMode"
        private const val KEY_OVERRIDE = "overrideUrl"
        private const val KEY_COLUMN_HINT = "columnHint"
        private const val KEY_DIARY = "diary"
        private const val KEY_LAST_FROM = "lastFrom"
        private const val KEY_LAST_TO = "lastTo"
        private const val KEY_HAS_SEARCH_DRAFT = "hasSearchDraft"
        private const val KEY_SEARCH_FROM_TEXT = "searchFromText"
        private const val KEY_SEARCH_FROM_RESOLVED = "searchFromResolved"
        private const val KEY_SEARCH_TO_TEXT = "searchToText"
        private const val KEY_SEARCH_TO_RESOLVED = "searchToResolved"
        private const val KEY_SEARCH_TRAIN_TEXT = "searchTrainText"
        private const val KEY_SEARCH_TRAIN_RESOLVED = "searchTrainResolved"
        private const val KEY_SEARCH_MODE = "searchMode"
        private const val KEY_SEARCH_EPOCH_DAY = "searchEpochDay"
        private const val KEY_SEARCH_MINUTE = "searchMinute"
        private const val KEY_LAST_TRAIN = "lastTrain"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_USE_LOCATION = "useLocation"
        private const val KEY_TRACK_SOURCE = "defaultTrackingSource"
        private const val KEY_ALERT_TRIGGER = "defaultAlertTrigger"
        private const val KEY_ALERT_FEEDBACK = "defaultAlertFeedback"
        private const val KEY_ALERT_INTERVAL = "defaultAlertInterval"
        private const val KEY_ALERT_DISTANCE = "defaultAlertDistance"
        private const val KEY_RECENT = "recent"
        private const val KEY_CROWD = "crowdReports"
        private const val KEY_MIRROR_CACHE = "mirrorCache"
        private const val KEY_ERAIL_ROUTE_CACHE = "erailRouteCache"
    }
}
