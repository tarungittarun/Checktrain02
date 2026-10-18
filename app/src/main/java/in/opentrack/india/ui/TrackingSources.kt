package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Preferences use the exact selector key, never the shortened display name.
 * The website and web cache are NOT the Where Is My Train phone app. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    // Only these two are traveller-selectable for now. Other probes remain
    // internal diagnostics/fallbacks; their pages are not phone-app telemetry.
    val options = listOf(OFFICIAL, PublicPageChecks.ORG)
    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        PublicPageChecks.ORG -> "Where Is My Train · website (unverified)"
        else -> name
    }
    fun valid(name: String?): String = name?.takeIf(options::contains) ?: OFFICIAL
}
