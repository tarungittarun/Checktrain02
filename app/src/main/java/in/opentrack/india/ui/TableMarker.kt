package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel

/** A marker at a REPORTED HALT, never an interpolated current position. A website card is a
 * source-attributed claim (amber), not a verified actual (blue). No timetable row earns a train. */
object TableMarker {
    data class Halt(val code: String, val caption: String, val website: Boolean)

    fun atHalt(rows: List<TrackModel.Stop>, anchorCode: String?, originDay: Long,
               nowMillis: Long): Halt? {
        val row = rows.firstOrNull { it.code.equals(anchorCode, true) } ?: return null
        val website = row.source == TrackModel.MinuteSource.SITE_REPORTED
        if (!website && row.source != TrackModel.MinuteSource.REPORTED) return null
        val minute = row.showDep ?: row.showArr
        // Feed age is NOT passage age. If no reported minute exists, print no age at the marker.
        val age = minute?.let { nowMillis - RailTime.railMinuteToEpochMillis(originDay, it) }
            ?.takeIf { it >= 0 }
        val ageText = when {
            age == null || age < 60_000L -> ""
            age < 3_600_000L -> " · ${age / 60_000L}m ago"
            else -> " · ${age / 3_600_000L}h ${(age % 3_600_000L) / 60_000L}m ago"
        }
        return Halt(row.code, (if (website) "Site's last past stop" else "Last reported stop") + ageText,
            website)
    }
}
