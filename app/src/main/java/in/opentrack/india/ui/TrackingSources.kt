package `in`.opentrack.india.ui

import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Preferences use exact source keys, never shortened display names.
 * The direct WIMT `.in` JSON feed and the `.org.in` website are distinct user-facing sources.
 * The experimental web-cache candidate and Google's WIMT phone app are not aliases for either. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    const val WIMT = WimtLiveProvider.SOURCE_NAME
    val options = listOf(OFFICIAL, WIMT, PublicPageChecks.ORG)

    enum class PickerAction { IGNORE, REFRESH, SELECT }

    /** A dropdown choice is a user action even when it arrives through Accessibility or UiAutomator
     * rather than MotionEvent. Direct `.in` and public-page selections always make a fresh request;
     * cached comparison state must never suppress the traveller's explicit choice. */
    fun pickerAction(name: String, active: String?, state: String?): PickerAction = when {
        name == active -> PickerAction.IGNORE
        name == WIMT || name in PublicPageChecks.names -> PickerAction.REFRESH
        state == null || state !in setOf("live", "last-known", "candidate") -> PickerAction.REFRESH
        else -> PickerAction.SELECT
    }

    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        WIMT -> "WIMT .in live-status feed"
        PublicPageChecks.ORG -> "Where Is My Train · website"
        WimtCandidate.SOURCE -> "WIMT web cache · experimental"
        else -> name
    }

    /** Normalize only recognized user-facing source keys; never alias `.in` to `.org.in`. */
    fun valid(name: String?): String {
        val source = name ?: return OFFICIAL
        return when (source) {
            OFFICIAL, WIMT, PublicPageChecks.ORG -> source
            // The experimental cache is not a selectable source. An old cache preference must
            // fall back to NTES, not silently turn into a request to the unrelated `.org.in` site.
            WimtCandidate.SOURCE -> OFFICIAL
            else -> OFFICIAL
        }
    }
}
