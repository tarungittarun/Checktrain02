package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** The short failure display names a failed CONNECTION, never an unchanged stop. */
object RefreshFlash {
    fun connectionError(source: String, state: Pair<String, String>?,
                        official: OfficialRequestReceipt?, websiteAnswered: Boolean?): String? {
        if (source == TrackingSources.OFFICIAL &&
            official?.kind == OfficialRequestReceipt.Kind.REQUEST_FAILED)
            return "Connection failed"
        val detail = state?.second.orEmpty()
        if (source == PublicPageChecks.ORG && websiteAnswered == true) return null
        return when {
            detail.contains("HTTP 403", true) || detail.contains("HTTP 429", true) -> "Source blocked request"
            detail.contains("timed out", true) || detail.contains("timeout", true) -> "Source timed out"
            detail.contains("Request failed", true) || detail.contains("could not connect", true) ||
                detail.contains("did not answer", true) -> "Connection failed"
            else -> null
        }
    }
}
