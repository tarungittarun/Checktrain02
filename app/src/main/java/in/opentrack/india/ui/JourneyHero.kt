package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel

/** A short, truthful first glance. The source's full sentence stays in the
 * accessible description and Info; a scheduled next minute is never an ETA. */
object JourneyHero {
    data class Official(val title: String, val next: String)

    fun official(rows: List<TrackModel.Stop>, anchorCode: String?): Official? {
        val index = rows.indexOfFirst { it.code.equals(anchorCode, true) &&
            it.source == TrackModel.MinuteSource.REPORTED }
        if (index < 0) return null
        val anchor = rows[index]
        if (anchor.showDep == null && anchor.showArr == null) return null
        val title = (if (anchor.showDep != null) "Departed " else "Arrived at ") +
            RowText.of(anchor.name)
        val next = rows.getOrNull(index + 1)?.let { stop ->
            buildString {
                append(RowText.of(stop.name))
                (stop.schedArr ?: stop.schedDep)?.let {
                    append(" · ").append(RailTime.formatRailMinute(it))
                }
                append("\nSchedule only · arrival unconfirmed")
            }
        } ?: "End of route · no next stop"
        return Official(title, next)
    }
}
