package `in`.opentrack.india.ui

import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** User-selectable sources are limited to dated NTES and direct WIMT `.in` live-status reports.
 * The `.org.in` website and experimental web-cache remain separate diagnostics, not user sources. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    const val WIMT = WimtLiveProvider.SOURCE_NAME
    val options = listOf(OFFICIAL, WIMT)

    enum class PickerAction { IGNORE, REFRESH, SELECT }

    /** A dropdown choice is a user action even when it arrives through Accessibility or UiAutomator
     * rather than MotionEvent. The direct `.in` source always makes a fresh request; cached
     * comparison state cannot suppress the traveller's explicit choice. */
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
            OFFICIAL, WIMT -> source
            // Unqualified website/cache preferences are no longer selected for travellers.
            // Normalize them to NTES, never alias them to the direct WIMT `.in` feed.
            PublicPageChecks.ORG, WimtCandidate.SOURCE -> OFFICIAL
            else -> OFFICIAL
        }
    }
}
