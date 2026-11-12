package `in`.opentrack.india.ui

import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Preferences use the exact selector key, never the shortened display name.
 * The live API, .org.in website and .in cache are separate WIMT data sources. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    const val WIMT = WimtLiveProvider.SOURCE_NAME
    // The cache is an explicit manual comparison choice, not the live API or .org.in website.
    val options = listOf(OFFICIAL, WIMT, PublicPageChecks.ORG, WimtCandidate.SOURCE)
    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        WIMT -> "WIMT .in live-status feed"
        PublicPageChecks.ORG -> "Where Is My Train · website"
        WimtCandidate.SOURCE -> "WIMT web cache · experimental"
        else -> name
    }
    fun valid(name: String?): String = name?.takeIf(options::contains) ?: OFFICIAL
}
