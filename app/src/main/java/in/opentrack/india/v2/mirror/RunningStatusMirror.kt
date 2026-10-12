package `in`.opentrack.india.v2.mirror

/**
 * The mirror that answers while the officials refuse: runningstatus.in server-renders, per halt, the
 * SCHEDULED and the ACTUAL arrival/departure of today's run - the exact per-stop actuals this product
 * exists for, in plain HTML a keyless GET can read (captured 2026-10-12 for 20651: every passed halt
 * carried its actual minute). It is a second witness, never the official: every line it produces on
 * screen names it, and a mirror that answers nothing changes nothing.
 *
 * Parsing runs on tag-stripped TEXT, not on the DOM - the same discipline as the diary's page probes.
 * The shape the parser trusts, learned from the capture: each halt's text block starts at its
 * "(CODE)" marker; blocks carry four 12-hour times when the halt has actual arr+dep (sch arr, sch dep,
 * actual arr, actual dep), two when it is the source (dep only) or the terminus (arr only), and the
 * words "Update Awaited" mean the actual has not reached the mirror yet - those halts render nothing.
 * The station-near row between halts carries no "(CODE)" marker and a 24-hour clock, so it can never
 * contaminate a neighbour block; that property is asserted by the test.
 */
object RunningStatusMirror {

    const val SOURCE = "runningstatus.in"
    private const val BASE = "https://runningstatus.in/status/"

    private val CODE = Regex("""\(([A-Z]{2,5})\)""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})\s+(AM|PM)\s+\d{1,2}-[A-Za-z]{3}""")

    data class Stop(
        val stationCode: String,
        val schArr: Int?,
        val schDep: Int?,
        val actArr: Int?,
        val actDep: Int?,
    )

    data class Actuals(val source: String, val stops: List<Stop>)

    /** The actuals, or the honest reason there are none - a silent null helps nobody debug. */
    data class Fetched(val actuals: Actuals?, val why: String)

    /**
     * The wire the phone uses: one plain GET, capped, no key, no session. The pair is (body, why) -
     * paste #19 showed DNS+TCP+TLS all healthy and the GET still "no answer", which is exactly the
     * gap a bare null leaves: the next paste must name the status or the exception, not the silence.
     */
    fun fetchOrWhy(trainNo: String, get: (String) -> Pair<String?, String> = ::plainGet): Fetched {
        val (body, why) = get(BASE + trainNo)
        if (body == null) return Fetched(null, why.ifBlank { "no answer" })
        val actuals = parse(stripTags(body)) ?: return Fetched(null, "answered but no halt rows parsed")
        return Fetched(actuals, "ok")
    }

    /** HTML to text: tags become one space, the handful of entities we meet become their characters. */
    fun stripTags(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    fun parse(text: String): Actuals? {
        val marks = CODE.findAll(text).toList()
        if (marks.isEmpty()) return null
        val stops = mutableListOf<Stop>()
        for (i in marks.indices) {
            val code = marks[i].groupValues[1]
            val from = marks[i].range.last + 1
            val to = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            val block = text.substring(from, to)
            val stop = row(code, block) ?: continue
            stops += stop
        }
        if (stops.isEmpty()) return null
        return Actuals(SOURCE, stops)
    }

    /** One halt block -> a Stop with actuals, or null when the mirror itself has none ("Update Awaited"). */
    internal fun row(code: String, block: String): Stop? {
        val times = TIME.findAll(block)
            .map { h12(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        val awaited = block.contains("Update Awaited")
        val hasArr = block.contains("Arr:")
        val hasDep = block.contains("Dep:")
        return when {
            times.size >= 4 -> Stop(code, times[0], times[1], times[2], times[3])
            times.size == 2 && !awaited && hasDep && !hasArr -> Stop(code, null, times[0], null, times[1])
            times.size == 2 && !awaited && hasArr && !hasDep -> Stop(code, times[0], null, times[1], null)
            else -> null
        }
    }

    /** "03:10 PM" -> 910. 12 AM is hour 0, 12 PM is hour 12 - the one trap of the 12-hour clock. */
    internal fun h12(hour: String, minute: String, ampm: String): Int {
        var h = hour.toInt() % 12
        if (ampm == "PM") h += 12
        return h * 60 + minute.toInt()
    }

    private fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${m / 60}:" + "%02d".format(m % 60)
    }

    /**
     * The sentence the screen renders, verbatim, one per halt that HAS an actual: both minutes the
     * mirror carries, then the source by name, then the delay the arithmetic gives. The delay is
     * computed against the mirror's own schedule and wrapped to the same day (a train that crosses
     * midnight shows a 20-minute slip as 20, never 1420).
     */
    fun sentences(actuals: Actuals, routeCodes: Collection<String>): Map<String, String> {
        val want = routeCodes.map { it.uppercase() }.toSet()
        val out = LinkedHashMap<String, String>()
        for (s in actuals.stops) {
            if (s.stationCode !in want) continue
            val parts = mutableListOf<String>()
            s.actArr?.let { parts += "arr ${fmt(it)}" }
            s.actDep?.let { parts += "dep ${fmt(it)}" }
            if (parts.isEmpty()) continue
            val delayWord = when {
                s.actDep != null && s.schDep != null -> delayWord(s.actDep - s.schDep)
                s.actArr != null && s.schArr != null -> delayWord(s.actArr - s.schArr)
                else -> null
            }
            val tail = if (delayWord == null) SOURCE else "$SOURCE ($delayWord)"
            out[s.stationCode] = "${s.stationCode} " + parts.joinToString(" · ") + " — via $tail"
        }
        return out
    }

    private fun delayWord(raw: Int): String {
        val d = ((raw + 720) % 1440) - 720
        return when {
            d <= 2 -> "on time"
            else -> "$d min late"
        }
    }

    /** The production wire: one GET, 8 s patience, a naming UA, nothing remembered. (body, why). */
    fun plainGet(url: String): Pair<String?, String> = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "OpenTrackIndia/1.0 (live running status)")
        }
        val code = conn.responseCode
        if (code in 200..299) {
            Pair(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.take(400_000), "ok")
        } else {
            Pair(null, "HTTP $code")
        }
    } catch (e: Exception) {
        Pair(null, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
}
