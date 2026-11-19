package `in`.opentrack.india.ui

import `in`.opentrack.india.model.TrainRoute

/**
 * WIMT may report timed stations that are absent from the selected timetable. Keep those
 * source-reported rows visible without inserting them into the scheduled route or train position.
 */
object WimtSupplementalRows {
    private const val MAX_VISIBLE_ROWS = 4

    fun text(sourceRows: Map<String, String>, route: TrainRoute): String? {
        val scheduledCodes = route.stops.mapTo(mutableSetOf()) { it.code.uppercase() }
        val additional = sourceRows.entries
            .filter { it.key.uppercase() !in scheduledCodes }
            .sortedBy { it.key.uppercase() }
        if (additional.isEmpty()) return null

        return buildString {
            append("WIMT .in source-reported station events outside this timetable:\n")
            additional.take(MAX_VISIBLE_ROWS).forEach { append("• ").append(it.value).append('\n') }
            if (additional.size > MAX_VISIBLE_ROWS) {
                append("… ${additional.size - MAX_VISIBLE_ROWS} more in Info.\n")
            }
            append("Source claims only; not scheduled route stops, GPS, or verified position.")
        }
    }
}
