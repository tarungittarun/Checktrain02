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
    /** The website card's colour is source UI state, not proof that a train moved. */
    enum class SiteCardMark { PAST, HIGHLIGHTED, UPCOMING }

    /** First and second (grey timetable) clocks printed in a route-matched card.
     * Extra labels remain comparison-only; none are TrackModel events or marker evidence. */
    data class SiteClocks(
        val arrivalMin: Int?,
        val departureMin: Int?,
        val forecast: Boolean,
        val arrivalScheduleMin: Int? = null,
        val departureScheduleMin: Int? = null,
        val stationName: String? = null,
        val platform: String? = null,
        val statusText: String? = null,
        val mark: SiteCardMark? = null,
        /** A same-code later card omitted its departure; the earlier card's displayed value is retained. */
        val latestCardArrivalOnly: Boolean = false,
        val cardCount: Int = 1,
        /** Printed site clock is later than the page's own update label; display-only, not an event. */
        val arrivalAfterPageUpdate: Boolean = false,
        val departureAfterPageUpdate: Boolean = false,
    )

    /** Printed clock plus the page's own grey timetable clock and original text. */
    data class SiteCardTime(
        val minute: Int,
        val pageScheduleMinute: Int?,
        val printedText: String? = null,
        val pageScheduleText: String? = null,
        /** This page visibly prints a clock later than its own update label. */
        val afterPageUpdate: Boolean = false,
    )

    /** One website card exactly as rendered. Includes duplicate and upcoming cards so
     * the comparison can preserve the page without promoting echoes to train events. */
    data class SiteCardClaim(
        val code: String,
        val mark: SiteCardMark,
        val arrival: SiteCardTime?,
        val departure: SiteCardTime?,
        val qualificationNote: String? = null,
        val stationName: String? = null,
        val platform: String? = null,
        val statusText: String? = null,
        val cardIndex: Int = 0,
    )
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
        /** True only when the source's own update label actually contained seconds. */
        val pageClockHasSeconds: Boolean = false,
        val siteClocks: Map<String, SiteClocks> = emptyMap(),
        val latestSiteCard: SiteCardClaim? = null,
        /** All parseable visible cards, including white forecasts and repeated station cards. */
        val siteCards: List<SiteCardClaim> = emptyList(),
    )
    data class Outcome(val report: Report?, val reason: String,
                       /** A source-marked card not admitted as an actual clock or map position. */
                       val qualificationNote: String? = null,
                       /** Parsed site cards survive even when no card qualifies as a report. */
                       val siteCards: List<SiteCardClaim> = emptyList(),
                       /** Route-matched comparison clocks are retained separately from event rows. */
                       val siteClocks: Map<String, SiteClocks> = emptyMap(),
                       /** Parsed publication clock remains distinct from app receipt and event times. */
                       val pageUpdatedAt: Long? = null)

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

    /** The parser has already qualified an intermediate against a separately
     * validated map. Rendering should re-check its adjacent timetable halts, not
     * require a second, possibly newer map copy to reproduce the same point row. */
    fun matchesSelectedRoute(report: Report, route: TrainRoute): Boolean =
        progress(report, route) != null && (report.intermediate != null ||
            route.stops.any { it.code.equals(report.anchorCode, true) && it.schedKm >= 0 })

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
    private val headingText = Regex("(?is)<h3\\b[^>]*>(.*?)</h3>")
    private val stationCode = Regex("(?i)\\(([A-Z0-9]{1,6})\\)")
    private val cardClass = Regex("(?is)<div\\b[^>]*\\bclass\\s*=\\s*[\"']([^\"']+)[\"']")
    private val stationCardRoot = Regex(
        """(?is)<div\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bflex-grow\b)(?=[^>]*\bclass\s*=\s*["'][^"']*\brounded-lg\b)[^>]*>""")
    private val spanTag = Regex("(?is)<span\\b[^>]*>(.*?)</span>")
    private val platformBadge = Regex("(?i)\\bPF\\s*[A-Z0-9]{1,3}\\b")
    private val platformValue = Regex("(?i)\\bPF\\s*([A-Z0-9]{1,3})\\b")
    private val updated = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM))")
    private val card = Regex("(?i)<!--\\s*Station Details Card\\s*-->")
    private val arrivalMarker = Regex("(?i)<!--\\s*Arrival\\s*-->")
    private val departureMarker = Regex("(?i)<!--\\s*Departure\\s*-->")
    private val arrivalLabel = Regex("(?i)\\bARRIVAL\\b")
    private val departureLabel = Regex("(?i)\\bDEPARTURE\\b")

    /** Prefer the source's explicit card comments, but retain a DOM-root fallback:
     * WebView serialization/client rendering may omit HTML comments while keeping
     * the visible card containers and nested station headings. */
    private fun cardParts(html: String): List<String> {
        val comments = card.findAll(html).map { it.range.last + 1 }.toList()
        val starts = if (comments.isNotEmpty()) comments
            else stationCardRoot.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexedNotNull { index, start ->
            val end = starts.getOrNull(index + 1) ?: html.length
            html.substring(start, end).takeIf { cardClass.find(it) != null }
        }
    }

    private fun cardSections(part: String): Pair<String, String> {
        val arrivalCommentMatch = arrivalMarker.find(part)
        val departureCommentMatch = departureMarker.find(part)
        val arrivalTextMatch = if (arrivalCommentMatch == null) arrivalLabel.find(part) else null
        val departureTextMatch = if (departureCommentMatch == null) departureLabel.find(part,
            (arrivalTextMatch?.range?.last ?: -1) + 1) else null
        val arrivalStart = arrivalCommentMatch?.range?.last?.plus(1)
            ?: arrivalTextMatch?.range?.last?.plus(1)
        val arrivalEnd = departureCommentMatch?.range?.first ?: departureTextMatch?.range?.first
            ?: part.length
        val departureStart = departureCommentMatch?.range?.last?.plus(1)
            ?: departureTextMatch?.range?.last?.plus(1)
        return (arrivalStart?.let { part.substring(it, arrivalEnd) } ?: "") to
            (departureStart?.let { part.substring(it) } ?: "")
    }
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

    private fun plainText(markup: String): String = markup
        .replace(Regex("(?is)<[^>]*>"), " ")
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace("&ndash;", "–", ignoreCase = true)
        .replace("&mdash;", "—", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun pageCardTime(section: String, scheduled: Int?, originDay: Long,
                             pageUpdatedAt: Long): SiteCardTime? {
        val clocks = clock.findAll(section.take(900)).take(2).map { it.value.trim() }.toList()
        val firstText = clocks.firstOrNull() ?: return null
        val firstRaw = minute(firstText) ?: return null
        val first = scheduled?.let { RailTime.alignClock(firstRaw, it) } ?: firstRaw
        val scheduleText = clocks.getOrNull(1)
        val scheduleRaw = scheduleText?.let(::minute)
        val schedule = scheduleRaw?.let { raw ->
            scheduled?.let { RailTime.alignClock(raw, it) } ?: raw
        }
        val afterPageUpdate = RailTime.railMinuteToEpochMillis(originDay, first) > pageUpdatedAt
        return SiteCardTime(first, schedule, firstText, scheduleText, afterPageUpdate)
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
        val siteClocks = linkedMapOf<String, SiteClocks>()
        val siteCards = mutableListOf<SiteCardClaim>()
        var anchorCode: String? = null
        var anchorDep = false
        var eventMinute = -1
        var previousSeq = -1
        var intermediate: Intermediate? = null
        var laterThanPage = 0
        val excluded = mutableListOf<Pair<Int, String>>()
        var lastAcceptedCard = -1
        var latestSiteCardIndex = -1
        var latestSiteCard: SiteCardClaim? = null
        for ((cardIndex, part) in cardParts(html).withIndex()) {
            val headingMarkup = headingText.find(part)?.groupValues?.get(1).orEmpty()
            val headingPlain = plainText(headingMarkup)
            val code = stationCode.find(headingPlain)?.groupValues?.get(1)
                ?.uppercase(Locale.ROOT) ?: continue
            // Green is source-marked past, blue is highlighted, and white is upcoming.
            // None of those styles independently proves that the train moved.
            val className = cardClass.find(part)?.groupValues?.get(1).orEmpty()
            val pastCard = className.contains("bg-green-50") && className.contains("opacity-90")
            val highlightedCard = className.contains("bg-blue-50")
            val mark = when {
                pastCard -> SiteCardMark.PAST
                highlightedCard -> SiteCardMark.HIGHLIGHTED
                else -> SiteCardMark.UPCOMING
            }
            val halt = route.stops.firstOrNull { it.code.equals(code, true) }
            val (arrSection, depSection) = cardSections(part)
            val cardHeaderEnd = listOfNotNull(
                arrivalMarker.find(part)?.range?.first,
                arrivalLabel.find(part)?.range?.first,
            ).minOrNull() ?: part.length
            val cardHeader = part.substring(0, cardHeaderEnd)
            // Keep the literal display for every card (including duplicate and white cards).
            // These values are separate from the narrowly qualified `rows` event ledger.
            val rawArrival = pageCardTime(arrSection, halt?.arrivalMin, originDay, stamp)
            val rawDeparture = pageCardTime(depSection, halt?.departureMin, originDay, stamp)
            val stationName = headingPlain.let { title ->
                val suffix = " ($code)"
                if (title.endsWith(suffix, ignoreCase = true)) title.dropLast(suffix.length).trim()
                else title
            }.takeIf { it.isNotBlank() }
            val badgeTexts = spanTag.findAll(cardHeader)
                .map { plainText(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
            val platform = badgeTexts.firstOrNull { platformBadge.containsMatchIn(it) }?.let {
                platformValue.find(it)?.groupValues?.get(1)
            }
            val statusText = badgeTexts.firstOrNull { !it.contains("PF", ignoreCase = true) }
            val scheduleEcho = listOfNotNull(rawArrival, rawDeparture).any { time ->
                time.pageScheduleMinute != null && time.minute == time.pageScheduleMinute
            }
            val cardClaim = SiteCardClaim(
                code = code, mark = mark, arrival = rawArrival, departure = rawDeparture,
                qualificationNote = if (scheduleEcho)
                    "A displayed clock matches the page timetable; it is not a distinct actual report."
                else null,
                stationName = stationName, platform = platform, statusText = statusText,
                cardIndex = cardIndex,
            )
            siteCards += cardClaim
            if (mark != SiteCardMark.UPCOMING && cardIndex > latestSiteCardIndex) {
                latestSiteCard = cardClaim
                latestSiteCardIndex = cardIndex
            }

            // The route-table comparison uses the same page values only when they can be
            // aligned to the selected timetable without a gross mismatch. This is still a
            // WEBSITE claim; it never seeds the event/position rows by itself.
            var shownArrival: SiteCardTime? = null
            var shownDeparture: SiteCardTime? = null
            if (halt != null) {
                fun firstClock(section: String, scheduled: Int?): SiteCardTime? {
                    if (scheduled == null) return null
                    val clocks = clock.findAll(section.take(650)).map { it.value.trim() }.take(2).toList()
                    val firstText = clocks.firstOrNull() ?: return null
                    val first = minute(firstText) ?: return null
                    val aligned = RailTime.alignClock(first, scheduled) ?: return null
                    val hasSchedulePair = clocks.size == 2
                    val pageSchedule = if (hasSchedulePair) {
                        val grey = RailTime.alignClock(minute(clocks[1]), scheduled) ?: return null
                        if (abs(grey - scheduled) > 3) return null
                        grey
                    } else {
                        if (abs(aligned - scheduled) > 3) return null
                        null
                    }
                    // The second clock remains the site's own printed timetable value.
                    return if ((aligned != scheduled || hasSchedulePair) &&
                        aligned in originDep..(originDep + 1440) && abs(aligned - scheduled) <= 360)
                        SiteCardTime(aligned, pageSchedule, firstText, clocks.getOrNull(1),
                            RailTime.railMinuteToEpochMillis(originDay, aligned) > stamp)
                    else null
                }
                val shownArr = firstClock(arrSection, halt.arrivalMin)
                val shownDep = firstClock(depSection, halt.departureMin)
                    ?.takeUnless { shownArr != null && it.minute < shownArr.minute }
                shownArrival = shownArr
                shownDeparture = shownDep
                if (shownArr != null || shownDep != null) {
                    val candidate = SiteClocks(
                        arrivalMin = shownArr?.minute,
                        departureMin = shownDep?.minute,
                        forecast = mark == SiteCardMark.UPCOMING,
                        arrivalScheduleMin = shownArr?.pageScheduleMinute,
                        departureScheduleMin = shownDep?.pageScheduleMinute,
                        stationName = stationName,
                        platform = platform,
                        statusText = statusText,
                        mark = mark,
                        latestCardArrivalOnly = shownArr != null && shownDep == null,
                        arrivalAfterPageUpdate = shownArr?.afterPageUpdate == true,
                        departureAfterPageUpdate = shownDep?.afterPageUpdate == true,
                    )
                    val previous = siteClocks[code]
                    siteClocks[code] = when {
                        previous == null -> candidate
                        previous.mark != SiteCardMark.UPCOMING && candidate.forecast ->
                            previous.copy(cardCount = previous.cardCount + 1)
                        else -> candidate.copy(
                            arrivalMin = shownArr?.minute ?: previous.arrivalMin,
                            departureMin = shownDep?.minute ?: previous.departureMin,
                            arrivalScheduleMin = shownArr?.pageScheduleMinute ?: previous.arrivalScheduleMin,
                            departureScheduleMin = shownDep?.pageScheduleMinute ?: previous.departureScheduleMin,
                            stationName = stationName ?: previous.stationName,
                            platform = platform ?: previous.platform,
                            statusText = statusText ?: previous.statusText,
                            latestCardArrivalOnly = shownArr != null && shownDep == null,
                            cardCount = previous.cardCount + 1,
                            arrivalAfterPageUpdate = shownArr?.afterPageUpdate
                                ?: previous.arrivalAfterPageUpdate,
                            departureAfterPageUpdate = shownDep?.afterPageUpdate
                                ?: previous.departureAfterPageUpdate,
                        )
                    }
                }
            }
            if (!pastCard && !highlightedCard) continue
            val stop = byCode[code]
            if (stop == null) {
                if (pastCard) excluded += cardIndex to
                    "Website marks $code past; route position unverified."
                continue
            }
            val leg = if (stop.isHalt) null else inLeg[code]
            if (!stop.isHalt && leg == null) {
                if (pastCard) excluded += cardIndex to
                    "Website marks $code past; intermediate leg unverified."
                continue
            }
            if (stop.seq <= previousSeq) continue
            fun published(section: String, scheduled: Int?, originDeparture: Boolean = false): Pair<Int, Int>? {
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
                // Only the separately past-marked ORIGIN departure can be a
                // schedule-equal site claim. Other schedule echoes are not Act.
                if ((value == printedSchedule && !originDeparture) ||
                    value !in originDep..railNow) return null
                // A page cannot have published an event which had not happened at its
                // own update time. 20652's 06:14:20 page marked ANF 06:15 as past;
                // using the device's later 06:15:52 fetch clock made a false jump.
                if (RailTime.railMinuteToEpochMillis(originDay, value) > stamp) {
                    laterThanPage++
                    return null
                }
                return value to printedSchedule
            }
            val arrival = published(arrSection, stop.arrivalMin)
            val departure = published(depSection, stop.departureMin,
                originDeparture = pastCard && code.equals(route.srcCode, true))
            val arr = arrival?.first
            // A blue highlight can mark a special halt OR a current/upcoming card.
            // Without its own past, schedule-paired arrival it is still a forecast.
            if (highlightedCard && arr == null) continue
            var dep = departure?.first
            if (arr != null && dep != null && dep < arr) dep = null
            // A passing point with a departure-only clock could be the site's
            // moving/current estimate; require its own past-marked arrival claim.
            if (leg != null && arr == null) {
                if (pastCard) excluded += cardIndex to
                    "Website marks $code past; departure-only clock cannot establish passage."
                continue
            }
            if (arr == null && dep == null) {
                if (pastCard) excluded += cardIndex to
                    "Website marks $code past; clocks do not distinguish report from timetable."
                continue
            }
            val eventPlatform = Regex("(?i)\\bPF\\s*([A-Z0-9]{1,3})\\b")
                .find(part.substringBefore("<!-- Arrival -->"))?.groupValues?.get(1)
            rows[code] = LiveStopFields(actualArrivalMin = arr, actualDepartureMin = dep,
                platform = eventPlatform)
            previousSeq = stop.seq
            lastAcceptedCard = cardIndex
            anchorCode = code
            anchorDep = dep != null
            eventMinute = dep ?: arr!!
            intermediate = leg?.let { (from, to, point) ->
                Intermediate(code, point.name, from, to, point.schedKm,
                    (arrival ?: departure)?.second ?: return@let null)
            }
        }
        val latestCardReason = excluded.lastOrNull { it.first == latestSiteCardIndex }?.second
            ?.substringAfter("; ")
        val latestCardClaim = latestSiteCard?.let { claim ->
            claim.copy(qualificationNote = latestCardReason ?: claim.qualificationNote)
        }
        val qualification = excluded.lastOrNull { it.first > lastAcceptedCard }?.second
        val omitted = if (excluded.isEmpty()) "" else
            "; ${excluded.size} source-coloured past card(s) omitted from Actual (timetable echo or unmapped station)"
        if (anchorCode == null) return Outcome(null, "no past-marked, schedule-paired website station minute for this run" +
            if (laterThanPage > 0) "; $laterThanPage claimed event(s) later than the page update clock rejected" else "",
            qualification, siteCards.toList(), siteClocks.toMap(), stamp)
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
            true, intermediate, provisional,
            updated.find(body)?.groupValues?.get(1)?.let {
                Regex("\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(it)
            } == true, siteClocks, latestCardClaim, siteCards)
        return Outcome(report,
            "${rows.size} website-published past station rows; " +
                (if (laterThanPage > 0) "$laterThanPage claimed event(s) later than the page update clock rejected; " else "") +
                "origin day inferred from timetable, not stated by site; clocks are site claims, not independently verified observations" + omitted,
            qualification, siteCards.toList(), siteClocks.toMap(), stamp)
    }
}
