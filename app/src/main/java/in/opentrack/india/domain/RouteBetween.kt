package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlin.math.abs

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

    /** Only the points between this scheduled halt and the NEXT one. A different route or
     * out-of-order map contributes no rows; a point does not acquire a reported time. */
    fun groupedPoints(map: TrainRoute?, halts: TrainRoute): Map<String, List<StopRow>> {
        if (map == null || withVerifiedHalts(map, halts) == null) return emptyMap()
        val groups = LinkedHashMap<String, List<StopRow>>()
        var start = 0
        for (i in 0 until halts.stops.lastIndex) {
            val from = halts.stops[i]
            val end = map.stops.indexOfFirst { it.code.equals(halts.stops[i + 1].code, true) }
            if (end <= start) return emptyMap()
            val mapStart = map.stops[start].schedKm
            val mapEnd = map.stops[end].schedKm
            var lastKm = mapStart
            val points = map.stops.subList(start + 1, end).map { point ->
                val km = point.schedKm
                val credible = km >= 0 && mapStart >= 0 && mapEnd > mapStart &&
                    km in lastKm..mapEnd
                if (credible) lastKm = km
                point.copy(actualArrivalMin = null, actualDepartureMin = null,
                    schedKm = if (credible) km else -1)
            }
            if (points.isNotEmpty()) groups[from.code] = points
            start = end
        }
        return groups
    }

    fun followingPoints(map: TrainRoute?, halts: TrainRoute, fromCode: String): List<StopRow> =
        groupedPoints(map, halts)[fromCode].orEmpty()

    /** A *projected* train icon within the two NTES halts. Never display this as observed
     * GPS or as an actual passage through an intermediate point. Hide on stale/schedule-only data. */
    data class LegPoint(val code: String, val name: String, val mapKm: Int, val fraction: Float)
    data class LegProjection(
        val from: String, val to: String, val fraction: Float,
        val nearestPoint: String?, val estimateKm: Int,
        val fromCode: String, val toCode: String, val points: List<LegPoint>,
    )

    fun projectLeg(halts: TrainRoute, map: TrainRoute?, position: TrainPosition): LegProjection? {
        if (!position.provenance.live || position.ageSeconds > PositionEngine.STALE_MS / 1000 ||
            (position.feedAgeSeconds ?: 0L) > PositionEngine.STALE_MS / 1000 ||
            position.status != StopStatus.DEPARTED || position.fraction <= 0f ||
            position.fraction >= 1f) return null
        val from = halts.stopAtSeq(position.anchorSeq) ?: return null
        val to = halts.stopAtSeq(position.nextSeq) ?: return null
        if (from.schedKm < 0 || to.schedKm <= from.schedKm ||
            position.nextSeq != position.anchorSeq + 1) return null
        val fraction = position.fraction.coerceIn(0f, 0.97f)
        // Map distances come from a separate static source, so interpolate on that source's OWN
        // leg axis. Official and map total km differ by a kilometre on 20652.
        val mapStart = map?.stops?.firstOrNull { it.code.equals(from.code, true) }?.schedKm ?: -1
        val mapEnd = map?.stops?.firstOrNull { it.code.equals(to.code, true) }?.schedKm ?: -1
        val points = if (mapStart >= 0 && mapEnd > mapStart) followingPoints(map, halts, from.code)
            .filter { !it.isHalt && it.schedKm in mapStart..mapEnd }
            .map { LegPoint(it.code, it.name, it.schedKm,
                (it.schedKm - mapStart).toFloat() / (mapEnd - mapStart)) } else emptyList()
        val projected = if (mapStart >= 0 && mapEnd > mapStart)
            mapStart + (mapEnd - mapStart) * fraction else -1f
        val near = if (projected >= 0) points.minByOrNull { abs(it.mapKm - projected) }
            ?.takeIf { abs(it.mapKm - projected) <= maxOf(1.5f, (mapEnd - mapStart) * 0.15f) }
            ?.name else null
        return LegProjection(from.name, to.name, fraction, near,
            (from.schedKm + (to.schedKm - from.schedKm) * fraction).toInt(),
            from.code, to.code, points)
    }

    fun inclusive(route: TrainRoute, firstCode: String, secondCode: String): List<StopRow> {
        val a = route.stops.indexOfFirst { it.code.equals(firstCode, true) }
        val b = route.stops.indexOfFirst { it.code.equals(secondCode, true) }
        if (a < 0 || b < 0) return emptyList()
        return route.stops.subList(minOf(a, b), maxOf(a, b) + 1)
    }
}
