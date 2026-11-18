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
 * Manual, read-only look at an UNDOCUMENTED Where is My Train web-cache response. These are
 * unverified historical notes. A strictly dated web point may be shown as a separate,
 * explicitly uncorroborated *candidate*, never as certified Act, phone-app GPS or an ETA. In the captured
 * 20689 response even future rows had fields named actual_* with source=eta; a departed row's
 * updated_at could precede its claimed departure. Both must be excluded separately.
 *
 * Do not silently promote this experimental endpoint into the background refresh path.
 */
object WimtCandidate {
    const val SOURCE = "WIMT web cache (candidate)"
    private const val MIN_EPOCH_DAY = 0L
    private const val MAX_EPOCH_DAY = 47_482L // 2100-01-01
    private val CLOCK_TIME = Regex("(?:[01]?\\d|2[0-3]|24):[0-5]\\d")
    /** Only a claimed web-cache location. The field is not a GPS/cell measurement and its root
     * timestamp is a cache timestamp, NOT the time the train passed this point. */
    data class WebPoint(
        val code: String, val routeKm: Double, val eventAtMillis: Long,
        val cacheAtMillis: Long, val precedingHalt: String, val followingHalt: String,
    )
    data class WireReply(val body: String?, val status: Int?, val detail: String)
    data class Result(
        val lines: Map<String, String>,
        val detail: String,
        val webPoint: WebPoint? = null,
        /** Transport metadata is separate from parsed source claims. */
        val responseStatus: Int? = null,
        val responseReceived: Boolean = false,
        /** Null means this web-cache response did not echo a train number. */
        val trainNumberMatched: Boolean? = null,
        val trainNumberEchoed: Boolean = false,
    )
    private data class ParsedTime(val railMinute: Int, val field: String)

    const val MAX_RESPONSE_BYTES = 150_000

    fun fetch(
        number: String, epochDay: Long, from: String, to: String,
        knownCodes: Set<String>, nowMillis: Long = System.currentTimeMillis(),
        knownKms: Map<String, Int> = emptyMap(),
        knownTimes: Map<String, Pair<Int?, Int?>> = emptyMap(),
        get: (String) -> WireReply = ::getOnce,
    ): Result {
        if (!number.matches(Regex("[0-9]{4,5}")) ||
            !from.matches(Regex("[A-Z]{2,5}")) || !to.matches(Regex("[A-Z]{2,5}")))
            return Result(emptyMap(), "invalid train or route identity")
        if (epochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY)
            return Result(emptyMap(), "invalid origin run date")
        val day = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        // This is an undocumented public web-cache comparison, not a genuine phone-app
        // session. Do not pass copied appVersion/sb_version identifiers, assumed from_day,
        // or a forged browser identity; ask once by run date and validate the returned route.
        val parameters = linkedMapOf("train_no" to number, "date" to day, "lang" to "en")
        val url = "https://whereismytrain.in/cache/live_status?" + parameters.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val reply = try {
            get(url)
        } catch (error: Exception) {
            return Result(emptyMap(), "${error.javaClass.simpleName}: ${(error.message ?: "").take(90)}")
        }
        if (reply.status != 200) {
            val status = reply.status?.let { "HTTP $it" } ?: "no HTTP response"
            return Result(emptyMap(), "$status · ${reply.detail}",
                responseStatus = reply.status, responseReceived = reply.status != null)
        }
        if (reply.body == null) {
            val reason = reply.detail.takeIf { it.isNotBlank() && it != "ok" }
                ?: "response body missing"
            return Result(emptyMap(), "HTTP 200; $reason",
                responseStatus = reply.status, responseReceived = true)
        }
        if (reply.body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return Result(emptyMap(), "HTTP 200; response exceeded ${MAX_RESPONSE_BYTES / 1000} kB",
                responseStatus = reply.status, responseReceived = true)
        }
        val root = runCatching { JSONObject(reply.body) }.getOrNull()
        val echoedTrain = root?.let { obj ->
            listOf("train_no", "train_number", "trainNo").firstNotNullOfOrNull { key ->
                obj.optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
            }
        }
        if (echoedTrain != null && echoedTrain != number) {
            return Result(emptyMap(), "HTTP 200; response train number did not match requested train",
                responseStatus = reply.status, responseReceived = true,
                trainNumberMatched = false, trainNumberEchoed = true)
        }
        val parsed = parse(reply.body, epochDay, from, to, nowMillis, knownCodes,
            knownKms, knownTimes)
        val trainIdentity = if (echoedTrain == null)
            "response train number not echoed; request used train $number"
        else "response train number matched requested train"
        return parsed.copy(detail = "HTTP 200; $trainIdentity; ${parsed.detail}",
            responseStatus = reply.status, responseReceived = true,
            trainNumberMatched = echoedTrain?.let { it == number },
            trainNumberEchoed = echoedTrain != null)
    }

    fun parse(body: String, epochDay: Long, from: String, to: String, nowMillis: Long,
              knownCodes: Set<String> = emptySet(), knownKms: Map<String, Int> = emptyMap(),
              knownTimes: Map<String, Pair<Int?, Int?>> = emptyMap()): Result {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES)
            return Result(emptyMap(), "response exceeded ${MAX_RESPONSE_BYTES / 1000} kB")
        if (epochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY)
            return Result(emptyMap(), "invalid origin run date")
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result(emptyMap(), "unreadable JSON; no minutes accepted")
        val expectedDate = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        val sourceStation = root.optString("source_station").uppercase(Locale.ENGLISH)
        val destinationStation = root.optString("destination_station").uppercase(Locale.ENGLISH)
        if (root.optString("start_date") != expectedDate ||
            !sourceStation.equals(from, true) || !destinationStation.equals(to, true))
            return Result(emptyMap(), "run date or route mismatched; no minutes accepted")
        val rows = root.optJSONArray("days_schedule")
            ?: return Result(emptyMap(), "no dated station rows")
        if (nowMillis < 0L) return Result(emptyMap(), "invalid current time")
        val lines = linkedMapOf<String, String>()
        var rejectedEta = 0
        var rejectedAhead = 0
        var latestObservation = 0L
        val seen = mutableSetOf<String>()
        val runStartMillis = RailTime.railMinuteToEpochMillis(epochDay, 0)
        val latestAllowedMillis = if (nowMillis > Long.MAX_VALUE - 120_000L)
            Long.MAX_VALUE else nowMillis + 120_000L
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("station_code").uppercase(Locale.ENGLISH)
            if (!code.matches(Regex("[A-Z]{2,5}")) || !seen.add(code) ||
                (knownCodes.isNotEmpty() && code !in knownCodes)) continue
            if (row.optString("source").equals("eta", true)) {
                rejectedEta++
                continue
            }
            val schedule = knownTimes[code]
            fun event(epochKey: String, clockKey: String, scheduledMinute: Int?): ParsedTime? {
                val secondsValue = row.opt(epochKey) as? Number
                if (secondsValue != null && secondsValue.toDouble() != 0.0) {
                    // Epoch-second fields need their own later row-update clock. Never
                    // rehabilitate a contradictory numeric claim with the fallback HH:mm.
                    val seconds = secondsValue.toLong()
                    val updated = updatedMillis(row.optString("updated_at")) ?: return null
                    if (seconds <= 0L || seconds > Long.MAX_VALUE / 1000L) {
                        if (seconds > 0L) rejectedAhead++
                        return null
                    }
                    val millis = seconds * 1000L
                    if (RailTime.istEpochDay(millis) !in epochDay..(epochDay + 3) ||
                        millis > latestAllowedMillis || updated > latestAllowedMillis ||
                        updated < runStartMillis || millis > updated + 30_000L) {
                        rejectedAhead++
                        return null
                    }
                    latestObservation = maxOf(latestObservation, updated)
                    val absoluteMinute = Math.floorDiv(millis + RailTime.IST_OFFSET_MILLIS, 60_000L)
                    val railMinute = (absoluteMinute - epochDay * RailTime.MINUTES_PER_DAY).toInt()
                    return ParsedTime(railMinute, epochKey)
                }

                // Gravity01's provider also consumes the cache's HH:mm fields. Anchor
                // those day-blind clocks to this same train's static run schedule; keep
                // them explicitly unverified and never copy them into ACTUAL cells.
                val rawClock = row.optString(clockKey).trim()
                if (!CLOCK_TIME.matches(rawClock)) return null
                val scheduleMinute = scheduledMinute ?: return null
                if (scheduleMinute !in -RailTime.MINUTES_PER_DAY..
                        (4 * RailTime.MINUTES_PER_DAY)) return null
                val clockMinute = RailTime.parseHm(rawClock) ?: return null
                val railMinute = RailTime.alignClock(clockMinute, scheduleMinute) ?: return null
                val millis = RailTime.railMinuteToEpochMillis(epochDay, railMinute)
                if (RailTime.istEpochDay(millis) !in epochDay..(epochDay + 3) ||
                    millis > latestAllowedMillis) {
                    rejectedAhead++
                    return null
                }
                val updateRaw = row.optString("updated_at").takeIf {
                    it.isNotBlank() && !it.equals("null", true)
                }
                val updated = if (updateRaw == null) null else updatedMillis(updateRaw) ?: return null
                if (updated != null && (updated > latestAllowedMillis ||
                        updated < runStartMillis || millis > updated + 30_000L)) {
                    rejectedAhead++
                    return null
                }
                if (updated != null) latestObservation = maxOf(latestObservation, updated)
                return ParsedTime(railMinute, clockKey)
            }

            val arrived = event("actual_arrival_tm", "actual_arrival_time", schedule?.first)
            val departed = row.opt("departed") == true
            val rawDeparture = if (departed)
                event("actual_departure_tm", "actual_departure_time", schedule?.second) else null
            // A station arrival may be the current reported event while the train is
            // still stopped. Departure is accepted only when WIMT marks it departed;
            // an out-of-order departure must never follow an admitted arrival.
            val departure = if (arrived != null && rawDeparture != null &&
                rawDeparture.railMinute < arrived.railMinute) {
                rejectedAhead++
                null
            } else rawDeparture
            if (arrived != null || departure != null) {
                val eventClaims = listOfNotNull(
                    arrived?.let { "arrival ${RailTime.formatRailMinute(it.railMinute)} [field ${it.field}]" },
                    departure?.let { "departure ${RailTime.formatRailMinute(it.railMinute)} [field ${it.field}]" },
                ).joinToString(" · ")
                val rawSourceTag = row.optString("source").trim()
                val sourceTag = if (rawSourceTag.matches(Regex("[A-Za-z0-9._-]{1,32}")) &&
                    !rawSourceTag.equals("null", true))
                    "cache source tag $rawSourceTag" else "cache source tag unavailable"
                val rowUpdate = updatedMillis(row.optString("updated_at"))?.let {
                    "row update ${formatUpdate(it)}"
                } ?: "row update unavailable"
                lines[code] = "$code · run date ${RailTime.formatDate(epochDay)} · " +
                    "$eventClaims · $sourceTag · $rowUpdate · WIMT web note (unverified, not Act)"
            }
        }
        val age = if (latestObservation == 0L) {
            if (lines.isEmpty()) "no admissible observed-time candidates"
            else "row update timestamp unavailable; age unknown"
        } else "last row update ${((nowMillis - latestObservation).coerceAtLeast(0L) / 60_000L)} min ago"
        val webPoint = positionCandidate(root, rows, epochDay, nowMillis, knownKms)
        val pointNote = if (webPoint == null) "no fresh, route-bracketed web position event"
            else "web point ${webPoint.code} at ${RailTime.formatClock(webPoint.eventAtMillis)} IST " +
                "(${(nowMillis - webPoint.eventAtMillis) / 60_000L} min old); not GPS or the WIMT phone app"
        return Result(lines, "run date ${RailTime.formatDate(epochDay)}; cache-reported route " +
            "$sourceStation→$destinationStation matched requested route; ${rows.length()} rows; " +
            "${lines.size} candidate stops; $rejectedEta ETA rows and $rejectedAhead " +
            "contradictory/pre-update/future cells excluded; $age. $pointNote. " +
            "Undocumented web cache; no future ETA inferred", webPoint)
    }

    /** A root timestamp is minted on every HTTP answer. Only the dated CURRENT point's
     * own event minute may make a position candidate, and it must be between two halts
     * whose order and distances the separately checked static schedule confirms. The
     * web point remains a claim by this cache, not verified cell/GPS or a stop actual. */
    private fun positionCandidate(root: JSONObject, rows: org.json.JSONArray, day: Long,
                                  now: Long, knownKms: Map<String, Int>): WebPoint? {
        if (knownKms.size < 2) return null
        val stamp = updatedMillis(root.optString("lastUpdateIsoDate")) ?: return null
        if (now - stamp !in 0L..120_000L) return null
        val code = root.optString("curStn").uppercase(Locale.ENGLISH)
        if (!code.matches(Regex("[A-Z0-9]{1,6}"))) return null
        val km = (root.opt("distance") as? Number)?.toDouble() ?: return null
        if (!(km >= 0.0 && km < 100_000.0)) return null
        val current = (0 until rows.length()).firstOrNull { i ->
            val row = rows.optJSONObject(i) ?: return@firstOrNull false
            row.optString("station_code").equals(code, true) &&
                row.opt("curStn") == true &&
                !row.optString("source").equals("eta", true)
        } ?: return null
        val row = rows.optJSONObject(current) ?: return null
        // An `actual_departure_tm` can be the *scheduled* time even BEFORE the arrival
        // on non-stops (BLKR on 16227). Never use it as the observation clock.
        val eventSeconds = row.optLong("actual_arrival_tm", 0L)
        if (eventSeconds <= 0 || eventSeconds > Long.MAX_VALUE / 1000L) return null
        val event = eventSeconds * 1000L
        val departedAt = row.optLong("actual_departure_tm", 0L)
        // In the observed BLKR capture this field was 48 minutes BEFORE arrival.
        // Even a freshly minted cache clock must not rehabilitate a contradictory row.
        if (departedAt > 0 && (departedAt < eventSeconds ||
                departedAt > (now + 30_000L) / 1000L)) return null
        val updateRaw = row.optString("updated_at")
        if (updateRaw.isNotBlank()) {
            val updated = updatedMillis(updateRaw) ?: return null
            if (event > updated + 30_000L || updated > now + 120_000L) return null
        }
        if (RailTime.istEpochDay(event) !in day..day + 3 || now - event !in 0L..600_000L ||
            stamp + 120_000L < event) return null
        val prev = if ((knownKms[code] ?: -1) >= 0) current else
            (current - 1 downTo 0).firstOrNull { i ->
                (knownKms[rows.optJSONObject(i)?.optString("station_code")
                    ?.uppercase(Locale.ENGLISH) ?: ""] ?: -1) >= 0
            } ?: return null
        val next = (current + 1 until rows.length()).firstOrNull { i ->
            (knownKms[rows.optJSONObject(i)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: ""] ?: -1) >= 0
        } ?: return null
        val before = rows.optJSONObject(prev)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: return null
        val after = rows.optJSONObject(next)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: return null
        val a = knownKms[before] ?: return null
        val b = knownKms[after] ?: return null
        if (a < 0 || b <= a || km !in a.toDouble()..b.toDouble()) return null
        return WebPoint(code, km, event, stamp, before, after)
    }

    private fun formatUpdate(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy HH:mm:ss 'IST'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(millis))

    private fun updatedMillis(raw: String): Long? {
        if (!raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+[+]05:30"))) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                isLenient = false
            }.parse(raw.take(19))?.time
        }.getOrNull()
    }

    internal fun getOnce(url: String): WireReply = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            useCaches = false
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TrackTrain/1.0 (Android; user-selected web-cache comparison)")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                WireReply(null, status, "HTTP $status")
            } else {
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8_192)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        if (out.size() + n > MAX_RESPONSE_BYTES)
                            return@use null
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes == null) WireReply(null, status,
                    "response exceeded ${MAX_RESPONSE_BYTES / 1000} kB")
                else WireReply(String(bytes, Charsets.UTF_8), status, "ok")
            }
        } finally { connection.disconnect() }
    } catch (e: Exception) {
        WireReply(null, null, e.javaClass.simpleName + ": " + (e.message ?: "").take(90))
    }
}
