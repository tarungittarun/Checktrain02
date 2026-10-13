package `in`.opentrack.india.remote

import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONArray
import org.json.JSONObject

/** Private, per-run last-known official table. Never an additional live source.
 * Schedule and actuals must travel together: replaying STNS actuals on an unrelated bundled
 * route was precisely the bug seen on 16227. No PNR, GPS, cookies or passenger data is stored. */
internal object OfficialRunCache {
    fun encode(snapshot: LiveSnapshot): String {
        val route = requireNotNull(snapshot.reportedRoute)
        val stops = JSONArray()
        for (s in route.stops) stops.put(JSONObject().apply {
            put("code", s.code); put("name", s.name); put("seq", s.seq)
            s.arrivalMin?.let { put("arr", it) }
            s.departureMin?.let { put("dep", it) }
            put("km", s.schedKm); put("halt", s.isHalt)
        })
        return JSONObject().apply {
            put("schema", 1)
            put("train", snapshot.trainNo)
            put("day", snapshot.journeyEpochDay)
            put("saved", snapshot.fetchedAtMillis)
            snapshot.feedStampMillis?.let { put("stamp", it) }
            put("name", route.trainName)
            put("stops", stops)
            put("snapshot", JSONObject(SnapshotCodec.encode(snapshot)))
        }.toString()
    }

    fun decode(text: String, train: String, day: Long, from: String, to: String,
               nowMillis: Long): LiveSnapshot? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("schema") != 1 || root.optString("train") != train ||
            root.optLong("day", -1) != day) return null
        val saved = root.optLong("saved", 0L)
        if (saved <= 0 || saved > nowMillis + 120_000L || nowMillis - saved > MAX_AGE_MS) return null
        val array = root.optJSONArray("stops") ?: return null
        if (array.length() < 3 || array.length() > 1000) return null
        val stops = (0 until array.length()).map { i ->
            val o = array.optJSONObject(i) ?: return null
            val code = o.getString("code")
            StopRow(
                seq = i + 1, code = code, name = o.optString("name").ifBlank { code },
                state = null, lat = null, lon = null,
                arrivalMin = o.optInt("arr").takeIf { o.has("arr") },
                departureMin = o.optInt("dep").takeIf { o.has("dep") },
                journeyDay = (o.optInt("dep").takeIf { o.has("dep") }
                    ?: o.optInt("arr").takeIf { o.has("arr") } ?: 0) / 1440,
                schedKm = o.optInt("km", -1), isHalt = (o.opt("halt") as? Boolean ?: true),
            )
        }
        val codes = stops.map { it.code.uppercase() }
        if (codes.any { !it.matches(Regex("[A-Z0-9]{2,6}")) } || codes.distinct().size != codes.size ||
            stops.first().departureMin == null || stops.last().arrivalMin == null) return null
        val a = codes.indexOf(from.uppercase())
        if (a < 0 || codes.indexOf(to.uppercase()) <= a) return null
        val segments = stops.zipWithNext { x, y ->
            SegmentRow(x.seq, x.code, y.code,
                if (x.schedKm >= 0 && y.schedKm >= x.schedKm) y.schedKm - x.schedKm else 0,
                x.departureMin?.let { d -> y.arrivalMin?.let { (it - d).coerceAtLeast(0) } })
        }
        val route = TrainRoute(train, root.optString("name").ifBlank { "Train $train" },
            stops.first().code, stops.first().name, stops.last().code, stops.last().name,
            stops.last().schedKm.coerceAtLeast(0),
            stops.last().arrivalMin?.let { it - (stops.first().departureMin ?: 0) },
            null, null, stops, segments)
        val snap = SnapshotCodec.decode((root.optJSONObject("snapshot") ?: return null).toString()) ?: return null
        if (snap.trainNo != train || snap.journeyEpochDay != day || snap.perStop.keys.none { it in codes })
            return null
        val stamp = root.optLong("stamp", 0L).takeIf { it > 0L }
        if (stamp != null && (stamp > nowMillis + 120_000L || stamp < saved - MAX_AGE_MS)) return null
        snap.copy(sourceKind = SourceKind.CACHED, fetchedAtMillis = saved,
            feedStampMillis = stamp, reportedRoute = route,
            rawNote = "Last known official NTES report; not a current observation")
    }.getOrNull()

    const val MAX_AGE_MS = 24 * 60 * 60_000L
}
