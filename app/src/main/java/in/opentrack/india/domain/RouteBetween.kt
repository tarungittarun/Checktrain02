package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute

/** Static schedule query, deliberately unrelated to live position or selected journey span.
 * The available list may contain only halts; callers MUST disclose when pass-through data is absent. */
object RouteBetween {
    fun inclusive(route: TrainRoute, firstCode: String, secondCode: String): List<StopRow> {
        val a = route.stops.indexOfFirst { it.code.equals(firstCode, true) }
        val b = route.stops.indexOfFirst { it.code.equals(secondCode, true) }
        if (a < 0 || b < 0) return emptyList()
        return route.stops.subList(minOf(a, b), maxOf(a, b) + 1)
    }
}
