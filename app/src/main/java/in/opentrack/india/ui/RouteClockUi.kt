package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime

/** UI-only formatting. The underlying minutes retain their run-day offset for all gates and TalkBack. */
object RouteClockUi {
    fun clock(minute: Int?): String = minute?.let(RailTime::formatRailMinuteBare) ?: "—"

    /** Display only at the first row of a new run day, not next to every clock. */
    fun dayMarker(firstMinute: Int?, previousMinute: Int?): String? {
        val day = firstMinute?.let(RailTime::dayOffsetOf) ?: return null
        val prior = previousMinute?.let(RailTime::dayOffsetOf) ?: -1
        return if (day > 0 && day != prior) "day${day + 1}" else null
    }
}
