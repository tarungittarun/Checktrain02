package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.RouteBetween
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
    /** A station in the independent STATIC map, not a measured GPS point or live route km. */
    data class Intermediate(val code: String, val name: String, val precedingHalt: String,
                            val followingHalt: String, val mapKm: Int, val scheduledMinute: Int)
    data class Report(
        val rows: Map<String, LiveStopFields>,
        val anchorCode: String,
        val departed: Boolean,
        val eventMinute: Int,
        val pageUpdatedAt: Long,
        val originInferred: Boolean = true,
        val intermediate: Intermediate? = null,
        /** The latest card's departure may advance with the page clock. Do not show as Act. */
        val provisionalDeparture: Int? = null,
    )
    data class Outcome(val report: Report?, val reason: String)

    /** Ordered route progress, NOT an absolute km. Map intermediate km and dated halt km
     * can be on different static axes; BEI 905 in a separately mapped leg must still
     * precede the next scheduled BDVT halt at 116 km on the timetable axis. */
    data class Progress(val haltIndex: Int, val afterHalt: Int, val mapKm: Int = 0) :
        Comparable<Progress> {
        override fun compareTo(other: Progress): Int {
            val halt = haltIndex.compareTo(other.haltIndex)
            if (halt != 0) return halt
            val relative = afterHalt.compareTo(other.afterHalt)
            return if (relative != 0) relative else mapKm.compareTo(other.mapKm)
        }
    }

    fun progress(report: Report, route: TrainRoute): Progress? {
        val point = report.intermediate
        val index = route.stops.indexOfFirst {
            it.code.equals(point?.precedingHalt ?: report.anchorCode, true)
        }
        if (index < 0 || point != null && (index >= route.stops.lastIndex ||
                point.mapKm < 0 || !route.stops[index + 1].code.equals(point.followingHalt, true)))
            return null
        return Progress(index, if (point == null) 0 else 1, point?.mapKm ?: 0)
    }

    /** A newer website reply can omit/retract a previously reached card. Do not turn that
     * into backwards motion. The floor is scoped by the caller to ONE train and origin run.
     * A prior card may be retained only inside its original five-minute freshness window;
     * once stale, show no position rather than resurrecting it with a newer page clock. */
    fun forwardOnly(previous: Report?, next: Report?, floor: Progress?, route: TrainRoute,
                    now: Long): Report? {
        if (next == null) return null
        val candidate = progress(next, route) ?: return null
        if (floor == null || candidate >= floor) return next
        return previous?.takeIf { old -> progress(old, route)?.let { it >= floor } == true &&
            now - old.pageUpdatedAt in 0L..300_000L }
    }

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
    fun parse(body: String, number: String, originDay: Long, now: Long, route: TrainRoute,
              mapRoute: TrainRoute? = null): Outcome {
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
        // The website can report non-halt stations (BEI/SME on 20689). Accept only
        // points in a separately validated, in-order map with credible leg distances.
        val map = mapRoute?.takeIf { RouteBetween.withVerifiedHalts(it, route) != null }
        val groups = RouteBetween.groupedPoints(map, route)
        val inLeg = groups.flatMap { (from, points) ->
            val i = route.stops.indexOfFirst { it.code.equals(from, true) }
            val next = route.stops.getOrNull(i + 1)
            points.filter { it.schedKm >= 0 && next != null }.map { point ->
                point.code.uppercase(Locale.ROOT) to Triple(from, next!!.code, point)
            }
        }.toMap()
        // Official halt schedules win if the static map's timetable differs; only
        // the non-halt names/ordering/distances come from that map.
        val byCode = (map?.let { RouteBetween.withVerifiedHalts(it, route) }?.stops
            ?: route.stops).associateBy { it.code.uppercase(Locale.ROOT) }
        val rows = linkedMapOf<String, LiveStopFields>()
        var anchorCode: String? = null
        var anchorDep = false
        var eventMinute = -1
        var previousSeq = -1
        var intermediate: Intermediate? = null
        for (part in card.split(html).drop(1)) {
            val code = heading.find(part)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: continue
            val stop = byCode[code] ?: continue
            val leg = if (stop.isHalt) null else (inLeg[code] ?: continue)
            // Green is a past card. The owner's 16205 screenshots and a read-only
            // 17:20 HTML trial also show a BLUE SMET card with a past 16:48 arrival,
            // even after later BDVT/MSS cards are green. Blue is not proof by itself:
            // require a distinct schedule-paired arrival that is already in the past.
            // White is upcoming and must NEVER create a reported minute.
            val className = Regex("(?i)^\\s*<div\\s+class=\"([^\"]+)\"").find(part)
                ?.groupValues?.get(1).orEmpty()
            val pastCard = className.contains("bg-green-50") && className.contains("opacity-90")
            val highlightedCard = className.contains("bg-blue-50")
            if (!pastCard && !highlightedCard) continue
            if (stop.seq <= previousSeq) continue
            val arrSection = part.substringAfter("<!-- Arrival -->", "").substringBefore("<!-- Departure -->")
            val depSection = part.substringAfter("<!-- Departure -->", "").take(900)
            fun published(section: String, scheduled: Int?): Pair<Int, Int>? {
                // A non-halt has no separately published timetable minute in our map.
                // Require the page's second clock to lie between its two official halt
                // schedules. It remains a WEBSITE schedule, not corroboration.
                val from = leg?.first?.let { c -> route.stops.firstOrNull { it.code == c } }
                val to = leg?.second?.let { c -> route.stops.firstOrNull { it.code == c } }
                val scheduledBase = scheduled ?: (from?.departureMin ?: from?.arrivalMin)
                if (scheduledBase == null) return null
                // The grey second clock is the site's printed scheduled minute, NOT a
                // second event. No pair means the lone number could be pure timetable.
                val clocks = clock.findAll(section.take(650)).take(2).map { it.value }.toList()
                if (clocks.size != 2) return null
                val printedSchedule = RailTime.alignClock(minute(clocks[1]), scheduledBase) ?: return null
                if (scheduled != null && abs(printedSchedule - scheduled) > 3) return null
                if (scheduled == null && (printedSchedule < scheduledBase - 3 ||
                    printedSchedule > (to?.arrivalMin ?: to?.departureMin ?: return null) + 3))
                    return null
                val value = RailTime.alignClock(minute(clocks[0]), printedSchedule) ?: return null
                if (value == printedSchedule || value !in originDep..railNow) return null
                return value to printedSchedule
            }
            val arrival = published(arrSection, stop.arrivalMin)
            val departure = published(depSection, stop.departureMin)
            val arr = arrival?.first
            // A blue highlight can mark a special halt OR a current/upcoming card.
            // Without its own past, schedule-paired arrival it is still a forecast.
            if (highlightedCard && arr == null) continue
            var dep = departure?.first
            if (arr != null && dep != null && dep < arr) dep = null
            // A passing point with a departure-only clock could be the site's
            // moving/current estimate; require its own past-marked arrival claim.
            if (leg != null && arr == null) continue
            if (arr == null && dep == null) continue
            val platform = Regex("(?i)\\bPF\\s*([A-Z0-9]{1,3})\\b")
                .find(part.substringBefore("<!-- Arrival -->"))?.groupValues?.get(1)
            rows[code] = LiveStopFields(actualArrivalMin = arr, actualDepartureMin = dep,
                platform = platform)
            previousSeq = stop.seq
            anchorCode = code
            anchorDep = dep != null
            eventMinute = dep ?: arr!!
            intermediate = leg?.let { (from, to, point) ->
                Intermediate(code, point.name, from, to, point.schedKm,
                    (arrival ?: departure)?.second ?: return@let null)
            }
        }
        if (anchorCode == null) return Outcome(null, "no past-marked, schedule-paired website station minute for this run")
        // The owner's 20689 diary shows the last green card's BDVT departure
        // changing 13:47→13:48→13:51→13:52 as the page clock advanced. It is
        // NOT a settled historic departure until another station has been reached.
        val last = rows[anchorCode]
        val provisional = if (last?.actualArrivalMin != null) last.actualDepartureMin else null
        if (provisional != null) {
            rows[anchorCode] = last!!.copy(actualDepartureMin = null)
            eventMinute = last.actualArrivalMin!!
            anchorDep = false
        }
        val report = Report(rows, anchorCode, anchorDep, eventMinute, stamp,
            true, intermediate, provisional)
        return Outcome(report,
            "${rows.size} website-published past station rows; origin day inferred from timetable, not stated by site; clocks are site claims, not independently verified observations")
    }
}
