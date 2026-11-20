package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel

/** Compact first-glance wording built from the latest station event, never the feed stamp. */
object SourceEventSummary {
    data class Event(
        val code: String,
        val name: String,
        val kind: String,
        val minute: Int,
        val eventAtMillis: Long,
        val ageMillis: Long,
    )

    fun latestReported(
        rows: List<TrackModel.Stop>,
        originEpochDay: Long,
        nowMillis: Long,
    ): Event? {
        val events = rows.asSequence()
            .filter { it.source == TrackModel.MinuteSource.REPORTED }
            .flatMap { row -> sequenceOf(
                row.showArr?.let { Triple(row, "arrival", it) },
                row.showDep?.let { Triple(row, "departure", it) },
            ).filterNotNull() }
            .mapNotNull { (row, kind, minute) ->
                val at = RailTime.railMinuteToEpochMillis(originEpochDay, minute)
                if (at <= 0L || at > nowMillis) null
                else Event(row.code, row.name, kind, minute, at, nowMillis - at)
            }
        return events.maxByOrNull {
            it.eventAtMillis * 2L + if (it.kind == "departure") 1L else 0L
        }
    }

    fun line(source: String, event: Event?, nowMillis: Long, timeNote: String): String {
        if (event == null) return "$source · no dated station event; position unknown."
        return "$source · ${event.code} ${event.kind} ${RailTime.formatRailMinute(event.minute)} · " +
            "${SourceFreshness.label(event.eventAtMillis, nowMillis)} · $timeNote"
    }
}
