package `in`.opentrack.india.ui

/** A number alone is not a route identity: offline editions can assign the
 * same number to another origin run. A provisional route must contain the
 * selected boarding and alighting stops in the passenger's direction. */
object JourneyRouteGate {
    fun covers(codes: List<String>, from: String, to: String): Boolean {
        if (from.isBlank() || to.isBlank()) return true // cannot disprove an unspecified end
        val start = codes.indexOfFirst { it.equals(from, true) }
        val end = codes.indexOfLast { it.equals(to, true) }
        return start >= 0 && end > start
    }

    /** Search-result endpoints are the passenger's boarding/alighting segment, not the train's
     * origin/terminus. Warn only when that segment is absent from the displayed route; its absence
     * is evidence of an incomplete route, not by itself proof that the train number is wrong. */
    fun missingJourneyNote(trainNo: String, codes: List<String>, from: String, to: String): String? {
        if (from.isBlank() || to.isBlank() || covers(codes, from, to)) return null
        return "Selected search segment ${from.uppercase()} → ${to.uppercase()} is not covered by " +
            "the timetable shown for train ${trainNo.trim()}; station times may be incomplete."
    }
}
