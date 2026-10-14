package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Explicit, bounded public-source checks. These are NOT sources of Act or the train marker.
 * In particular, a timetable row or a freshly generated HTTP/update clock is not an observation.
 * Keep the returned date, upstream, station claim and reason for rejection visible to the traveller. */
object PublicPageChecks {
    const val RAIL_YATRI = "RailYatri (NTES relay)"
    const val IXIGO = "ixigo page"
    const val ORG = "whereismytrain.org.in (website)"
    val names = listOf(RAIL_YATRI, IXIGO, ORG)
    data class Result(val detail: String, val checkedAt: Long, val pageAnswered: Boolean)

    fun url(name: String, number: String, originDay: Long, now: Long): String? {
        if (!number.matches(Regex("[0-9]{5}"))) return null
        return when (name) {
            RAIL_YATRI -> {
                val offset = RailTime.istEpochDay(now) - originDay
                if (offset !in 0L..2L) null else
                    "https://livestatus.railyatri.in/api/v3/train_eta_data/$number/$offset.json" +
                        "?start_day=$offset&user_id=-1&device_type_id=4&change_name=seo_train_name" +
                        "&lat=&lng=&claim_on_train=false"
            }
            IXIGO -> "https://www.ixigo.com/trains/$number/running-status"
            ORG -> "https://whereismytrain.org.in/?trainNo=$number"
            else -> null
        }
    }

    fun fetch(http: Http, name: String, number: String, originDay: Long, now: Long): Result =
        fetch(name, number, originDay, now) { url ->
            http.get(url, timeoutMillis = 4_000, retries = 0, budgetMillis = 4_000,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) Chrome/120 Safari/537.36",
                    "Accept" to if (name == RAIL_YATRI) "application/json" else "text/html"))
        }

    /** Transport seam: prove each dropdown choice issues its own URL; an HTTP cache hit is
     * disclosed rather than counted as a new observation. No Android sockets in the JVM tests. */
    internal fun fetch(name: String, number: String, originDay: Long, now: Long,
        get: (String) -> Http.Result): Result {
        val url = url(name, number, originDay, now)
            ?: return Result("Run outside the available 0–2 day query window; no request made.", now, false)
        val reply = get(url)
        return when (reply) {
            is Http.Result.Failed -> Result("Request failed: ${reply.kind} · ${reply.message.take(90)}; no position.",
                System.currentTimeMillis(), false)
            is Http.Result.Ok -> {
                val checked = System.currentTimeMillis()
                val answer = parse(name, reply.body, number, originDay, checked)
                if (reply.fromCache) answer.copy(
                    detail = "Cached HTTP body (${reply.ageMillis / 60_000} min old); " +
                        answer.detail + " No fresh observation.", pageAnswered = false)
                else answer
            }
        }
    }

    fun parse(name: String, body: String, number: String, originDay: Long, now: Long): Result =
        when (name) {
            RAIL_YATRI -> railYatri(body, number, originDay, now)
            IXIGO, ORG -> page(name, body, number, originDay, now)
            else -> Result("Unknown check; no position.", now, false)
        }

    private fun railYatri(body: String, number: String, originDay: Long, now: Long): Result {
        val doc = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result("RailYatri: response is not a status object; no position.", now, false)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(originDay * 86_400_000L))
        val returnedNumber = doc.optString("train_number")
        val returnedDate = doc.optString("train_start_date")
        val upstream = doc.optString("data_from").ifBlank { "unspecified" }
        val code = doc.optString("current_station_code").uppercase(Locale.ROOT)
        val status = when (doc.optString("status").uppercase(Locale.ROOT)) {
            "D" -> "departed"
            "A" -> "arrived"
            else -> doc.optString("status").take(18).ifBlank { "not supplied" }
        }
        val stamp = doc.optString("update_time")
        val stampMillis = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH).apply { isLenient = false }
                .parse(stamp)?.time
        }.getOrNull()
        val age = stampMillis?.let { now - it }
        val identity = "returned $returnedNumber / origin $returnedDate (requested $number / $date)"
        val clock = if (stamp.isBlank()) "no update clock" else "update clock $stamp (not a station event time)"
        val upstreamLabel = "upstream $upstream"
        val claim = if (code.matches(Regex("[A-Z0-9]{2,6}"))) "$code $status" else "no station claim"
        val verdict = when {
            doc.optBoolean("success") != true -> "provider reports failure"
            returnedNumber != number || returnedDate != date -> "wrong train/origin run"
            age == null || age !in 0L..300_000L -> "clock absent, future or >5 min old"
            else -> "same run; station claim only"
        }
        return Result("RailYatri: $identity · $upstreamLabel · $claim · $clock; $verdict. " +
            "ETA/ETD and 'departed' timetable rows are not observed Act; this is not onboard GPS.",
            now, true)
    }

    private fun page(name: String, body: String, number: String, originDay: Long, now: Long): Result {
        // Never search script bundles for train events: embedded schedule/SEO JSON often contains
        // yesterday, today AND tomorrow. Only bounded visible text is used for the page verdict.
        val visible = RunningStatusMirror.stripTags(body
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " "))
            .replace(Regex("\\s+"), " ").trim().take(120_000)
        val title = if (name == IXIGO) "ixigo" else "whereismytrain.org.in"
        if (number !in body) return Result("$title: returned page did not identify train $number; no position.",
            now, false)
        val date = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(originDay * 86_400_000L))
        val selected = Regex("(?i)\\b(?:Today|Selected date|Running status for)\\s*[:–-]?\\s*" +
            "(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})").find(visible)?.groupValues?.get(1)
        val status = when {
            Regex("(?i)\\b(?:yet to start|hasn.t started)\\b").containsMatchIn(visible) -> "yet to start"
            Regex("(?i)\\b(?:reached destination|run complete)\\b").containsMatchIn(visible) -> "reached destination"
            else -> "no unambiguous run status in visible page"
        }
        val updated = Regex("(?i)\\b(?:Last\\s+)?Updated\\s*:?\\s*([^<]{1,38})")
            .find(visible)?.groupValues?.get(1)?.take(32)?.trim()
        val returned = selected?.let { "page-selected $it" } ?: "page does not identify a selected origin date"
        val wrong = selected != null && selected.replace('/', '-') != date
        return Result("$title: train $number · $returned (requested origin $date) · $status" +
            (updated?.let { " · page update label ${it.take(32)}" } ?: "") +
            "; ${if (wrong) "wrong origin run" else "no event-level dated observation"}. " +
            "Schedule, page update clocks and projected times are not Act or measured position" +
            (if (name == ORG) "; not Google's WIMT phone app." else "."), now, true)
    }
}
