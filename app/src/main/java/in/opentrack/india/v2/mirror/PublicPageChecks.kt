package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Manual public-source comparisons. A freshly published station claim is NOT an observed
 * station event, a departure minute or GPS. The caller must keep these distinctions on screen. */
object PublicPageChecks {
    const val RAIL_YATRI = "RailYatri"
    const val IXIGO = "ixigo page"
    const val ORG = "whereismytrain.org.in (website)"
    val names = listOf(RAIL_YATRI, IXIGO, ORG)

    data class StationClaim(val code: String, val status: String, val updateAt: Long, val upstream: String)
    data class Result(
        val detail: String, val checkedAt: Long, val pageAnswered: Boolean,
        val stationClaim: StationClaim? = null,
        /** The other website's labelled, past-card station claims. Not independently verified Act. */
        val orgReport: OrgWebsiteStatus.Report? = null,
        /** Page excerpts for comparison only. Never feed these into Act or position. */
        val lines: Map<String, String> = emptyMap(),
    )

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

    fun fetch(http: Http, name: String, number: String, originDay: Long, now: Long,
              source: String? = null, destination: String? = null,
              knownCodes: Set<String> = emptySet(), route: TrainRoute? = null): Result =
        fetch(name, number, originDay, now, source, destination, knownCodes, route) { url ->
            http.get(url, timeoutMillis = 4_000, retries = 0, budgetMillis = 4_000,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) Chrome/120 Safari/537.36",
                    "Accept" to if (name == RAIL_YATRI) "application/json" else "text/html"))
        }

    /** Transport seam: a source choice always requests its own URL. An HTTP fallback cache may
     * show an old comparison, but cannot produce a fresh station claim. */
    internal fun fetch(name: String, number: String, originDay: Long, now: Long,
        source: String? = null, destination: String? = null,
        knownCodes: Set<String> = emptySet(), route: TrainRoute? = null,
        get: (String) -> Http.Result): Result {
        val url = url(name, number, originDay, now)
            ?: return Result("Run outside the available 0–2 day query window; no request made.", now, false)
        return when (val reply = get(url)) {
            is Http.Result.Failed -> Result("Request failed: ${reply.kind} · ${reply.message.take(90)}; no position.",
                System.currentTimeMillis(), false)
            is Http.Result.Ok -> {
                val checked = System.currentTimeMillis()
                val answer = parse(name, reply.body, number, originDay, checked, source, destination,
                    knownCodes, route)
                if (reply.fromCache) answer.copy(
                    detail = "Cached HTTP body (${reply.ageMillis / 60_000} min old); " +
                        answer.detail + " No fresh observation.", pageAnswered = false,
                    stationClaim = null, orgReport = null)
                else answer
            }
        }
    }

    fun parse(name: String, body: String, number: String, originDay: Long, now: Long,
              source: String? = null, destination: String? = null,
              knownCodes: Set<String> = emptySet(), route: TrainRoute? = null): Result = when (name) {
        RAIL_YATRI -> railYatri(body, number, originDay, now, source, destination, knownCodes)
        IXIGO, ORG -> page(name, body, number, originDay, now, knownCodes, route)
        else -> Result("Unknown check; no position.", now, false)
    }

    private fun railYatri(body: String, number: String, originDay: Long, now: Long,
                          source: String?, destination: String?, knownCodes: Set<String>): Result {
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
        val clock = if (stamp.isBlank()) "no update clock" else "update clock $stamp (NOT a station event time)"
        val claim = if (code.matches(Regex("[A-Z0-9]{1,6}"))) "$code $status" else "no station claim"
        val sameRoute = source != null && destination != null &&
            doc.optString("source").equals(source, true) &&
            doc.optString("destination").equals(destination, true)
        val verdict = when {
            !doc.optBoolean("success") -> "provider reports failure"
            returnedNumber != number || returnedDate != date -> "wrong train/origin run"
            age == null || age !in 0L..300_000L -> "clock absent, future or >5 min old"
            !sameRoute -> "route not verified against selected timetable"
            code !in knownCodes || status !in setOf("arrived", "departed") -> "no route-matched station/status claim"
            else -> "same-run station claim, not an observed event"
        }
        val stationClaim = if (verdict.startsWith("same-run"))
            StationClaim(code, status, stampMillis!!, upstream) else null
        return Result("RailYatri: $identity · upstream $upstream · root claims $claim · $clock; $verdict. " +
            "ETA/ETD and timetable rows are not Act; not onboard GPS.", now, true, stationClaim)
    }

    private fun page(name: String, body: String, number: String, originDay: Long, now: Long,
                     knownCodes: Set<String>, route: TrainRoute?): Result {
        // Only visible text. SEO/Next.js scripts can contain several runs and future ETA rows.
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
        val updated = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?)")
            .find(visible)?.groupValues?.get(1)?.trim()
        // The .org.in page actually returned a 29-stop timeline on the owner's phone. Show
        // what it printed beside static rows; do not call those clock tokens observed events.
        val timeline = if (name == ORG) visible.substringAfter("Journey Timeline", visible) else visible
        val matches = Regex("\\(([A-Z0-9]{1,6})\\)").findAll(timeline).take(300).toList()
        val lines = linkedMapOf<String, String>()
        for ((i, m) in matches.withIndex()) {
            val code = m.groupValues[1]
            if (code !in knownCodes || code in lines) continue
            val end = matches.getOrNull(i + 1)?.range?.first ?: timeline.length
            val excerpt = timeline.substring(m.range.last + 1, end.coerceAtMost(m.range.last + 141))
                .replace(Regex("\\s+"), " ").trim()
            if (!Regex("(?i)\\b(?:ARRIVAL|DEPARTURE)\\b").containsMatchIn(excerpt)) continue
            lines[code] = "Page shows $code: ${excerpt.take(115)} · NOT dated Act"
        }
        val returned = selected?.let { "page-selected $it" } ?: "page does not identify a selected origin date"
        val wrong = selected != null && selected.replace('/', '-') != date
        val org = if (name == ORG && route != null && !wrong)
            OrgWebsiteStatus.parse(body, number, originDay, now, route) else null
        val runLabel = if (org?.report != null && selected == null)
            "site has no date selector; mapped to the active selected run by timetable" else returned
        return Result("$title: train $number · $runLabel (requested origin $date) · $status" +
            (updated?.let { " · page update label $it" } ?: "") +
            "; " + (org?.reason ?: if (wrong) "wrong origin run" else "no event-level dated observation") +
            ". ${lines.size} displayed station excerpts for comparison" +
            (if (org?.report != null) "; source-marked past rows are website claims, NOT independently verified Act/GPS."
                else "; NOT Act or measured position.") +
            (if (name == ORG) " not Google's WIMT phone app." else ""), now, true,
            orgReport = org?.report, lines = lines)
    }
}
