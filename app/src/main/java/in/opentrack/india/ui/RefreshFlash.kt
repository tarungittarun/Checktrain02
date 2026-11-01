package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** The short failure display names a failed CONNECTION, never an unchanged stop. */
object RefreshFlash {
    fun connectionError(source: String, state: Pair<String, String>?,
                        official: OfficialRequestReceipt?, websiteAnswered: Boolean?): String? {
        val detail = state?.second.orEmpty()
        if (source == PublicPageChecks.ORG && websiteAnswered == true) return null
        val reason = detail + " " + official?.reason.orEmpty()
        if (source == PublicPageChecks.ORG) {
            // A refused request is not the same as a rate limit. Do not guess the reason
            // for the refusal, but make the HTTP outcome visible on the phone itself.
            if (detail.contains("HTTP 403", true)) return "Website refused request (HTTP 403)"
            if (detail.contains("HTTP 429", true)) return "Website rate limited (HTTP 429)"
        }
        if (source == TrackingSources.OFFICIAL &&
            official?.kind == OfficialRequestReceipt.Kind.REQUEST_FAILED) return when {
            reason.contains("HTTP 403", true) || reason.contains("HTTP 429", true) -> "Source blocked request"
            reason.contains("timed out", true) || reason.contains("timeout", true) -> "Source timed out"
            else -> "Connection failed"
        }
        return when {
            detail.contains("HTTP 403", true) || detail.contains("HTTP 429", true) -> "Source blocked request"
            detail.contains("timed out", true) || detail.contains("timeout", true) -> "Source timed out"
            detail.contains("Request failed", true) || detail.contains("could not connect", true) ||
                detail.contains("did not answer", true) -> "Connection failed"
            else -> null
        }
    }
}
