package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks

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

    /** The route table is a history, NOT the site's forecast board. A white card
     * for the following halt is not an arrival, even when prefixed with '~'. The
     * latest past card may also print a departure later than its own page stamp:
     * keep the arrival, but never show that departure before it occurred. Compare
     * against both the page and device clocks, using the selected run's IST day.
     * Fail closed if the site's anchor cannot be located on this route. */
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
        // A later source-coloured card may be ambiguous (for example, its clocks
        // repeat the page timetable). Keep it visible in the WEBSITE column, not in
        // Report.rows or the marker, so a newer page claim is not hidden merely
        // because it failed the event gate.
        val latestClaim = report.latestSiteCard
        val displayedClaims = report.siteClocks.toMutableMap()
        if (latestClaim != null) displayedClaims[latestClaim.code] = OrgWebsiteStatus.SiteClocks(
            latestClaim.arrival?.minute, latestClaim.departure?.minute, forecast = false)
        return displayedClaims.mapNotNull { (code, clocks) ->
            val index = haltCodes.indexOfFirst { it.equals(code, ignoreCase = true) }
            val isLatestClaim = latestClaim?.code?.equals(code, ignoreCase = true) == true
            if ((index !in 0..lastIndex && !isLatestClaim) || clocks.forecast) return@mapNotNull null
            val arrival = past(clocks.arrivalMin)
            val departure = past(clocks.departureMin)?.takeIf { dep ->
                // Defence against older/injected reports: never display a
                // departure before the SAME card's arrival, even if the parser
                // once retained a raw timetable echo in siteClocks.
                (arrival ?: report.rows[code]?.actualArrivalMin)?.let { dep >= it } ?: true
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
    }

    fun pageUpdateClock(report: OrgWebsiteStatus.Report): String =
        if (report.pageClockHasSeconds) RailTime.formatClockSeconds(report.pageUpdatedAt)
        else RailTime.formatClock(report.pageUpdatedAt)

    fun pageAgeAtCheck(report: OrgWebsiteStatus.Report, appCheckedAt: Long): String {
        if (appCheckedAt < report.pageUpdatedAt)
            return "unknown; page clock is ahead of the app clock"
        val upperBound = appCheckedAt - report.pageUpdatedAt
        if (report.pageClockHasSeconds) {
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

    fun latestSiteCardBrief(report: OrgWebsiteStatus.Report?, route: `in`.opentrack.india.model.TrainRoute): String? {
        val claim = report?.latestSiteCard ?: return null
        val name = route.stops.firstOrNull { it.code.equals(claim.code, ignoreCase = true) }
            ?.name?.let { RowText.of(it) } ?: claim.code
        val times = buildList {
            claim.arrival?.let { add("site arr ${RailTime.formatRailMinute(it.minute)}") }
            claim.departure?.let { add("site dep ${RailTime.formatRailMinute(it.minute)}") }
        }
        return buildString {
            append("Latest .org.in ").append(siteMarkLabel(claim.mark)).append(" card · ")
                .append(name).append(" (").append(claim.code).append(")")
            if (times.isNotEmpty()) append(" · ").append(times.joinToString(" · "))
            else append(" · no parseable displayed clock")
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
            claim.arrival?.pageScheduleMinute?.let {
                append(" · page arr schedule ").append(RailTime.formatRailMinute(it))
            }
            claim.departure?.pageScheduleMinute?.let {
                append(" · page dep schedule ").append(RailTime.formatRailMinute(it))
            }
            append(". Page updated ").append(pageUpdateClock(report))
                .append(" IST; app response received ").append(RailTime.formatClockSeconds(appCheckedAt))
                .append(" IST; page age ").append(pageAgeAtCheck(report, appCheckedAt))
                .append(" at check. This does not establish a train event or current position.")
            claim.qualificationNote?.let { append(" ").append(it) }
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
