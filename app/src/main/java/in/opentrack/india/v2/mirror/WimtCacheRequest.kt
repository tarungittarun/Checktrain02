package `in`.opentrack.india.v2.mirror

/** One user-selected read of the undocumented WIMT web cache. The persisted gate is per host,
 * shared across trains/screens, and checked before transport; a 403/429 is never retried. */
object WimtCacheRequest {
    const val PAUSE_PREFS = "wimt_web_cache_request_pause"

    fun fetch(
        number: String,
        epochDay: Long,
        from: String,
        to: String,
        knownCodes: Set<String>,
        pacing: WebsiteRequestPacing,
        nowMillis: () -> Long = System::currentTimeMillis,
        knownKms: Map<String, Int> = emptyMap(),
        knownTimes: Map<String, Pair<Int?, Int?>> = emptyMap(),
        get: (String) -> WimtCandidate.WireReply = { WimtCandidate.getOnce(it) },
    ): WimtCandidate.Result {
        val requestedAt = nowMillis()
        pacing.paused(requestedAt)?.let { paused ->
            return WimtCandidate.Result(emptyMap(), paused.detail)
        }
        val result = WimtCandidate.fetch(
            number = number,
            epochDay = epochDay,
            from = from,
            to = to,
            knownCodes = knownCodes,
            nowMillis = requestedAt,
            knownKms = knownKms,
            knownTimes = knownTimes,
            get = get,
        )
        pacing.observeHttpStatus(result.responseStatus, result.responseReceived, nowMillis())
        return result
    }
}
