package `in`.opentrack.india.ui

/** Starts nonblocking independent-source jobs before awaiting the potentially slow official request. */
internal object TrackingSourceFanout {
    suspend fun <T> startBeforeOfficial(
        startIndependentSources: () -> Unit,
        fetchOfficial: suspend () -> T,
    ): T {
        startIndependentSources()
        return fetchOfficial()
    }
}
