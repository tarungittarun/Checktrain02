package `in`.opentrack.india.data

import android.content.Context
import android.content.SharedPreferences

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

    var overrideUrl: String
        get() = sp.getString(KEY_OVERRIDE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OVERRIDE, v).apply()

    /** Positional field map for the live feed, e.g. `1:code,2:name,3:sa,4:sd,5:aa,6:ad`. */
    var columnHint: String
        get() = sp.getString(KEY_COLUMN_HINT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_COLUMN_HINT, v).apply()

    /** 0 disables polling: on a train, a background refresh loop is mostly a battery drain. */
    var autoRefreshSeconds: Int
        get() = sp.getInt(KEY_REFRESH, 60)
        set(v) = sp.edit().putInt(KEY_REFRESH, v.coerceIn(0, 3600)).apply()

    var lastFromCode: String
        get() = sp.getString(KEY_LAST_FROM, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_FROM, v).apply()

    var lastToCode: String
        get() = sp.getString(KEY_LAST_TO, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TO, v).apply()

    /** Most recently opened train, as `trainNo|fromCode|toCode|originEpochDay`. */
    var lastTrain: String
        get() = sp.getString(KEY_LAST_TRAIN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TRAIN, v).apply()

    /** True once the user has seen (or dismissed) the first-launch data screen. */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

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
        .remove(KEY_LAST_FROM).remove(KEY_LAST_TO).remove(KEY_LAST_TRAIN).remove(KEY_RECENT).apply()

    companion object {
        private const val KEY_OVERRIDE = "overrideUrl"
        private const val KEY_COLUMN_HINT = "columnHint"
        private const val KEY_REFRESH = "refreshSeconds"
        private const val KEY_LAST_FROM = "lastFrom"
        private const val KEY_LAST_TO = "lastTo"
        private const val KEY_LAST_TRAIN = "lastTrain"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_USE_LOCATION = "useLocation"
        private const val KEY_RECENT = "recent"
    }
}
