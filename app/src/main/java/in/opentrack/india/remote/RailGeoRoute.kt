package `in`.opentrack.india.remote

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONArray
import org.json.JSONObject

/** A historical NTES full-route feed. Treat as untrusted until it contains EVERY independently
 * dated official scheduled halt in the same order with the same endpoints. It offers station codes,
 * not a trustworthy timetable at pass-through points. Never use it for live position. */
object RailGeoRoute {
    private val keys = listOf("STN", "STN_CODE", "stationCode", "station_code", "stnCode",
        "stn", "SC", "code", "station", "station_code_en")
    private val codePattern = Regex("[A-Z0-9]{1,6}")

    fun parse(body: String, official: TrainRoute, nameOf: (String) -> String? = { null }): TrainRoute? {
        val array = runCatching {
            if (body.trimStart().startsWith("[")) JSONArray(body) else {
                val obj = JSONObject(body)
                obj.optJSONArray("data") ?: obj.optJSONArray("schedule") ?: obj.optJSONArray("stations")
            }
        }.getOrNull() ?: return null
        if (array.length() <= official.stops.size || array.length() > 1000) return null
        val codes = (0 until array.length()).map { i ->
            val obj = array.optJSONObject(i) ?: return null
            keys.map { key -> obj.optString(key).trim().uppercase() }
                .firstOrNull { codePattern.matches(it) } ?: return null
        }
        if (codes.distinct().size != codes.size ||
            codes.first() != official.srcCode.uppercase() || codes.last() != official.dstCode.uppercase()) return null
        var cursor = 0
        for (halt in official.stops) {
            val index = codes.indexOf(halt.code.uppercase())
            if (index < cursor) return null
            cursor = index + 1
        }
        val byCode = official.stops.associateBy { it.code.uppercase() }
        return official.copy(stops = codes.mapIndexed { i, code ->
            byCode[code]?.copy(seq = i + 1) ?: StopRow(
                seq = i + 1, code = code, name = nameOf(code)?.takeIf { it.isNotBlank() } ?: code,
                state = null, lat = null, lon = null, arrivalMin = null, departureMin = null,
                journeyDay = 0, schedKm = -1, isHalt = false,
            )
        }, segments = emptyList())
    }
}
