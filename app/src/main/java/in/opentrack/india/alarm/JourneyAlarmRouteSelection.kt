package `in`.opentrack.india.alarm

import `in`.opentrack.india.model.TrainRoute

/** Selects only a route that contains this exact, ordered journey for the requested train. */
object JourneyAlarmRouteSelection {
    fun firstMatching(trainNo: String, fromCode: String, toCode: String,
                      vararg candidates: TrainRoute?): TrainRoute? =
        candidates.firstOrNull { matches(it, trainNo, fromCode, toCode) }

    fun matches(route: TrainRoute?, trainNo: String, fromCode: String, toCode: String): Boolean {
        if (route == null || trainNo.isBlank() || fromCode.isBlank() || toCode.isBlank() ||
            !route.trainNo.equals(trainNo, ignoreCase = true)) return false
        val from = route.stops.firstOrNull { it.code.equals(fromCode, ignoreCase = true) }
            ?: return false
        val to = route.stops.firstOrNull { it.code.equals(toCode, ignoreCase = true) }
            ?: return false
        return from.seq < to.seq
    }
}
