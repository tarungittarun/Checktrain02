package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute

/** Static schedule query, deliberately unrelated to live position or selected journey span.
 * The available list may contain only halts; callers MUST disclose when pass-through data is absent. */
object RouteBetween {
    /** A dated official timetable may confirm that a point the static map labelled "passing"
     * is in fact a scheduled halt. Keep map order, but use that timetable's halt status/times. */
    fun withVerifiedHalts(map: TrainRoute, schedule: TrainRoute): TrainRoute? {
        val codes = map.stops.map { it.code.uppercase() }
        val required = schedule.stops.map { it.code.uppercase() }
        if (map.trainNo != schedule.trainNo ||
            !map.srcCode.equals(schedule.srcCode, true) ||
            !map.dstCode.equals(schedule.dstCode, true) ||
            codes.filter { it in required } != required)
            return null
        val halts = schedule.stops.filter { it.isHalt }.associateBy { it.code.uppercase() }
        return map.copy(stops = map.stops.mapIndexed { index, point ->
            (halts[point.code.uppercase()] ?: point).copy(seq = index + 1)
        })
    }

    fun inclusive(route: TrainRoute, firstCode: String, secondCode: String): List<StopRow> {
        val a = route.stops.indexOfFirst { it.code.equals(firstCode, true) }
        val b = route.stops.indexOfFirst { it.code.equals(secondCode, true) }
        if (a < 0 || b < 0) return emptyList()
        return route.stops.subList(minOf(a, b), maxOf(a, b) + 1)
    }
}
