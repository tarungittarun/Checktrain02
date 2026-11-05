package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** User-initiated link to the independent website. The external browser owns the entire
 * session; this URL is not an app HTTP transport or an in-app tracking response. */
object BrowserWebsiteLink {
    fun url(number: String): String? =
        PublicPageChecks.url(PublicPageChecks.ORG, number, 0L, 0L)
}
