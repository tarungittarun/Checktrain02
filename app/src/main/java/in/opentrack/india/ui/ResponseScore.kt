package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime

/** A brief network receipt. A page-published clock is NOT a train position clock.
 * Never add invented :00 seconds to a minute-granular source update. */
object ResponseScore {
    fun durationMillis(sourceDetail: String?): Long? =
        Regex("checked in (\\d+) ms").find(sourceDetail.orEmpty())
            ?.groupValues?.get(1)?.toLongOrNull()

    fun text(elapsedMs: Long, sourceUpdateMillis: Long?, hasSeconds: Boolean = false): String =
        "Took: ${(elapsedMs.coerceAtLeast(0) + 999) / 1000} sec\nAt: " +
            (sourceUpdateMillis?.let {
                if (hasSeconds) RailTime.formatClockSeconds(it) else RailTime.formatClock(it)
            } ?: "—")
}
