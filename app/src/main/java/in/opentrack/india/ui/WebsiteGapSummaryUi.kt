package `in`.opentrack.india.ui

/** Short first-glance copy only. The full website update label and comparison
 * qualifications remain in source details and the diary. */
object WebsiteGapSummaryUi {
    private val missingDestination = Regex(
        "website has no ([A-Z0-9]+) arrival row\\. Last displayed: ([A-Z0-9]+)",
    )
    private val routeStopCount = Regex("selected timetable route has ([0-9]+) stops?")

    fun concise(full: String): String {
        val missing = missingDestination.find(full)
        if (missing != null) return "No ${missing.groupValues[1]} site arrival · last listed " +
            "${missing.groupValues[2]} · run/position unknown."
        if (full.startsWith("Website route "))
            return "Website route mismatch · detached claim · position unknown."
        routeStopCount.find(full)?.let {
            return "Selected route has too few stops to match website cards."
        }
        return when {
            "visible run date does not match" in full ->
                "Website date mismatch · site cards not attached."
            "endpoints do not match the selected timetable" in full ->
                "Website route mismatch · site cards not attached."
            "not scheduled for this origin day" in full ->
                "Train not scheduled this run day · no site event."
            "no origin departure time" in full ->
                "Origin schedule missing · website run unverified."
            "no date selector and the selected origin run is not unambiguously active" in full ->
                "Website run window unverified · no event admitted."
            "reports that this train run is not active" in full ->
                "Website run inactive · no station event admitted."
            "update clock is missing, future, or too old" in full ->
                "Website update clock invalid or stale · no new event."
            "no past-marked, schedule-paired stop claim qualified" in full ->
                "No qualifying website stop event · timetable shown."
            full.length <= 84 -> full
            else -> "Website comparison only · see Source details."
        }
    }
}
