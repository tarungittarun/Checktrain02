package `in`.opentrack.india.e2e

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Live-source acceptance uses a source's event clock, never a transport receipt, page publication
 * time, timetable, or a clock borrowed from another provider. Rail clock strings are interpreted
 * against the selected run date and their explicit (+Nd) marker. Ambiguous/missing clocks fail closed.
 */
internal object SourceEventTimeQualification {
    private const val MAX_AGE_MS = 120L * 60L * 1000L
    private const val MAX_FUTURE_SKEW_MS = 2L * 60L * 1000L
    private val railClock = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?:\s*\(\+(\d+)d\))?""")

    private fun runDateEpoch(date: String): Long? {
        val normalized = date.trim().removePrefix("run of ").removePrefix("Run of ").trim()
        val formats = listOf("dd-MM-yyyy", "d MMM yyyy", "dd MMM yyyy", "d MMMM yyyy")
        for (pattern in formats) {
            val parser = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            val position = ParsePosition(0)
            val parsed = parser.parse(normalized, position)
            if (parsed != null && position.index == normalized.length) return parsed.time
        }
        return null
    }

    fun eventAtMillis(runDate: String, clock: String): Long? {
        val match = railClock.find(clock) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (minute !in 0..59 || hour !in 0..47) return null
        val runStart = runDateEpoch(runDate) ?: return null
        val dayOffset = match.groupValues[3].toIntOrNull() ?: (hour / 24)
        val minuteOfDay = hour % 24 * 60 + minute
        return runStart + dayOffset * 86_400_000L + minuteOfDay * 60_000L
    }

    fun isFreshEvent(eventAtMillis: Long?, checkedAtMillis: Long): Boolean {
        if (eventAtMillis == null) return false
        val age = checkedAtMillis - eventAtMillis
        return age in -MAX_FUTURE_SKEW_MS..MAX_AGE_MS
    }

    /** The `.in` check consumes only its own matched response's actual row clocks. */
    fun wimtLiveEventClock(
        selectedSource: String,
        responseDetails: String,
        runDate: String,
        checkedAtMillis: Long,
    ): String? {
        if (!selectedSource.contains("Selected WIMT .in live-status feed", true) ||
            !responseDetails.contains("Run date echoed and matched: true", true) ||
            !responseDetails.contains("both route endpoints echoed and matched: true", true) ||
            !responseDetails.contains("Selected WIMT .in source-reported rows (", true)) return null
        val rowSection = responseDetails.substringAfter("Selected WIMT .in source-reported rows (", "")
        val rowLines = rowSection.substringAfter("):", "").substringBefore("These are source-reported clocks")
        return railClock.findAll(rowLines).map { it.value }.firstOrNull { clock ->
            isFreshEvent(eventAtMillis(runDate, clock), checkedAtMillis)
        }
    }

    fun wimtLiveFeedFresh(
        selectedSource: String,
        responseDetails: String,
        runDate: String,
        checkedAtMillis: Long,
    ): Boolean = wimtLiveEventClock(selectedSource, responseDetails, runDate, checkedAtMillis) != null

    /** Website page clocks are claims; only an explicit date-matched page claim can qualify. */
    fun websiteClaimFresh(
        selectedSource: String,
        provenance: String,
        runDate: String,
        checkedAtMillis: Long,
    ): Boolean {
        if (!selectedSource.contains("Selected Where Is My Train · website", true) ||
            !provenance.contains("Website ", true) ||
            !provenance.contains("claim ", true) ||
            !provenance.contains("visible page date matches the selected origin run", true) ||
            provenance.contains("did not match an explicit selected run date", true)) return false
        val claimClock = Regex("""Website\s+[^,]+?\s+claim\s+([^;,.]+)""", RegexOption.IGNORE_CASE)
            .find(provenance)?.groupValues?.get(1)?.trim() ?: return false
        val eventAt = eventAtMillis(runDate, claimClock) ?: return false
        return isFreshEvent(eventAt, checkedAtMillis)
    }

    /** NTES must supply its own displayed event, in the selected run, and a current route. */
    fun ntesEventFresh(
        selectedSource: String,
        eventDescription: String,
        runDate: String,
        routePresent: Boolean,
        checkedAtMillis: Long,
    ): Boolean {
        if (!selectedSource.contains("Selected NTES · official", true) ||
            !eventDescription.contains("Last NTES event at ", true) || !routePresent) return false
        val clock = Regex("""Last NTES event at .+?,\s*(\d{1,2}:\d{2}(?:\s*\(\+\d+d\))?)""", RegexOption.IGNORE_CASE)
            .find(eventDescription)?.groupValues?.get(1) ?: return false
        return isFreshEvent(eventAtMillis(runDate, clock), checkedAtMillis)
    }
}
