package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Preferences use the exact selector key, never the shortened display name.
 * The website and web cache are NOT the Where Is My Train phone app. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    // The experimental web cache is an explicit manual comparison choice, not
    // the Google phone app and not an automatically refreshed/verified feed.
    val options = listOf(OFFICIAL, PublicPageChecks.ORG, WimtCandidate.SOURCE)
    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        PublicPageChecks.ORG -> "Where Is My Train · website"
        WimtCandidate.SOURCE -> "WIMT web cache · experimental"
        else -> name
    }
    fun valid(name: String?): String = name?.takeIf(options::contains) ?: OFFICIAL
}
