package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Displayed fallback is not a new source choice. A deliberate Refresh retries the traveller's
 * selected website before NTES; the opt-in timer stays on the displayed fallback after refusal,
 * rather than sending a denied website another request every minute. Choosing NTES explicitly
 * changes the saved preference and makes NTES first again. */
object RefreshTargetPolicy {
    fun target(displayed: String?, preferred: String?, auto: Boolean): String {
        val active = displayed ?: TrackingSources.OFFICIAL
        return if (!auto && active == TrackingSources.OFFICIAL &&
            TrackingSources.valid(preferred) == PublicPageChecks.ORG) PublicPageChecks.ORG
        else active
    }
}
