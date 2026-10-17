package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Preferences use the exact selector key, never the shortened display name.
 * The website and web cache are NOT the Where Is My Train phone app. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    val options = listOf(OFFICIAL, PublicPageChecks.ORG, "runningstatus.in", "confirmtkt",
        "erail", WimtCandidate.SOURCE, PublicPageChecks.RAIL_YATRI, PublicPageChecks.IXIGO)
    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        PublicPageChecks.ORG -> "Website · unverified"
        WimtCandidate.SOURCE -> "WIMT web cache · candidate"
        else -> name
    }
    fun valid(name: String?): String = name?.takeIf(options::contains) ?: OFFICIAL
}
