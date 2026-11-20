package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute

/**
 * WIMT can return station rows that are absent from the selected timetable. Keep those rows visible
 * below the route table, but preserve the distinction between its schedule clocks and event clocks.
 * These rows never enter TrackModel or alter the reported position.
 */
object WimtSupplementalRows {
    private const val MAX_VISIBLE_ROWS = 4

    fun text(
        sourceEvents: Map<String, String>,
        wimtScheduleStops: List<StopRow>,
        route: TrainRoute,
    ): String? {
        val routeCodes = route.stops.mapTo(mutableSetOf()) { it.code.uppercase() }
        val schedules = wimtScheduleStops
            .filter { it.code.uppercase() !in routeCodes }
            .associateBy { it.code.uppercase() }
        val eventRows = sourceEvents.entries
            .filter { it.key.uppercase() !in routeCodes }
            .associate { it.key.uppercase() to it.value }
        // Keep the provider's schedule order; append event-only codes in their source-map order.
        val codes = (schedules.keys + eventRows.keys.filterNot { it in schedules }).distinct()
        if (codes.isEmpty()) return null

        return buildString {
            append("WIMT stations outside this timetable:\n")
            codes.take(MAX_VISIBLE_ROWS).forEach { code ->
                val schedule = schedules[code]
                val event = eventRows[code]
                val name = schedule?.name?.takeIf { it.isNotBlank() && !it.equals(code, true) }
                append("• ").append(name ?: code).append(" (").append(code).append(")")
                if (event != null) {
                    append(" · source event: ").append(event.substringAfter(" · ", event))
                }
                val scheduleClocks = listOfNotNull(
                    schedule?.arrivalMin?.let { "arr ${RailTime.formatRailMinuteBare(it)}" },
                    schedule?.departureMin?.let { "dep ${RailTime.formatRailMinuteBare(it)}" },
                )
                if (scheduleClocks.isNotEmpty()) {
                    append(" · WIMT schedule: ").append(scheduleClocks.joinToString(" / "))
                }
                if (event == null && scheduleClocks.isEmpty()) append(" · no dated event clock")
                append('\n')
            }
            if (codes.size > MAX_VISIBLE_ROWS) {
                append("… ${codes.size - MAX_VISIBLE_ROWS} more in Info.\n")
            }
            append("Schedule clocks are not events. Source events are claims, not GPS or verified position.")
        }
    }
}
