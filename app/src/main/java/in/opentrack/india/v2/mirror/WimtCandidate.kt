package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manual, read-only look at an UNDOCUMENTED Where is My Train response. These are candidate
 * historical reports, not certified actuals and never position/ETA inputs. In the captured
 * 20689 response even future rows had fields named actual_* with source=eta; a departed row's
 * updated_at could precede its claimed departure. Both must be excluded separately.
 *
 * Do not silently promote this experimental endpoint into the background refresh path.
 */
object WimtCandidate {
    const val SOURCE = "Where is My Train (candidate)"
    data class Result(val lines: Map<String, String>, val detail: String)

    fun fetch(
        number: String, epochDay: Long, from: String, to: String,
        knownCodes: Set<String>, nowMillis: Long = System.currentTimeMillis(),
        get: (String) -> Pair<String?, String> = ::getOnce,
    ): Result {
        if (!number.matches(Regex("\\d{4,5}")) ||
            !from.matches(Regex("[A-Z]{2,5}")) || !to.matches(Regex("[A-Z]{2,5}")))
            return Result(emptyMap(), "invalid train or route identity")
        val day = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        val parameters = mapOf("date" to day, "train_no" to number,
            "from" to from, "to" to to, "from_day" to "1", "lang" to "en",
            "appVersion" to "7.1.5.633808517", "sb_version" to "0")
        val url = "https://whereismytrain.in/cache/live_status?" + parameters.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val (body, why) = get(url)
        if (body == null) return Result(emptyMap(), "no response ($why)")
        return parse(body, epochDay, from, to, nowMillis, knownCodes)
    }

    fun parse(body: String, epochDay: Long, from: String, to: String, nowMillis: Long,
              knownCodes: Set<String> = emptySet()): Result {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result(emptyMap(), "unreadable JSON; no minutes accepted")
        val expectedDate = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        if (root.optString("start_date") != expectedDate ||
            !root.optString("source_station").equals(from, true) ||
            !root.optString("destination_station").equals(to, true))
            return Result(emptyMap(), "run date or route mismatched; no minutes accepted")
        val rows = root.optJSONArray("days_schedule")
            ?: return Result(emptyMap(), "no dated station rows")
        val lines = linkedMapOf<String, String>()
        var rejectedEta = 0
        var rejectedAhead = 0
        var latestObservation = 0L
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("station_code").uppercase(Locale.ENGLISH)
            if (!code.matches(Regex("[A-Z]{2,5}")) || !seen.add(code) ||
                (knownCodes.isNotEmpty() && code !in knownCodes)) continue
            if (row.optString("source").equals("eta", true)) {
                rejectedEta++
                continue
            }
            // A location or a time string isn't an event. Demand the source's departed flag
            // AND a distinct update timestamp that is later than each proposed event.
            if (row.opt("departed") != true) continue
            val updated = updatedMillis(row.optString("updated_at")) ?: continue
            if (updated > nowMillis + 120_000L || updated < epochDay * 86_400_000L -
                RailTime.IST_OFFSET_MILLIS) continue
            fun event(key: String): String? {
                val seconds = row.optLong(key, 0L)
                val millis = seconds * 1000L
                if (seconds <= 0 || RailTime.istEpochDay(millis) != epochDay ||
                    millis > nowMillis + 120_000L || millis > updated + 30_000L) {
                    if (seconds > 0) rejectedAhead++
                    return null
                }
                latestObservation = maxOf(latestObservation, updated)
                val minute = RailTime.istMinuteOfDay(millis)
                return RailTime.formatRailMinuteBare(minute)
            }
            val arr = event("actual_arrival_tm")
            val dep = event("actual_departure_tm")
            if (arr != null || dep != null) {
                lines[code] = "$code · " + listOfNotNull(arr?.let { "arrival $it" },
                    dep?.let { "departure $it" }).joinToString(" · ") +
                    " · WIMT candidate report (not corroborated; not live position)"
            }
        }
        val age = if (latestObservation == 0L) "no admissible observed-time candidates"
            else "last event update ${((nowMillis - latestObservation).coerceAtLeast(0L) / 60_000L)} min ago"
        return Result(lines, "${rows.length()} rows; ${lines.size} candidate stops; " +
            "$rejectedEta ETA rows and $rejectedAhead pre-update/future cells excluded; $age. " +
            "Unofficial feed; no position or ETA inferred")
    }

    private fun updatedMillis(raw: String): Long? {
        if (!raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+[+]05:30"))) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                isLenient = false
            }.parse(raw.take(19))?.time
        }.getOrNull()
    }

    private fun getOnce(url: String): Pair<String?, String> = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenTrack India source research/1.0")
        }
        try {
            if (connection.responseCode != 200) null to "HTTP ${connection.responseCode}"
            else {
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (out.size() <= 150_000) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.size > 150_000) null to "response exceeded 150 kB"
                else String(bytes, Charsets.UTF_8) to "ok"
            }
        } finally { connection.disconnect() }
    } catch (e: Exception) {
        null to (e.javaClass.simpleName + ": " + (e.message ?: "").take(90))
    }
}
