package `in`.opentrack.india.ui

import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Preferences use exact source keys, never shortened display names.
 * The main and Settings pickers offer one WIMT choice: the .org.in website. The .in live feed and
 * web cache remain separate internal evidence sources, but are not separate user-facing choices. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    const val WIMT = WimtLiveProvider.SOURCE_NAME
    val options = listOf(OFFICIAL, PublicPageChecks.ORG)

    enum class PickerAction { IGNORE, REFRESH, SELECT }

    /** A dropdown choice is a user action even when it arrives through Accessibility or UiAutomator
     * rather than MotionEvent. Public-page selections always make a fresh request; cached comparison
     * state must never suppress the traveller's explicit choice. */
    fun pickerAction(name: String, active: String?, state: String?): PickerAction = when {
        name == active -> PickerAction.IGNORE
        name in PublicPageChecks.names -> PickerAction.REFRESH
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

    /** Resolve legacy .in/cache defaults to the sole WIMT choice without merging provider data. */
    fun valid(name: String?): String {
        val source = name ?: return OFFICIAL
        return when (source) {
            OFFICIAL, PublicPageChecks.ORG -> source
            WIMT, WimtCandidate.SOURCE -> PublicPageChecks.ORG
            else -> OFFICIAL
        }
    }
}
