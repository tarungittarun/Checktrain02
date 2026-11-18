package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus

/** Supplemental website-card clocks for a covered halt where the selected
 * NTES view has no accepted actual. Never write these into the Act column, the
 * official snapshot or the marker. The website does not state the run date. */
internal object WebsiteGapUi {
    private data class ClaimText(val brief: String)

    /** Concise, visible row text. Detailed page metadata is exposed to accessibility
     * and Source details instead of being squeezed into a clipped timetable row. */
    fun briefCaption(stop: TrackModel.Stop, site: OrgWebsiteStatus.SiteClocks?,
                     saved: Boolean = false): String? = claimText(stop, site, saved)?.brief

    /** Full source-local description. The page's own schedule, platform, status and
     * visual card mark remain distinguishable from a railway/event report. */
    fun caption(stop: TrackModel.Stop, site: OrgWebsiteStatus.SiteClocks?,
                saved: Boolean = false): String? {
        val claim = claimText(stop, site, saved) ?: return null
        val clocks = site ?: return claim.brief
        val details = buildList {
            clocks.arrivalScheduleMin?.let {
                add("page arr schedule ${RailTime.formatRailMinuteBare(it)}")
            }
            clocks.departureScheduleMin?.let {
                add("page dep schedule ${RailTime.formatRailMinuteBare(it)}")
            }
            clocks.platform?.takeIf { it.isNotBlank() }?.let { add("website PF $it") }
            clocks.statusText?.takeIf { it.isNotBlank() }?.let { add("website status $it") }
            clocks.mark?.let { add("website card ${markLabel(it)}") }
            if (clocks.cardCount > 1) add("${clocks.cardCount} same-code website cards")
            if (clocks.latestCardArrivalOnly)
                add("latest duplicate card has arrival only")
        }
        return if (details.isEmpty()) claim.brief
        else claim.brief + details.joinToString(separator = " · ", prefix = " · ")
    }

    private fun markLabel(mark: OrgWebsiteStatus.SiteCardMark): String = when (mark) {
        OrgWebsiteStatus.SiteCardMark.PAST -> "green/past-marked"
        OrgWebsiteStatus.SiteCardMark.HIGHLIGHTED -> "blue/highlighted"
        OrgWebsiteStatus.SiteCardMark.UPCOMING -> "white/upcoming"
    }

    private fun claimText(stop: TrackModel.Stop, site: OrgWebsiteStatus.SiteClocks?,
                          saved: Boolean): ClaimText? {
        if (site == null || !site.forecast &&
            !(stop.state == TrackModel.State.PASSED || stop.isAnchor) && site.mark == null) return null
        val official = stop.source == TrackModel.MinuteSource.REPORTED
        val arr = site.arrivalMin.takeUnless { official && stop.showArr != null }
        val dep = site.departureMin.takeUnless { official && stop.showDep != null }
        if (arr == null && dep == null) return null
        val day = RouteClockUi.departureDay(dep, arr)
        val brief = buildString {
            append(if (site.forecast) "Website forecast" else "Website claim")
            if (saved) append(" (saved)")
            if (arr != null) append(" · arr ")
                .append(if (site.arrivalAfterPageUpdate) "~" else "")
                .append(RailTime.formatRailMinuteBare(arr))
            if (dep != null) append(" · dep ")
                .append(if (site.departureAfterPageUpdate) "~" else "")
                .append(RailTime.formatRailMinuteBare(dep))
            if (day != null) append(" · ").append(day)
        }
        return ClaimText(brief)
    }
}
