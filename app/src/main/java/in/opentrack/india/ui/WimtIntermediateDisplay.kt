package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.remote.WimtLiveProvider

/** A WIMT intermediate event is shown separately from scheduled route rows.
 * It can become the newest event label, but never becomes a route stop or GPS position. */
object WimtIntermediateDisplay {
    data class Event(
        val code: String,
        val name: String,
        val precedingHalt: String,
        val followingHalt: String,
        val eventMinute: Int,
        val eventAtMillis: Long,
        val ageMillis: Long,
    )

    /** Admit only a dated event for a non-timetable station between adjacent selected halts. */
    fun newerEvent(
        report: WimtLiveProvider.WimtIntermediate?,
        route: TrainRoute,
        originEpochDay: Long,
        latestRouteEventAtMillis: Long?,
        nowMillis: Long,
    ): Event? {
        report ?: return null
        val minute = report.eventMinute ?: return null
        if (route.stops.any { it.code.equals(report.code, true) }) return null
        val before = route.stops.indexOfFirst { it.code.equals(report.precedingHalt, true) }
        val after = route.stops.indexOfFirst { it.code.equals(report.followingHalt, true) }
        if (before < 0 || after != before + 1) return null
        val eventAt = RailTime.railMinuteToEpochMillis(originEpochDay, minute)
        if (eventAt <= 0L || eventAt > nowMillis ||
            latestRouteEventAtMillis?.let { eventAt <= it } == true) return null
        return Event(report.code, report.name, report.precedingHalt, report.followingHalt,
            minute, eventAt, nowMillis - eventAt)
    }
}
