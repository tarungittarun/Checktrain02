package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import java.util.Locale

/** Green means usable train-specific information, not automatically independently verified Act. */
object SourceIndicator {
    enum class Tone { READY, UNAVAILABLE, PENDING, OLD }
    data class Indicator(val tone: Tone, val label: String)

    fun of(name: String, state: String, detail: String): Indicator = when {
        state == "live" -> Indicator(Tone.READY, "reported times")
        state == "candidate" && name == PublicPageChecks.ORG ->
            Indicator(Tone.READY, "site stops · unverified")
        state == "candidate" && name == WimtCandidate.SOURCE ->
            Indicator(Tone.READY, "site distance · not GPS")
        state == "last-known" -> Indicator(Tone.OLD, "saved · not new")
        detail.contains("not answered yet") || detail.contains("checking on first load") ||
            detail.contains("manual check available") -> Indicator(Tone.PENDING, "checking…")
        detail.contains("HTTP 403") -> Indicator(Tone.UNAVAILABLE, "site blocks request")
        detail.contains("Request failed", true) || detail.contains("timed out", true) ->
            Indicator(Tone.UNAVAILABLE, "connection failed")
        detail.contains("run day mismatched") -> Indicator(Tone.UNAVAILABLE, "wrong run day")
        else -> Indicator(Tone.UNAVAILABLE, "no usable stop report")
    }

    /** A short elapsed seconds figure, not the age of the website's last stop or page clock. */
    fun seconds(detail: String): String? = Regex("checked in (\\d+) ms").find(detail)
        ?.groupValues?.get(1)?.toLongOrNull()
        ?.let { String.format(Locale.ROOT, "%.2f s", it / 1000.0) }

    fun priority(tone: Tone): Int = when (tone) {
        Tone.READY -> 0
        Tone.OLD -> 1
        Tone.PENDING -> 2
        Tone.UNAVAILABLE -> 3
    }
}
