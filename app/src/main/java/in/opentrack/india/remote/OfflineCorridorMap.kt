package `in`.opentrack.india.remote

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject

/** Historical infrastructure, NOT a dated timetable or evidence of train movement.
 * The archived corridor is a route from MYS to TLGP assembled from older schedules.
 * It may be reused only on independently checked consecutive legs of today's timetable;
 * missing/changed legs stay unmapped. No old clock or halt flag is used. */
object OfflineCorridorMap {
    const val ASSET = "corridors/16227.json"
    const val SOURCE = "Offline historical corridor · archive commit 2026-06-20; timetable age unknown"
    data class Result(val route: TrainRoute, val mappedLegStarts: Set<String>)
    private data class Point(val code: String, val name: String, val km: Int)

    /**
     * The same archived infrastructure contains the TLGP–SBC section in the *opposite*
     * direction for train 20652. Only that exact endpoint pair is eligible. For 20652,
     * each current consecutive-halt pair is validated independently: this lets a verified
     * DRU–ASK corridor render even if an older map omitted another current halt (KMSI).
     * Intermediate distances are scaled onto the dated timetable's own axis; they never
     * acquire arrival times or become a position source.
     */
    fun parse(json: String, official: TrainRoute): Result? {
        if (official.stops.size < 3 ||
            official.stops.map { it.code.uppercase() }.distinct().size != official.stops.size)
            return null
        val doc = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (doc.optString("train") != "16227" ||
            doc.optString("kind") != "historical corridor, not current schedule or location" ||
            doc.optString("source_vintage") != "2026-06-20") return null
        val arr = doc.optJSONArray("points") ?: return null
        if (arr.length() < 30 || arr.length() > 200) return null
        val archivePoints = (0 until arr.length()).map { i ->
            val row = arr.optJSONArray(i) ?: return null
            Point(row.optString(0).trim().uppercase(), row.optString(1).trim(), row.optInt(2, -1))
        }
        if (archivePoints.first().code != "MYS" || archivePoints.first().km != 0 ||
            archivePoints.last().code != "TLGP" || archivePoints.any { it.name.isEmpty() || it.km < 0 } ||
            archivePoints.map { it.code }.distinct().size != archivePoints.size ||
            archivePoints.zipWithNext().any { (a, b) -> b.km <= a.km }) return null

        val path = when {
            official.trainNo == "16227" && official.srcCode.equals("MYS", true) &&
                official.dstCode.equals("TLGP", true) -> archivePoints
            official.trainNo == "20652" && official.srcCode.equals("TLGP", true) &&
                official.dstCode.equals("SBC", true) -> {
                val sbcIndex = archivePoints.indexOfFirst { it.code == "SBC" }
                val tlgpIndex = archivePoints.indexOfFirst { it.code == "TLGP" }
                if (sbcIndex < 0 || tlgpIndex <= sbcIndex) return null
                val reversed = archivePoints.subList(sbcIndex, tlgpIndex + 1).asReversed()
                val originKm = archivePoints[tlgpIndex].km
                reversed.map { point -> point.copy(km = originKm - point.km) }
            }
            else -> return null
        }
        if (path.first().code != official.srcCode.uppercase() ||
            path.last().code != official.dstCode.uppercase() ||
            path.zipWithNext().any { (a, b) -> b.km <= a.km }) return null

        val officialCodes = official.stops.map { it.code.uppercase() }
        val officialCodeSet = officialCodes.toSet()
        val pathIndex = path.withIndex().associate { it.value.code to it.index }
        // Any current scheduled halts that are present on the old path must keep their
        // order. An unknown/reversed junction makes only the affected legs unavailable.
        val knownPathIndices = officialCodes.mapNotNull(pathIndex::get)
        if (knownPathIndices.zipWithNext().any { (a, b) -> b <= a }) return null

        val routeStops = mutableListOf<StopRow>()
        val covered = linkedSetOf<String>()
        routeStops += official.stops.first()
        official.stops.zipWithNext().forEach { (from, to) ->
            val fromIndex = pathIndex[from.code.uppercase()]
            val toIndex = pathIndex[to.code.uppercase()]
            val span = if (fromIndex != null && toIndex != null && toIndex > fromIndex)
                path.subList(fromIndex, toIndex + 1) else emptyList()
            val physicalKm = if (span.isNotEmpty()) span.last().km - span.first().km else 0
            val currentKm = to.schedKm - from.schedKm
            val hasSkippedScheduledHalt = span.drop(1).dropLast(1)
                .any { it.code in officialCodeSet }
            val valid = span.isNotEmpty() && !hasSkippedScheduledHalt && span.size <= 30 &&
                physicalKm in 1..130 && currentKm in 1..130 &&
                physicalKm.toDouble() / currentKm in 0.65..1.5
            if (valid) {
                covered += from.code.uppercase()
                var lastMappedKm = from.schedKm
                span.drop(1).dropLast(1).forEach { point ->
                    val scaledKm = from.schedKm +
                        ((point.km - span.first().km).toDouble() / physicalKm * currentKm).toInt()
                    // If the dated leg is too short to place this archived point strictly
                    // between its halts, omit that point rather than drawing a false order.
                    if (scaledKm > lastMappedKm && scaledKm < to.schedKm) {
                        routeStops += StopRow(0, point.code, point.name,
                            state = null, lat = null, lon = null,
                            arrivalMin = null, departureMin = null,
                            journeyDay = 0, schedKm = scaledKm, isHalt = false)
                        lastMappedKm = scaledKm
                    }
                }
            }
            routeStops += to
        }
        if (covered.isEmpty()) return null
        val validatedRoute = official.copy(
            stops = routeStops.mapIndexed { i, row -> row.copy(seq = i + 1) },
            segments = emptyList())
        return Result(validatedRoute, covered)
    }
}
