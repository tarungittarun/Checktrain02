package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Displayed fallback is not a new source choice. A deliberate Refresh retries the traveller's
 * saved non-NTES source before NTES; the opt-in timer stays on the displayed fallback after refusal,
 * rather than repeating a failed request every minute. Choosing NTES explicitly makes it first again. */
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

    /** The summary card can switch only between the two sources it actually compares. */
    fun alternateSourceTarget(displayed: String?): String? = when (displayed) {
        PublicPageChecks.ORG -> TrackingSources.OFFICIAL
        TrackingSources.OFFICIAL -> PublicPageChecks.ORG
        else -> null
    }

    fun target(displayed: String?, preferred: String?, auto: Boolean): String {
        val active = displayed ?: TrackingSources.OFFICIAL
        val saved = TrackingSources.valid(preferred)
        return if (!auto && active == TrackingSources.OFFICIAL && saved != TrackingSources.OFFICIAL)
            saved else active
    }
}
