package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Displayed fallback is not a new source choice. A deliberate Refresh retries the traveller's
 * saved admitted source before NTES; retired website preferences normalize to NTES. The opt-in
 * timer stays on the displayed fallback after refusal rather than repeating a failed request. */
object RefreshTargetPolicy {
    /** The initial background survey must not ask the website for an NTES view. */
    fun surveyWebsite(active: String?, alreadyChecked: Boolean): Boolean =
        active == PublicPageChecks.ORG && !alreadyChecked

    /** A first-load refusal or unparsed page must not leave an already answered official source
     * hidden behind a blank website table. Parsed site clocks are comparison content even when no
     * event qualifies. This changes only the displayed source, never the saved preference. */
    fun afterWebsiteSurvey(displayed: String?, siteContentAvailable: Boolean,
                           officialActualsAvailable: Boolean): String? =
        if (displayed == PublicPageChecks.ORG && !siteContentAvailable && officialActualsAvailable)
            TrackingSources.OFFICIAL else displayed

    /** A fresh page fetch does not freshen an old website station event. During a
     * deliberate website Refresh, use an already-answered NTES report as fallback
     * only when the site event is stale and NTES has a meaningfully newer event. */
    fun websiteEventNeedsOfficialFallback(siteEventAgeMillis: Long?,
                                          officialEventAgeMillis: Long?): Boolean =
        siteEventAgeMillis != null && siteEventAgeMillis >= SourceFreshness.STALE_AFTER_MS &&
            SourceRecency.officialIsNewer(officialEventAgeMillis, siteEventAgeMillis)

    /** The traveler-facing summary may leave a legacy website selection for NTES, but never
     * offers the unqualified website as an alternative to an admitted source. */
    fun alternateSourceTarget(displayed: String?): String? =
        if (displayed == PublicPageChecks.ORG) TrackingSources.OFFICIAL else null

    fun target(displayed: String?, preferred: String?, auto: Boolean): String {
        val active = TrackingSources.valid(displayed)
        val saved = TrackingSources.valid(preferred)
        return if (!auto && active == TrackingSources.OFFICIAL && saved != TrackingSources.OFFICIAL)
            saved else active
    }
}
