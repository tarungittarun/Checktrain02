package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** Green = this source has usable train-specific data, NOT necessarily verified actuals.
 * A site claim stays explicitly labelled as a site claim; a fast HTTP 200 alone is red. */
object SourceIndicator {
    enum class Tone { READY, UNAVAILABLE, PENDING, OLD }
    data class Indicator(val tone: Tone, val label: String)

    fun of(name: String, state: String, detail: String): Indicator = when {
        state == "live" -> Indicator(Tone.READY, "reported times")
        state == "candidate" && name == PublicPageChecks.ORG ->
            Indicator(Tone.READY, "site stop reports · unverified")
        state == "candidate" && name == WimtCandidate.SOURCE ->
            Indicator(Tone.READY, "site distance · unverified")
        state == "last-known" -> Indicator(Tone.OLD, "saved · not new")
        detail.contains("not answered yet") || detail.contains("checking on first load") ||
            detail.contains("manual check available") -> Indicator(Tone.PENDING, "checking…")
        else -> Indicator(Tone.UNAVAILABLE, "no usable live report")
    }
}
