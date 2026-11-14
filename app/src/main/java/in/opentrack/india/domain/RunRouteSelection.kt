package `in`.opentrack.india.domain

import `in`.opentrack.india.model.TrainRoute

/** Route-number matches are candidates, not proof that a route covers the traveller's selected leg. */
object RunRouteSelection {
    /** Require the selected boarding and alighting stops in travel order. A missing endpoint cannot
     * disprove a route, preserving the callers that genuinely have no selected segment yet. */
    fun covers(codes: List<String>, fromCode: String, toCode: String): Boolean {
        if (fromCode.isBlank() || toCode.isBlank()) return true
        val from = codes.indexOfFirst { it.equals(fromCode, ignoreCase = true) }
        val to = codes.indexOfLast { it.equals(toCode, ignoreCase = true) }
        return from >= 0 && to > from
    }

    fun covers(route: TrainRoute, fromCode: String, toCode: String): Boolean =
        covers(route.stops.map { it.code }, fromCode, toCode)

    /** First route that actually contains the selected passenger leg, preserving source priority. */
    fun firstCovering(candidates: Iterable<TrainRoute?>, fromCode: String,
                      toCode: String): TrainRoute? =
        candidates.firstOrNull { route -> route != null && covers(route, fromCode, toCode) }
}
