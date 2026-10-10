package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainSummary

/**
 * One row of the type-ahead, in either search mode.
 *
 * `value` is what the search will actually run on — a 3-letter station code or a 5-digit train number —
 * while `primary` is what the field shows. Keeping them separate is what makes picking a suggestion
 * exact: "New Delhi" and "New Delhi Railway Station" are different codes, and re-parsing the name the
 * user just watched us choose would be a second chance to get it wrong.
 */
data class Suggestion(
    val kind: Kind,
    val value: String,
    val primary: String,
    val badge: String,
    val secondary: String,
) {
    enum class Kind { STATION, TRAIN }

    companion object {
        fun ofStation(s: StationHit): Suggestion {
            val bits = ArrayList<String>(3)
            s.state?.takeIf { it.isNotBlank() }?.let { bits += it.lowercase().replaceFirstChar(Char::uppercase) }
            bits += if (s.rank == 1) "1 train halts" else "${s.rank} trains halt"
            // Smart cast is available here: `lat`/`lon` are plain vals in the same module.
            if (s.lat != null && s.lon != null) bits += "%.2f°N %.2f°E".format(s.lat, s.lon)
            return Suggestion(
                kind = Kind.STATION, value = s.code, primary = TitleCase.of(s.name),
                badge = s.code, secondary = bits.joinToString(" · "),
            )
        }

        fun ofTrain(t: TrainSummary): Suggestion {
            val bits = ArrayList<String>(3)
            bits += "${t.src} → ${t.dst}"
            if (t.totalKm > 0) bits += "${t.totalKm} km"
            t.totalMin?.let { bits += RailTime.formatDuration(it) }
            bits += "${t.halts} halts"
            return Suggestion(
                kind = Kind.TRAIN, value = t.no, primary = TitleCase.of(t.name),
                badge = t.no, secondary = bits.joinToString(" · "),
            )
        }

    }
}


/**
 * The upstream dump writes every station and train name in caps, which is fine on a station
 * board and unreadable in a list of seven. Title-casing here (rather than in the ETL) keeps the
 * bytes we ship identical to the source, so a name can still be matched by a user who typed
 * what they saw on the platform - and one function decides how it looks.
 */
object TitleCase {
    private val SMALL = setOf("of", "and", "the", "to", "at", "via", "for", "jn", "exp", "spl")

    fun of(raw: String): String {
        val cleaned = raw.trim().replace('_', ' ').replace('-', '–').replace("  ", " ")
        if (cleaned.isEmpty()) return cleaned
        if (!cleaned.any { it.isUpperCase() }) return cleaned   // already mixed case: leave it
        return cleaned.lowercase().split(' ').mapIndexed { i, w ->
            val cased = when {
                w.length <= 2 -> w.uppercase()                       // "NR", "JN", "E"
                i > 0 && SMALL.contains(w) -> w
                else -> w.replaceFirstChar(Char::uppercase)
            }
            capAfterDash(cased)
        }.joinToString(" ")
    }

    /**
     * Indian train names are frequently two places tied with a dash ("Mumbai Central–New Delhi
     * Rajdhani"), and a lowercase half reads as a typo in a headline.
     */
    private fun capAfterDash(w: String): String = buildString(w.length) {
        var up = false
        for (c in w) {
            if (c == '–' || c == '-') { append(c); up = true; continue }
            append(if (up) c.uppercaseChar() else c)
            up = false
        }
    }
    }
