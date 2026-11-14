package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.core.WebsiteCardMarkup
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.v2.contract.SourceKind as IdentitySourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
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

    enum class SiteClaimKind(val label: String) { ARRIVAL("arrival"), DEPARTURE("departure") }

    /** One source-coloured clock pair from a route-identity-matched page. It is still only
     * the website's own claim; the detached form is never attached to another route's rows. */
    data class SiteEventClaim(
        val stationCode: String,
        val stationName: String?,
        val kind: SiteClaimKind,
        val minute: Int,
        val pageScheduleMinute: Int,
        /** A last-card departure can continue changing while the page advances. */
        val provisionalDeparture: Boolean = false,
    )

    /** A populated site page whose endpoints match the independently fetched train identity,
     * but not the selected timetable. Kept as a detached comparison, never a position. */
    data class DetachedPageClaim(
        val trainNo: String,
        val websiteFromCode: String,
        val websiteToCode: String,
        val selectedRouteFromCode: String,
        val selectedRouteToCode: String,
        val identitySource: String,
        val originDay: Long,
        val pageUpdatedAt: Long,
        val pageClockHasSeconds: Boolean,
        val latestPastCard: SiteCardClaim?,
        val event: SiteEventClaim?,
        val siteCards: List<SiteCardClaim>,
        /** Visible run-date label matched the selected origin day; route can still conflict. */
        val runDateMatched: Boolean = false,
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
                       val pageUpdatedAt: Long? = null,
                       /** A site claim identity-matched to an independent live endpoint witness,
                        * but deliberately detached from the selected timetable when routes differ. */
                       val detachedPageClaim: DetachedPageClaim? = null,
                       /** Bounded endpoint codes/labels from visible text, never raw website HTML. */
                       val pageEndpoints: PageEndpoints? = null,
                       /** Structural card containers found, whether or not their labels qualify. */
                       val parsedCardContainers: Int = 0)

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
    private val headingText = Regex("(?is)<h[2-6]\\b[^>]*>(.*?)</h[2-6]>")
    private val stationCode = Regex("(?i)\\(([A-Z0-9]{1,6})\\)")
    private val cardClass = Regex("(?is)<(?:div|section|article|li|tr)\\b[^>]*\\bclass\\s*=\\s*[\"']([^\"']+)[\"']")
    private val spanTag = Regex("(?is)<span\\b[^>]*>(.*?)</span>")
    private val platformBadge = Regex("(?i)\\bPF\\s*[A-Z0-9]{1,3}\\b")
    private val platformValue = Regex("(?i)\\bPF\\s*([A-Z0-9]{1,3})\\b")
    private val updated = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM))")
    private val arrivalLabel = Regex("(?i)\\bARRIVAL\\b")
    private val departureLabel = Regex("(?i)\\bDEPARTURE\\b")

    /** Parse only actual rendered station-card subtrees. Comments and script text are
     * masked first, so source-looking markup hidden in either cannot become a card. */
    private fun cardParts(html: String): List<String> {
        val document = WebsiteCardMarkup.visiblePageMarkup(html)
        val starts = WebsiteCardMarkup.stationCardSectionStarts(document)
        return starts.mapIndexedNotNull { index, start ->
            val limit = starts.getOrNull(index + 1) ?: document.html.length
            val part = WebsiteCardMarkup.containerSectionAt(document.html, start, limit)
                ?: document.html.substring(start, limit)
            part.takeIf { cardClass.find(it) != null || headingText.containsMatchIn(it) }
        }
    }

    /** Prefer the container class visibly carrying a known site source mark. This never
     * infers a card's timeline position from page order or its printed clock. */
    private fun cardClassName(part: String): String {
        val classes = cardClass.findAll(part).map { it.groupValues[1] }.toList()
        return classes.firstOrNull { value ->
            value.contains("bg-green-50") && value.contains("opacity-90") ||
                value.contains("bg-blue-50")
        } ?: classes.firstOrNull().orEmpty()
    }

    /** Bounded structural diagnostic; never returns card text or raw website markup. */
    internal fun diagnosticCardCount(html: String): Int = cardParts(html).size

    /** Bounded redacted DOM structure for a fresh answer with missing/parsed cards.
     * No HTML, station names, clocks, URL, or response excerpt is returned. */
    internal fun pageStructureReceipt(html: String): String {
        val boundedHtml = html.take(120_000)
        val document = WebsiteCardMarkup.visiblePageMarkup(boundedHtml)
        val visible = document.html
        val tags = listOf("main", "article", "section", "div", "ul", "ol", "li", "table", "tr",
            "h1", "h2", "h3", "h4", "h5", "h6", "span", "p")
        val tagCounts = tags.joinToString(",") { tag ->
            val count = Regex("(?i)<$tag\\b").findAll(visible).take(100_001).count()
            "$tag:$count"
        }
        val headings = Regex("(?is)<(h[1-6])\\b[^>]*>(.*?)</\\1>")
            .findAll(visible).take(8).map { match ->
                val text = plainText(match.groupValues[2])
                val code = stationCode.find(text)?.groupValues?.get(1)
                val kind = when {
                    code != null -> "station_$code"
                    Regex("(?i)\\brunning\\s+status\\b").containsMatchIn(text) -> "train_status"
                    else -> "text"
                }
                match.groupValues[1].lowercase(Locale.ROOT) + "_" + kind
            }.toList().joinToString("|").ifBlank { "none" }
        val labels = Regex("(?i)\\b(ARRIVAL|DEPARTURE)\\b")
            .findAll(visible).take(8).map { match ->
                val tail = visible.substring(match.range.last + 1,
                    minOf(visible.length, match.range.last + 221))
                val clockPresent = Regex("(?i)\\b\\d{1,2}:\\d{2}\\s*(?:AM|PM)\\b")
                    .containsMatchIn(plainText(tail))
                "${match.groupValues[1].uppercase(Locale.ROOT)}:${if (clockPresent) "clock" else "no_clock"}"
            }.toList().joinToString("|").ifBlank { "none" }
        val roots = WebsiteCardMarkup.stationCardSectionStarts(document)
        val cards = diagnosticCardCount(boundedHtml)
        val openContainers = Regex("(?is)<(div|section|article|li|tr)\\b([^>]*)>")
            .findAll(visible).take(10_000).toList()
        val classAttribute = Regex("(?is)\\bclass\\s*=\\s*[\"']([^\"']+)[\"']")
        val classSamples = roots.take(8).mapNotNull { root ->
            val opening = openContainers.firstOrNull { it.range.first == root } ?: return@mapNotNull null
            classAttribute.find(opening.groupValues[2])?.groupValues?.get(1)
                ?.replace(Regex("[^A-Za-z0-9_-]+"), "_")?.trim('_')?.take(64)
        }.joinToString("|").ifBlank { "none" }
        return "chars:${visible.length}~truncated:${html.length > boundedHtml.length}~tags:$tagCounts~" +
            "roots:${roots.size}~cards:$cards~heads:$headings~labels:$labels~classes:$classSamples"
    }

    /** Read endpoint labels without guessing. Prefer an explicit candidate station code, then an
     * exact normalized match against names already carried by the selected timetable. Unrecognized
     * or ambiguous names remain unknown; a route is never inferred from a loose prefix. Only the
     * bounded visible labels are retained for diagnosis, never raw website markup. */
    data class PageEndpoints(
        val fromCode: String?,
        val toCode: String?,
        val fromLabel: String? = null,
        val toLabel: String? = null,
    )

    internal fun routeEndpointsFromVisibleText(text: String,
        candidates: Set<String>, namesByCode: Map<String, Set<String>> = emptyMap()): PageEndpoints {
        val header = text.substringBefore("Journey Timeline", text).take(1500)
        val allowed = candidates.map { it.uppercase(Locale.ROOT) }.toSet()
        fun field(label: String): String? {
            val start = Regex("(?i)\\b${Regex.escape(label)}\\s*:\\s*").find(header)
                ?: return null
            val rest = header.substring(start.range.last + 1)
            val end = Regex("(?i)\\b(?:Source|Destination|Last\\s+Updated)\\s*:|\\bJourney\\s+Timeline\\b")
                .find(rest)?.range?.first ?: rest.length
            return rest.substring(0, end).trim()
        }
        fun code(value: String?): String? {
            if (value.isNullOrBlank() || allowed.isEmpty()) return null
            val explicit = stationCode.findAll(value)
                .map { it.groupValues[1].uppercase(Locale.ROOT) }
                .firstOrNull { it in allowed }
            if (explicit != null) return explicit
            val tokens = Regex("(?i)\\b([A-Z0-9]{2,6})\\b").findAll(value)
                .map { it.groupValues[1].uppercase(Locale.ROOT) }
                .filter { it in allowed }.distinct().toList()
            if (tokens.size == 1) return tokens.firstOrNull()
            if (tokens.size > 1) return null

            val printedName = normalizedStationLabel(value)
            if (printedName.isEmpty()) return null
            val matches = namesByCode.entries.asSequence()
                .filter { it.key.uppercase(Locale.ROOT) in allowed }
                .filter { (_, names) -> names.any { normalizedStationLabel(it) == printedName } }
                .map { it.key.uppercase(Locale.ROOT) }.distinct().toList()
            return matches.singleOrNull()
        }
        fun safeLabel(value: String?): String? {
            val label = value.orEmpty().replace(Regex("[\\p{Cntrl}<>]"), " ")
                .replace(Regex("\\s+"), " ").trim().take(80)
            return label.takeIf { it.isNotEmpty() }
        }
        val fromLabel = field("Source")
        val toLabel = field("Destination")
        return PageEndpoints(code(fromLabel), code(toLabel),
            safeLabel(fromLabel), safeLabel(toLabel))
    }

    private val endpointSuffixes = setOf("junction", "jn", "jct", "station", "railway")

    private fun normalizedStationLabel(value: String): String = value
        .replace(Regex("(?i)&(?:amp;)?"), " and ")
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() && it !in endpointSuffixes }
        .joinToString(" ")

    private fun routeStationNames(route: TrainRoute): Map<String, Set<String>> =
        route.stops.associate { stop ->
            val aliases = linkedSetOf(stop.name)
            if (stop.code.equals(route.srcCode, true)) aliases += route.srcName
            if (stop.code.equals(route.dstCode, true)) aliases += route.dstName
            stop.code.uppercase(Locale.ROOT) to aliases
        }

    /** Explicit station codes remain useful even when they are not on the selected route;
     * name-only cards map only when one selected-route stop has an exact normalized name. */
    private fun stationCodeFromCardHeading(heading: String, route: TrainRoute): String? {
        stationCode.find(heading)?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let { return it }
        val label = normalizedStationLabel(heading)
        if (label.isEmpty()) return null
        val matches = routeStationNames(route).entries.asSequence()
            .filter { (_, names) -> names.any { normalizedStationLabel(it) == label } }
            .map { it.key }.distinct().toList()
        return matches.singleOrNull()
    }

    private fun cardSections(part: String): Pair<String, String> {
        val arrival = arrivalLabel.find(part)
        val departure = arrival?.let { departureLabel.find(part, it.range.last + 1) }
        val arrivalStart = arrival?.range?.last?.plus(1)
        val arrivalEnd = departure?.range?.first ?: part.length
        val departureStart = departure?.range?.last?.plus(1)
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

    /** Detached page clocks have no independent timetable minute to align to. Anchor their
     * printed clocks to the page's own publication minute; this is comparison-only and never
     * supplies route actuals. */
    private fun detachedPageCardTime(section: String, originDay: Long, pageUpdatedAt: Long): SiteCardTime? {
        val clocks = clock.findAll(section.take(900)).take(2).map { it.value.trim() }.toList()
        val firstText = clocks.firstOrNull() ?: return null
        val firstRaw = minute(firstText) ?: return null
        val pageMinute = ((RailTime.istEpochDay(pageUpdatedAt) - originDay) * 1440L +
            RailTime.istMinuteOfDay(pageUpdatedAt)).toInt()
        val first = RailTime.alignClock(firstRaw, pageMinute) ?: firstRaw
        val scheduleText = clocks.getOrNull(1)
        val schedule = scheduleText?.let(::minute)?.let { raw ->
            RailTime.alignClock(raw, first)
        }
        val afterPageUpdate = RailTime.railMinuteToEpochMillis(originDay, first) > pageUpdatedAt
        return SiteCardTime(first, schedule, firstText, scheduleText, afterPageUpdate)
    }

    /** Only create a detached comparison when the website's own route matches the independently
     * fetched live identity. The selected timetable remains untouched when that route differs. */
    private fun detachedPageClaim(html: String, body: String, number: String,
        originDay: Long, now: Long, route: TrainRoute, expectedIdentity: TrainIdentity?): DetachedPageClaim? {
        val identity = expectedIdentity?.takeIf {
            it.number == number && it.provenance.kind == IdentitySourceKind.LIVE_IDENTITY &&
                it.provenance.isFresh(now)
        } ?: return null
        if (route.trainNo != number || route.stops.size < 3) return null

        val visibleHtml = WebsiteCardMarkup.visiblePageMarkup(html).html
        val visibleBody = WebsiteCardMarkup.visiblePageMarkup(body).html
        val visibleText = visibleHtml.replace(Regex("(?s)<[^>]*>"), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
            .replace(Regex("\\s+"), " ").trim()
        val header = visibleText.substringBefore("Journey Timeline", visibleText).take(1500)
        if (!Regex("(?i)\\b${Regex.escape(number)}\\b").containsMatchIn(header) ||
            Regex("(?i)\\b(?:not running today|yet to start|has not started|cancelled|diverted)\\b")
                .containsMatchIn(header)) return null
        val endpoints = routeEndpointsFromVisibleText(visibleText,
            setOf(route.srcCode, route.dstCode, identity.fromCode, identity.toCode),
            routeStationNames(route))
        val websiteFrom = endpoints.fromCode ?: return null
        val websiteTo = endpoints.toCode ?: return null
        if (!websiteFrom.equals(identity.fromCode, true) ||
            !websiteTo.equals(identity.toCode, true) ||
            websiteFrom.equals(route.srcCode, true) && websiteTo.equals(route.dstCode, true)) return null
        val publishedAt = pageClock(visibleBody, now) ?: return null
        val hasSeconds = updated.find(visibleBody)?.groupValues?.get(1)?.let {
            Regex("\\d{1,2}:\\d{2}:\\d{2}").containsMatchIn(it)
        } == true
        val cards = cardParts(html).mapIndexedNotNull { index, part ->
            val headingMarkup = headingText.find(part)?.groupValues?.get(1).orEmpty()
            val headingPlain = plainText(headingMarkup)
            val code = stationCodeFromCardHeading(headingPlain, route)
                ?: return@mapIndexedNotNull null
            val className = cardClassName(part)
            val mark = when {
                className.contains("bg-green-50") && className.contains("opacity-90") -> SiteCardMark.PAST
                className.contains("bg-blue-50") -> SiteCardMark.HIGHLIGHTED
                else -> SiteCardMark.UPCOMING
            }
            val (arrivalSection, departureSection) = cardSections(part)
            val cardHeaderEnd = arrivalLabel.find(part)?.range?.first ?: part.length
            val cardHeader = part.substring(0, cardHeaderEnd)
            val stationName = headingPlain.let { title ->
                val suffix = " ($code)"
                if (title.endsWith(suffix, true)) title.dropLast(suffix.length).trim() else title
            }.takeIf { it.isNotBlank() }
            val badges = spanTag.findAll(cardHeader).map { plainText(it.groupValues[1]) }
                .filter { it.isNotBlank() }.toList()
            val platform = badges.firstOrNull { platformBadge.containsMatchIn(it) }?.let {
                platformValue.find(it)?.groupValues?.get(1)
            }
            val statusText = badges.firstOrNull { !it.contains("PF", true) }
            val arrival = detachedPageCardTime(arrivalSection, originDay, publishedAt)
            val departure = detachedPageCardTime(departureSection, originDay, publishedAt)
            val echoesSchedule = listOfNotNull(arrival, departure).any {
                it.pageScheduleMinute != null && it.minute == it.pageScheduleMinute
            }
            SiteCardClaim(code, mark, arrival, departure,
                if (echoesSchedule) "A displayed clock matches the website's own timetable; it is not a distinct event claim."
                else null, stationName, platform, statusText, index)
        }
        val event = cards.reversed().firstNotNullOfOrNull { claim ->
            if (claim.mark != SiteCardMark.PAST) return@firstNotNullOfOrNull null
            fun distinct(time: SiteCardTime?): SiteEventClaim? {
                val schedule = time?.pageScheduleMinute ?: return null
                if (time.afterPageUpdate || time.minute == schedule ||
                    RailTime.railMinuteToEpochMillis(originDay, time.minute) > publishedAt) return null
                return SiteEventClaim(claim.code, claim.stationName,
                    if (time === claim.arrival) SiteClaimKind.ARRIVAL else SiteClaimKind.DEPARTURE,
                    time.minute, schedule,
                    provisionalDeparture = time === claim.departure && claim.arrival != null)
            }
            // Prefer a distinct arrival: a last card's displayed departure can continue
            // advancing with the page clock and is not evidence of a completed departure.
            distinct(claim.arrival) ?: distinct(claim.departure)
        }
        val latestPastCard = event?.let { selected ->
            cards.lastOrNull { it.code.equals(selected.stationCode, true) && it.mark == SiteCardMark.PAST }
        }
        return DetachedPageClaim(number, websiteFrom, websiteTo,
            route.srcCode.uppercase(Locale.ROOT), route.dstCode.uppercase(Locale.ROOT),
            identity.provenance.source, originDay, publishedAt, hasSeconds,
            latestPastCard, event, cards)
    }

    /** [route] is the selected origin run's STATIC timetable; it does not attest to any live row. */
    fun parse(body: String, number: String, originDay: Long, now: Long, route: TrainRoute,
              mapRoute: TrainRoute? = null, expectedIdentity: TrainIdentity? = null): Outcome {
        if (route.trainNo != number || route.stops.size < 3)
            return Outcome(null, "selected train/route unavailable")
        // Route-matched report rows below need an unambiguous active selected run.
        // A route-conflicting page is handled separately as a detached source claim;
        // using this timetable's run-day/clock to suppress that page would imply a
        // timetable match we have already disproved.
        // Reject script content before reading identity or rows. Never find a different
        // train/run hidden in SEO JSON and mistake it for the visible page.
        val html = WebsiteCardMarkup.visiblePageMarkup(body).html
        val text = html.replace(Regex("(?s)<[^>]*>"), " ").replace(Regex("\\s+"), " ")
        val header = text.substringBefore("Journey Timeline", text).take(1500)
        if (!Regex("(?i)\\b${Regex.escape(number)}\\b").containsMatchIn(text))
            return Outcome(null, "website page does not identify train $number")
        val endpoints = routeEndpointsFromVisibleText(text,
            setOf(route.srcCode, route.dstCode, expectedIdentity?.fromCode.orEmpty(),
                expectedIdentity?.toCode.orEmpty()), routeStationNames(route))
        val parsedCardContainers = diagnosticCardCount(body)
        fun rejected(reason: String): Outcome = Outcome(null, reason, null, emptyList(),
            emptyMap(), null, null, endpoints, parsedCardContainers)
        val websiteFrom = endpoints.fromCode
        val websiteTo = endpoints.toCode
        val routeMatchesPage = websiteFrom?.equals(route.srcCode, true) == true &&
            websiteTo?.equals(route.dstCode, true) == true
        if (!routeMatchesPage) {
            val detached = detachedPageClaim(body, body, number, originDay, now, route,
                expectedIdentity)
            if (detached != null) {
                val routeLabel = "${detached.selectedRouteFromCode}→${detached.selectedRouteToCode}"
                val siteLabel = "${detached.websiteFromCode}→${detached.websiteToCode}"
                val claimLabel = detached.event?.let {
                    " ${it.kind.label} claim at ${it.stationCode} " +
                        RailTime.formatRailMinute(it.minute) +
                        " vs site schedule ${RailTime.formatRailMinute(it.pageScheduleMinute)}"
                } ?: " no fresh past-card clock pair qualified"
                return Outcome(
                    null,
                    "website route $siteLabel matches live ${detached.identitySource} identity but " +
                        "differs from selected timetable $routeLabel;$claimLabel; comparison only, " +
                        "not mapped to this route or current position",
                    null, detached.siteCards, emptyMap(), detached.pageUpdatedAt, detached,
                    endpoints, parsedCardContainers)
            }
            val siteLabel = "${websiteFrom ?: "unknown"}→${websiteTo ?: "unknown"}"
            val selectedLabel = "${route.srcCode.uppercase(Locale.ROOT)}→${route.dstCode.uppercase(Locale.ROOT)}"
            return rejected("website endpoints $siteLabel do not match selected timetable " +
                "$selectedLabel; $parsedCardContainers parsed station card container(s); " +
                "no selected-run claim admitted")
        }
        if (!RailTime.runsOn(route.runDays, RailTime.istWeekdayOfEpochDay(originDay)))
            return rejected("selected train does not run on this origin day")
        // This site has no date selector. Only a route-matched page may be associated
        // with the active selected timetable run; a detached page is never given that
        // run identity or mapped to its schedule.
        val originDep = route.stops.first().departureMin
            ?: return rejected("origin departure timetable missing")
        val railNow = ((RailTime.istEpochDay(now) - originDay) * 1440 + RailTime.istMinuteOfDay(now)).toInt()
        val elapsed = railNow - originDep
        if (elapsed !in 0..minOf(1439, (route.totalMin ?: 1440) + 240))
            return rejected("site has no day selector; selected origin run is not unambiguously active")
        if (Regex("(?i)\\b(?:not running today|yet to start|has not started|cancelled|diverted)\\b")
                .containsMatchIn(header))
            return rejected("website reports this run is not active")
        val stamp = pageClock(text, now)
            ?: return rejected("website update clock missing or over five minutes old")
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
        for ((cardIndex, part) in cardParts(body).withIndex()) {
            val headingMarkup = headingText.find(part)?.groupValues?.get(1).orEmpty()
            val headingPlain = plainText(headingMarkup)
            val code = stationCodeFromCardHeading(headingPlain, route) ?: continue
            // Green is source-marked past, blue is highlighted, and white is upcoming.
            // None of those styles independently proves that the train moved.
            val className = cardClassName(part)
            val pastCard = className.contains("bg-green-50") && className.contains("opacity-90")
            val highlightedCard = className.contains("bg-blue-50")
            val mark = when {
                pastCard -> SiteCardMark.PAST
                highlightedCard -> SiteCardMark.HIGHLIGHTED
                else -> SiteCardMark.UPCOMING
            }
            val halt = route.stops.firstOrNull { it.code.equals(code, true) }
            val (arrSection, depSection) = cardSections(part)
            val cardHeaderEnd = arrivalLabel.find(part)?.range?.first ?: part.length
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
                .find(part.substring(0, arrivalLabel.find(part)?.range?.first ?: part.length))
                ?.groupValues?.get(1)
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
        if (anchorCode == null) return Outcome(null,
            "no past-marked, schedule-paired website station minute for this run" +
                if (laterThanPage > 0) "; $laterThanPage claimed event(s) later than the page update clock rejected" else "",
            qualification, siteCards.toList(), siteClocks.toMap(), stamp, null,
            endpoints, parsedCardContainers)
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
            qualification, siteCards.toList(), siteClocks.toMap(), stamp, null,
            endpoints, parsedCardContainers)
    }
}
