package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import java.util.Locale

/** A parsed past-card timeline is usable in the *selected website's* own view.
 * It is not an independently run-verified NTES actual, and a page clock is not
 * a train event. Only [OrgWebsiteStatus.parse]'s guarded cells may be painted. */
object WebsiteReportDisplay {
    const val STALE_BADGE = "WEBSITE · mapped event · stale · run inferred"

    /** Event type for the website's last qualifying past card. A card with only a
     * departure is not an arrival; page-update time never chooses this label. */
    enum class SiteEventKind(val summaryLabel: String, val noun: String) {
        ARRIVAL("Site arrival", "arrival"),
        DEPARTURE("Site departure", "departure"),
        PASSAGE("Site passage", "passage"),
        EVENT("Site event", "event"),
    }

    fun eventKind(report: OrgWebsiteStatus.Report): SiteEventKind {
        if (report.intermediate != null) return SiteEventKind.PASSAGE
        val fields = report.rows.entries.firstOrNull {
            it.key.equals(report.anchorCode, ignoreCase = true)
        }?.value
        return eventKind(fields?.actualArrivalMin, fields?.actualDepartureMin,
            report.eventMinute)
    }

    /** With both clocks present, the displayed event minute determines which one
     * the report is describing. Without one, a halt's latest clock is departure. */
    fun eventKind(arrivalMinute: Int?, departureMinute: Int?,
                  eventMinute: Int? = null): SiteEventKind = when {
        departureMinute != null && (eventMinute == null || eventMinute == departureMinute) ->
            SiteEventKind.DEPARTURE
        arrivalMinute != null && (eventMinute == null || eventMinute == arrivalMinute) ->
            SiteEventKind.ARRIVAL
        departureMinute != null -> SiteEventKind.DEPARTURE
        arrivalMinute != null -> SiteEventKind.ARRIVAL
        else -> SiteEventKind.EVENT
    }

    fun usable(state: String?, answered: Boolean, report: OrgWebsiteStatus.Report?): Boolean =
        state == "comparison" && answered && report != null && report.rows.isNotEmpty()

    /** Website cards occupy a separate comparison column: white predictions are prefixed
     * with '~' and never enter Report.rows or the marker. A past/highlighted card may print
     * a departure later than its own page stamp; keep it out until both page and device clocks
     * have passed it. Fail closed if the site's anchor cannot be located on this route. */
    fun displayedClocks(report: OrgWebsiteStatus.Report?, originDay: Long,
        haltCodes: List<String>, now: Long): Map<String, OrgWebsiteStatus.SiteClocks> {
        if (report == null) return emptyMap()
        val anchor = report.intermediate?.precedingHalt ?: report.anchorCode
        val lastIndex = haltCodes.indexOfFirst { it.equals(anchor, ignoreCase = true) }
        if (lastIndex < 0) return emptyMap()
        val latestPossible = minOf(now, report.pageUpdatedAt)
        fun past(minute: Int?): Int? = minute?.takeIf {
            RailTime.railMinuteToEpochMillis(originDay, it) <= latestPossible
        }
        // White-card forecasts remain visible in the WEBSITE column with a ~ prefix
        // from the adapter. They never enter Report.rows or the train marker. A later
        // source-coloured duplicate may be arrival-only, so merge its station clocks
        // while preserving the exact per-card records separately.
        val latestClaim = report.latestSiteCard
        val displayedClaims = report.siteClocks.toMutableMap()
        if (latestClaim != null) {
            val earlierSameCode = displayedClaims[latestClaim.code]
            // A later duplicate can be arrival-only even when an earlier same-code
            // page card showed both clocks. Use the fullest per-station comparison,
            // while latestSiteCard/siteCards continue to describe the duplicate itself.
            displayedClaims[latestClaim.code] = OrgWebsiteStatus.SiteClocks(
                arrivalMin = latestClaim.arrival?.minute ?: earlierSameCode?.arrivalMin,
                departureMin = latestClaim.departure?.minute ?: earlierSameCode?.departureMin,
                forecast = false,
                arrivalScheduleMin = latestClaim.arrival?.pageScheduleMinute
                    ?: earlierSameCode?.arrivalScheduleMin,
                departureScheduleMin = latestClaim.departure?.pageScheduleMinute
                    ?: earlierSameCode?.departureScheduleMin,
                stationName = latestClaim.stationName ?: earlierSameCode?.stationName,
                platform = latestClaim.platform ?: earlierSameCode?.platform,
                statusText = latestClaim.statusText ?: earlierSameCode?.statusText,
                mark = latestClaim.mark,
                latestCardArrivalOnly = latestClaim.arrival != null &&
                    latestClaim.departure == null && earlierSameCode?.departureMin != null,
                cardCount = maxOf(earlierSameCode?.cardCount ?: 0,
                    report.siteCards.count { it.code.equals(latestClaim.code, true) }),
            )
        }
        return displayedClaims.mapNotNull { (code, clocks) ->
            val index = haltCodes.indexOfFirst { it.equals(code, ignoreCase = true) }
            val isLatestClaim = latestClaim?.code?.equals(code, ignoreCase = true) == true
            if (index !in haltCodes.indices || (index > lastIndex && !clocks.forecast && !isLatestClaim))
                return@mapNotNull null
            // Only past/highlighted claims are gated against the upstream and device clocks.
            // White forecasts are shown as forecasts, never as events or marker evidence.
            val arrival = if (clocks.forecast) clocks.arrivalMin else past(clocks.arrivalMin)
            val departureRaw = if (clocks.forecast) clocks.departureMin else past(clocks.departureMin)
            val departure = departureRaw?.takeIf { dep ->
                (arrival ?: report.rows[code]?.actualArrivalMin)?.let { dep >= it } ?: true
            }
            if (arrival == null && departure == null) null
            else code to clocks.copy(arrivalMin = arrival, departureMin = departure)
        }.toMap()
    }

    /** Route-matched site columns remain available when a fresh page has no qualifying event. */
    fun displayedClocks(
        siteClocks: Map<String, OrgWebsiteStatus.SiteClocks>,
        originDay: Long,
        haltCodes: List<String>,
        pageUpdatedAt: Long?,
        now: Long,
    ): Map<String, OrgWebsiteStatus.SiteClocks> {
        if (pageUpdatedAt == null) return emptyMap()
        val latestPossible = minOf(now, pageUpdatedAt)
        fun past(minute: Int?): Int? = minute?.takeIf {
            RailTime.railMinuteToEpochMillis(originDay, it) <= latestPossible
        }
        return siteClocks.mapNotNull { (code, clocks) ->
            if (haltCodes.none { it.equals(code, true) }) return@mapNotNull null
            val arrival = if (clocks.forecast) clocks.arrivalMin else past(clocks.arrivalMin)
            val departureRaw = if (clocks.forecast) clocks.departureMin else past(clocks.departureMin)
            val arrivalForOrder = arrival ?: clocks.arrivalMin
            val departure = departureRaw?.takeIf { dep ->
                arrivalForOrder == null || dep >= arrivalForOrder
            }
            if (arrival == null && departure == null) null
            else code to clocks.copy(arrivalMin = arrival, departureMin = departure)
        }.toMap()
    }

    /** Compact, visibly qualified context for the newest colored card. These are
     * the site's own first clocks beside its timetable, not accepted train events. */
    private fun siteMarkLabel(mark: OrgWebsiteStatus.SiteCardMark): String = when (mark) {
        OrgWebsiteStatus.SiteCardMark.PAST -> "past-marked"
        OrgWebsiteStatus.SiteCardMark.HIGHLIGHTED -> "highlighted"
        OrgWebsiteStatus.SiteCardMark.UPCOMING -> "upcoming"
    }

    fun pageUpdateClock(report: OrgWebsiteStatus.Report): String =
        pageUpdateClock(report.pageUpdatedAt, report.pageClockHasSeconds)

    fun pageUpdateClock(pageUpdatedAt: Long, hasSeconds: Boolean): String =
        if (hasSeconds) RailTime.formatClockSeconds(pageUpdatedAt)
        else RailTime.formatClock(pageUpdatedAt)

    fun pageAgeAtCheck(report: OrgWebsiteStatus.Report, appCheckedAt: Long): String =
        pageAgeAtCheck(report.pageUpdatedAt, report.pageClockHasSeconds, appCheckedAt)

    fun pageAgeAtCheck(pageUpdatedAt: Long, hasSeconds: Boolean, appCheckedAt: Long): String {
        if (appCheckedAt < pageUpdatedAt)
            return "unknown; page clock is ahead of the app clock"
        val upperBound = appCheckedAt - pageUpdatedAt
        if (hasSeconds) {
            return if (upperBound < 60_000L) "${upperBound / 1_000L}s"
            else TableMarker.elapsedLabel(upperBound)
        }
        // The page printed only HH:mm, so its actual update may be anywhere in
        // that minute. Show a range/coarse bound rather than pretending the parsed
        // :00 is an exact upstream update instant.
        val lowerBound = (upperBound - 59_999L).coerceAtLeast(0L)
        return if (upperBound < 120_000L)
            "${lowerBound / 1_000L}–${upperBound / 1_000L}s (minute-only page clock)"
        else "${lowerBound / 60_000L}–${upperBound / 60_000L} min (minute-only page clock)"
    }

    private fun siteClock(time: OrgWebsiteStatus.SiteCardTime): String =
        time.printedText?.takeIf { it.isNotBlank() } ?: RailTime.formatRailMinute(time.minute)

    private fun siteSchedule(time: OrgWebsiteStatus.SiteCardTime): String? =
        time.pageScheduleText?.takeIf { it.isNotBlank() }
            ?: time.pageScheduleMinute?.let(RailTime::formatRailMinute)

    fun latestSiteCardBrief(report: OrgWebsiteStatus.Report?, route: `in`.opentrack.india.model.TrainRoute): String? {
        val claim = report?.latestSiteCard ?: return null
        val name = claim.stationName?.let { RowText.of(it) }
            ?: route.stops.firstOrNull { it.code.equals(claim.code, ignoreCase = true) }
                ?.name?.let { RowText.of(it) } ?: claim.code
        val times = buildList {
            claim.arrival?.let { add("site arr ${siteClock(it)}") }
            claim.departure?.let { add("site dep ${siteClock(it)}") }
        }
        val earlierSameCode = report.siteCards.filter {
            it.code.equals(claim.code, true) && it.cardIndex < claim.cardIndex
        }.maxByOrNull { it.cardIndex }
        val duplicateNote = if (claim.departure == null && earlierSameCode?.departure != null)
            "duplicate arrival-only card; earlier same-code card displayed dep ${siteClock(earlierSameCode.departure)}"
        else null
        return buildString {
            append("Latest .org.in ").append(siteMarkLabel(claim.mark)).append(" card · ")
                .append(name).append(" (").append(claim.code).append(")")
            if (times.isNotEmpty()) append(" · ").append(times.joinToString(" · "))
            else append(" · no parseable displayed clock")
            claim.platform?.let { append(" · PF ").append(it) }
            claim.statusText?.let { append(" · website status ‘").append(it).append("’") }
            duplicateNote?.let { append(" · ").append(it) }
            append(" · website claim, not a verified actual")
        }
    }

    /** A short version for Battery mode's collapsed card: the upstream publication
     * clock and this app's response-receipt clock are both visible, not conflated. */
    fun latestSiteCardBatteryBrief(report: OrgWebsiteStatus.Report?,
        route: `in`.opentrack.india.model.TrainRoute, appCheckedAt: Long): String? {
        val claim = report?.latestSiteCard ?: return null
        val brief = latestSiteCardBrief(report, route) ?: return null
        return listOfNotNull(brief, claim.qualificationNote,
            "Page updated ${pageUpdateClock(report)} IST",
            "app response received ${RailTime.formatClockSeconds(appCheckedAt)} IST",
            "page age ${pageAgeAtCheck(report, appCheckedAt)} at check")
            .joinToString(" · ")
    }

    /** Full freshness/provenance copy. App check time and upstream page-update
     * time stay distinct; neither is the time of a train event. */
    fun latestSiteCardSummary(report: OrgWebsiteStatus.Report?,
        route: `in`.opentrack.india.model.TrainRoute, appCheckedAt: Long): String? {
        val claim = report?.latestSiteCard ?: return null
        val brief = latestSiteCardBrief(report, route) ?: return null
        return buildString {
            append(brief)
            claim.arrival?.let { siteSchedule(it)?.let { schedule ->
                append(" · page arr schedule ").append(schedule)
            } }
            claim.departure?.let { siteSchedule(it)?.let { schedule ->
                append(" · page dep schedule ").append(schedule)
            } }
            append(". Page updated ").append(pageUpdateClock(report))
                .append(" IST; app response received ").append(RailTime.formatClockSeconds(appCheckedAt))
                .append(" IST; page age ").append(pageAgeAtCheck(report, appCheckedAt))
                .append(" at check. This does not establish a train event or current position.")
            claim.qualificationNote?.let { append(" ").append(it) }
        }
    }

    /** Full card-level copy for Source details: duplicates, site status/platform badges,
     * both site clocks and their grey site schedules, with no event/position promotion. */
    fun allSiteCardsSummary(report: OrgWebsiteStatus.Report?): String? = report?.let {
        allSiteCardsSummary(it.siteCards, it.pageUpdatedAt, it.pageClockHasSeconds)
    }

    /** The same card summary also works when the page answered but no event qualified. */
    fun allSiteCardsSummary(
        cards: List<OrgWebsiteStatus.SiteCardClaim>,
        pageUpdatedAt: Long? = null,
        pageClockHasSeconds: Boolean = false,
        appCheckedAt: Long? = null,
    ): String? {
        if (cards.isEmpty()) return null
        fun mark(mark: OrgWebsiteStatus.SiteCardMark) = when (mark) {
            OrgWebsiteStatus.SiteCardMark.PAST -> "green · past-marked"
            OrgWebsiteStatus.SiteCardMark.HIGHLIGHTED -> "blue · highlighted"
            OrgWebsiteStatus.SiteCardMark.UPCOMING -> "white · upcoming"
        }
        fun time(label: String, value: OrgWebsiteStatus.SiteCardTime?): String? = value?.let {
            buildString {
                append(label).append(' ').append(siteClock(it))
                siteSchedule(it)?.let { schedule -> append(" (page schedule ").append(schedule).append(')') }
            }
        }
        val counts = cards.groupingBy { it.code.uppercase(Locale.ROOT) }.eachCount()
        return buildString {
            append(".org.in visible cards · website display only; not NTES/railway actuals, movement, or current position:\n")
            cards.sortedBy { it.cardIndex }.forEach { card ->
                val name = card.stationName?.let { RowText.of(it) } ?: card.code
                append("• ").append(name).append(" (").append(card.code).append(") · ")
                    .append(mark(card.mark))
                val duplicateIndex = cards.count {
                    it.code.equals(card.code, true) && it.cardIndex <= card.cardIndex
                }
                val duplicateCount = counts[card.code.uppercase(Locale.ROOT)] ?: 0
                if (duplicateCount > 1)
                    append(" · card ").append(duplicateIndex).append(" of ").append(duplicateCount)
                card.platform?.let { append(" · PF ").append(it) }
                card.statusText?.let { append(" · status ‘").append(it).append("’") }
                listOfNotNull(time("ARRIVAL", card.arrival), time("DEPARTURE", card.departure))
                    .takeIf { it.isNotEmpty() }?.let { append(" · ").append(it.joinToString(" · ")) }
                card.qualificationNote?.let { append(" · ").append(it) }
                append('\n')
            }
            append("These page clocks and badges are kept separate from the qualifying source-event rows.")
            pageUpdatedAt?.let { pageAt ->
                append(" Page updated ").append(pageUpdateClock(pageAt, pageClockHasSeconds))
                    .append(" IST")
                if (appCheckedAt != null) {
                    append("; app response received ")
                        .append(RailTime.formatClockSeconds(appCheckedAt)).append(" IST; page age ")
                        .append(pageAgeAtCheck(pageAt, pageClockHasSeconds, appCheckedAt))
                        .append(" at check")
                } else {
                    append("; app response time for these retained cards is unavailable")
                }
                append(". Neither timestamp establishes train movement or current position.")
            }
        }
    }

    fun rows(model: TrackModel.View, report: OrgWebsiteStatus.Report): TrackModel.View =
        model.copy(rows = model.rows.map { row ->
            if (row.code in report.rows && row.source == TrackModel.MinuteSource.REPORTED)
                row.copy(source = TrackModel.MinuteSource.SITE_REPORTED)
            else row
        })

    fun selected(source: String?): Boolean = source == PublicPageChecks.ORG
}
