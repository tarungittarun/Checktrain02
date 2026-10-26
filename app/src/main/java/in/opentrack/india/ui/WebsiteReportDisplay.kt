package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** A parsed past-card timeline is usable in the *selected website's* own view.
 * It is not an independently run-verified NTES actual, and a page clock is not
 * a train event. Only [OrgWebsiteStatus.parse]'s guarded cells may be painted. */
object WebsiteReportDisplay {
    const val STALE_BADGE = "WEBSITE · mapped arrival · stale · run inferred"

    fun usable(state: String?, answered: Boolean, report: OrgWebsiteStatus.Report?): Boolean =
        state == "comparison" && answered && report != null && report.rows.isNotEmpty()

    /** Stale past cards remain labelled as old site data; stale white forecasts vanish. */
    fun displayedClocks(report: OrgWebsiteStatus.Report?, now: Long):
        Map<String, OrgWebsiteStatus.SiteClocks> = report?.siteClocks.orEmpty().filterValues {
            !it.forecast || now - (report?.pageUpdatedAt ?: 0L) in 0L..300_000L
        }

    fun rows(model: TrackModel.View, report: OrgWebsiteStatus.Report): TrackModel.View =
        model.copy(rows = model.rows.map { row ->
            if (row.code in report.rows && row.source == TrackModel.MinuteSource.REPORTED)
                row.copy(source = TrackModel.MinuteSource.SITE_REPORTED)
            else row
        })

    fun selected(source: String?): Boolean = source == PublicPageChecks.ORG
}
