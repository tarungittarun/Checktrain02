package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.TrainRoute
import java.util.Locale
import kotlin.math.abs

/** Server-rendered whereismytrain.org.in website, NOT Google's WIMT phone app.
 *
 * Its first clock is a website-published time; its second grey clock is the timetable.
 * The website also puts FUTURE predictions in that first clock. Only a past-marked card,
 * a distinct schedule pair, a past clock and a matching route can make a website report.
 * Even then this is a *site claim*, not independently verified Act or measured GPS.
 */
object OrgWebsiteStatus {
    data class Report(
        val rows: Map<String, LiveStopFields>,
        val anchorCode: String,
        val departed: Boolean,
        val eventMinute: Int,
        val pageUpdatedAt: Long,
        val originInferred: Boolean = true,
    )
    data class Outcome(val report: Report?, val reason: String)

    // The page update clock includes seconds (06:06:19 AM) while station cells do
    // not (05:50 AM). Parse the whole value; otherwise a substring matcher sees
    // "06:19 AM" inside 06:06:19 and invents a thirteen-minute-future clock.
    private val clock = Regex("(?i)\\b(\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*(AM|PM)\\b")
    private val heading = Regex("(?is)<h3\\b[^>]*>\\s*[^<]*?\\(([A-Z0-9]{1,6})\\)\\s*</h3>")
    private val updated = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM))")
    private val card = Regex("(?i)<!--\\s*Station Details Card\\s*-->")
    private const val DAY = 86_400_000L
    private const val IST_OFFSET = 19_800_000L

    private fun minute(s: String): Int? {
        val m = clock.find(s) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 1..12 || min !in 0..59) return null
        return (h % 12 + if (m.groupValues[3].equals("PM", true)) 12 else 0) * 60 + min
    }

    private fun pageClock(body: String, now: Long): Long? {
        val label = updated.find(body)?.groupValues?.get(1) ?: return null
        val min = minute(label) ?: return null
        val seconds = Regex("\\d{1,2}:\\d{2}:(\\d{2})").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (seconds !in 0..59) return null
        var stamp = RailTime.istEpochDay(now) * DAY - IST_OFFSET + min * 60_000L + seconds * 1000L
        if (stamp > now + 60_000L) stamp -= DAY
        return stamp.takeIf { now - it in 0L..300_000L }
    }

    /** [route] is the selected origin run's STATIC timetable; it does not attest to any live row. */
    fun parse(body: String, number: String, originDay: Long, now: Long, route: TrainRoute): Outcome {
        if (route.trainNo != number || route.stops.size < 3)
            return Outcome(null, "selected train/route unavailable")
        if (!RailTime.runsOn(route.runDays, RailTime.istWeekdayOfEpochDay(originDay)))
            return Outcome(null, "selected train does not run on this origin day")
        // This site's URL has no day selector. Only the *currently active, unambiguous*
        // run can be associated with it: never use it for a historical or future selection,
        // or after the next daily service might have departed.
        val originDep = route.stops.first().departureMin
            ?: return Outcome(null, "origin departure timetable missing")
        val railNow = ((RailTime.istEpochDay(now) - originDay) * 1440 + RailTime.istMinuteOfDay(now)).toInt()
        val elapsed = railNow - originDep
        if (elapsed !in 0..minOf(1439, (route.totalMin ?: 1440) + 240))
            return Outcome(null, "site has no day selector; selected origin run is not unambiguously active")
        // Reject script content before reading identity or rows. Never find a different
        // train/run hidden in SEO JSON and mistake it for the visible page.
        val html = body.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        val text = html.replace(Regex("(?s)<[^>]*>"), " ").replace(Regex("\\s+"), " ")
        if (!Regex("(?i)\\b$number\\b").containsMatchIn(text) ||
            !Regex("(?i)\\bSource:\\s*${Regex.escape(route.srcCode)}\\b").containsMatchIn(text) ||
            !Regex("(?i)\\bDestination:\\s*${Regex.escape(route.dstCode)}\\b").containsMatchIn(text))
            return Outcome(null, "website train or endpoints do not match selected run")
        val header = text.substringBefore("Journey Timeline", text).take(1500)
        if (Regex("(?i)\\b(?:not running today|yet to start|has not started|cancelled|diverted)\\b")
                .containsMatchIn(header))
            return Outcome(null, "website reports this run is not active")
        val stamp = pageClock(text, now) ?: return Outcome(null, "website update clock missing or over five minutes old")
        val byCode = route.stops.associateBy { it.code.uppercase(Locale.ROOT) }
        val rows = linkedMapOf<String, LiveStopFields>()
        var anchorCode: String? = null
        var anchorDep = false
        var eventMinute = -1
        var previousSeq = -1
        for (part in card.split(html).drop(1)) {
            val code = heading.find(part)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: continue
            val stop = byCode[code] ?: continue
            // green = the website's own *past* card. White is an upcoming forecast,
            // even if it prints two clock values. We do not infer passing from our clock.
            val className = Regex("(?i)^\\s*<div\\s+class=\"([^\"]+)\"").find(part)
                ?.groupValues?.get(1).orEmpty()
            if (!className.contains("bg-green-50") || !className.contains("opacity-90")) continue
            if (stop.seq <= previousSeq) continue
            val arrSection = part.substringAfter("<!-- Arrival -->", "").substringBefore("<!-- Departure -->")
            val depSection = part.substringAfter("<!-- Departure -->", "").take(900)
            fun published(section: String, scheduled: Int?): Int? {
                if (scheduled == null) return null
                // The grey second clock is the site's printed scheduled minute, NOT a
                // second event. No pair means the lone number could be pure timetable.
                val clocks = clock.findAll(section.take(650)).take(2).map { it.value }.toList()
                if (clocks.size != 2) return null
                val printedSchedule = RailTime.alignClock(minute(clocks[1]), scheduled) ?: return null
                if (abs(printedSchedule - scheduled) > 3) return null
                val value = RailTime.alignClock(minute(clocks[0]), scheduled) ?: return null
                if (value == printedSchedule || value !in originDep..railNow) return null
                return value
            }
            val arr = published(arrSection, stop.arrivalMin)
            var dep = published(depSection, stop.departureMin)
            if (arr != null && dep != null && dep < arr) dep = null
            if (arr == null && dep == null) continue
            val platform = Regex("(?i)\\bPF\\s*([A-Z0-9]{1,3})\\b")
                .find(part.substringBefore("<!-- Arrival -->"))?.groupValues?.get(1)
            rows[code] = LiveStopFields(actualArrivalMin = arr, actualDepartureMin = dep,
                platform = platform)
            previousSeq = stop.seq
            anchorCode = code
            anchorDep = dep != null
            eventMinute = dep ?: arr!!
        }
        if (anchorCode == null) return Outcome(null, "no past-marked, schedule-paired website station minute for this run")
        return Outcome(Report(rows, anchorCode, anchorDep, eventMinute, stamp),
            "${rows.size} website-published past station rows; origin day inferred from timetable, not stated by site; clocks are site claims, not independently verified observations")
    }
}
