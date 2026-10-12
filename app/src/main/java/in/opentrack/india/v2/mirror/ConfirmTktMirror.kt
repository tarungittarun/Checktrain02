package `in`.opentrack.india.v2.mirror

/**
 * The SECOND witness for actual arr/dep: confirmtkt.com's running-status page carries per-halt actual
 * minutes too ("Delay by N min" rows), captured for 20651 on 2026-10-12 while the train ran. It has no
 * station CODES - only names ("Tumakuru" where our timetable says "Tumkur") - so the parser aligns by
 * NAME, walking the route in order and never matching backwards, with a three-letter-prefix fallback
 * for spelling drift. Display and cross-check only: the position anchor comes from the primary mirror,
 * because this page blends actuals (passed halts) and estimates (ahead) in the same column.
 */
object ConfirmTktMirror {

    const val SOURCE = "confirmtkt"
    private const val BASE = "https://www.confirmtkt.com/train-running-status/"

    data class Fetched(val lines: Map<String, String>, val halts: Int, val ok: Boolean)

    /** The wire the phone uses: same (body, why) pair discipline as the primary mirror. */
    fun fetchOrWhy(
        trainNo: String,
        stops: List<Pair<String, String>>,
        get: (String) -> Pair<String?, String> = ::plainGet,
    ): Fetched {
        val (body, why) = get(BASE + trainNo)
        if (body == null) return Fetched(emptyMap(), 0, false).also { }
        val lines = parse(RunningStatusMirror.stripTags(body), stops)
        return Fetched(lines, lines.size, lines.isNotEmpty())
    }

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val DELAY = Regex("""Delay by (\d{1,3}) min""")

    private fun norm(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Walk the route in order; each halt's block runs from its name to the next halt's name. Two times
     * = actual arr + dep; a single time = the origin's departure or the terminus's arrival (position in
     * the route decides). Rows with no time render nothing.
     */
    fun parse(text: String, stops: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var from = 0
        for ((idx, pair) in stops.withIndex()) {
            val (code, name) = pair
            val key = norm(name)
            var at = findName(text, key, from)
            if (at < 0) {
                // prefix fallback: "tumakuru" vs "tumkur" drift
                at = findName(text, key.take(3), from)
            }
            if (at < 0) continue
            val nextAt = if (idx + 1 < stops.size) {
                val nKey = norm(stops[idx + 1].second)
                val direct = findName(text, nKey, at + key.length)
                (if (direct >= 0) direct else findName(text, nKey.take(3), at + key.length))
                    .takeIf { it >= 0 } ?: (at + 700)
            } else {
                (at + 700).coerceAtMost(text.length)
            }
            val block = text.substring(at, nextAt.coerceAtMost(text.length))
            val times = TIME.findAll(block).toList()
                .map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
                .filter { it in 0..1439 }
            val delay = DELAY.find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (times.isNotEmpty()) {
                val arr = when {
                    times.size >= 2 -> times[0]
                    idx == 0 -> null // the origin's single minute is a departure
                    else -> times[0]
                }
                val dep = when {
                    times.size >= 2 -> times[1]
                    idx == 0 -> times[0]
                    else -> null
                }
                val parts = mutableListOf<String>()
                arr?.let { parts += "arr ${fmt(it)}" }
                dep?.let { parts += "dep ${fmt(it)}" }
                if (parts.isNotEmpty()) {
                    val tail = if (delay != null && delay > 2) "$SOURCE ($delay min late)"
                    else "$SOURCE (on time)"
                    out[code] = "$code " + parts.joinToString(" · ") + " — via $tail"
                }
            }
            from = at + key.length.coerceAtLeast(3)
        }
        return out
    }

    private fun findName(text: String, key: String, fromIdx: Int): Int {
        if (key.isEmpty()) return -1
        var i = text.indexOf(key, fromIdx, ignoreCase = true)
        return i
    }

    private fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${m / 60}:" + "%02d".format(m % 60)
    }

    /** Same wire discipline as the primary mirror: one GET, 8 s, (body, why). */
    fun plainGet(url: String): Pair<String?, String> = RunningStatusMirror.plainGet(url)
}
