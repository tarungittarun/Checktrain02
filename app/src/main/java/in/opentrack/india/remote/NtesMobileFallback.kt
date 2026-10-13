package `in`.opentrack.india.remote

import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.HttpBody
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Alternate request FORM found in kilotrain's published implementation, not a proven live feed.
 * Warm the official /mntes/ page for cookies, then try queryType 6 on the same official host.
 * This is attempted only after the shipped encrypted request fails. Neither a 200 nor a parsed
 * STNS array is enough to assert current-run actuals: [matchesRun] gates any promotion. */
internal object NtesMobileFallback {
    fun fetch(
        trainNo: String,
        runDay: Long,
        endpoint: String = NtesWire.BASE_URL,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): Http.Result {
        if (trainNo.length !in 4..6 || !trainNo.all(Char::isDigit))
            return Http.Result.Failed(Http.Result.Kind.HTTP, "invalid train number")
        val url = URL(endpoint)
        if (url.protocol != "https" && url.host != "127.0.0.1")
            return Http.Result.Failed(Http.Result.Kind.HTTP, "insecure mobile endpoint refused")
        val warm = URL(url.protocol, url.host, url.port, "/mntes/")
        val cookie = runCatching {
            val c = open(warm)
            try {
                c.connectTimeout = 2_500
                c.readTimeout = 2_500
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", NtesWire.USER_AGENT)
                val status = c.responseCode
                Diary.get()?.log("NTES", "mobile warmup HTTP $status")
                c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value.orEmpty() }
                    .map { it.substringBefore(';').trim() }
                    .filter { it.contains('=') }
                    .joinToString("; ")
            } finally { c.disconnect() }
        }.getOrElse {
            Diary.get()?.log("NTES", "mobile warmup failed: ${it.javaClass.simpleName}")
            ""
        }
        val body = """{"queryType":"6","trainNo":"$trainNo","startDate":"${NtesWire.dateForWire(runDay)}","stationCode":"","lang":"en"}"""
        return try {
            val c = open(url)
            try {
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("User-Agent", NtesWire.USER_AGENT)
                c.setRequestProperty("Referer", warm.toString())
                c.setRequestProperty("Connection", "close")
                if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = c.responseCode
                if (status !in 200..299) {
                    Diary.get()?.log("NTES", "mobile JSON HTTP $status")
                    Http.Result.Failed(Http.Result.Kind.HTTP, "mobile JSON HTTP $status")
                } else {
                    val reply = HttpBody.read(c.inputStream, c.contentEncoding)
                    Diary.get()?.log("NTES", "mobile JSON HTTP $status body=${reply.length}B")
                    if (reply.isBlank()) Http.Result.Failed(Http.Result.Kind.EMPTY, "mobile JSON blank")
                    else Http.Result.Ok(reply, fromCache = false, ageMillis = 0)
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Diary.get()?.log("NTES", "mobile JSON failed: ${e.javaClass.simpleName}: ${e.message}")
            Http.Result.Failed(Http.Result.Kind.HTTP, "mobile JSON ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A train number and explicit run date in the RESPONSE, not just the outgoing request.
     * If the endpoint only echoes a generic timetable, we cannot identify this run and refuse it. */
    fun matchesRun(doc: JSONObject, trainNo: String, runDay: Long): Boolean {
        val echoedNo = doc.optString("TrainNo").ifBlank { doc.optString("trainNo") }
        if (echoedNo != trainNo) return false
        val expected = NtesWire.dateForWire(runDay)
        val day = listOf("StartDate", "startDate", "RunDate", "runDate", "JourneyDate")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotBlank() } ?: return false
        return day.equals(expected, ignoreCase = true)
    }
    /** ETA/ETD from STNS are NOT synonymous with actuals. Only a run-matched, past,
     * schedule-plausible deviation for a halt on our route can enter Act. The rest remain unknown;
     * in particular no future estimate may anchor position or become an actual. */
    fun trustedRows(
        parsed: NtesWire.Parsed, route: TrainRoute, runDay: Long, nowMillis: Long,
    ): NtesWire.Parsed {
        val nowRail = ((RailTime.istEpochDay(nowMillis) - runDay) * 1440 +
            RailTime.istMinuteOfDay(nowMillis)).toInt()
        val origin = route.stops.firstOrNull()?.departureMin ?: Int.MAX_VALUE
        val byCode = route.stops.associateBy { it.code.uppercase() }
        return parsed.copy(rows = parsed.rows.map { row ->
            val stop = byCode[row.code] ?: return@map row.copy(
                actArr = null, actDep = null, delayArr = null, delayDep = null, departed = null)
            fun keep(clock: Int?, schedule: Int?): Int? {
                if (clock == null || schedule == null) return null
                val aligned = RailTime.alignClock(clock, schedule) ?: return null
                return aligned.takeIf { it - schedule in -10..180 && aligned <= nowRail + 2 &&
                    aligned >= origin - 10 }
            }
            // ETA/ETD can be predictions. An associated published stop delay is required
            // before a changed time is even eligible for the past-stop clock checks.
            val arr = if (row.delayArr != null) keep(row.actArr, stop.arrivalMin) else null
            val dep = if (row.delayDep != null) keep(row.actDep, stop.departureMin) else null
            row.copy(actArr = arr, actDep = dep,
                delayArr = row.delayArr.takeIf { arr != null },
                delayDep = row.delayDep.takeIf { dep != null },
                departed = if (dep != null) true else null)
        })
    }
}
