package in.opentrack.india.remote

import in.opentrack.india.core.RailTime

/**
 * Parser for the public Indian Railways enquiry wire format (erail-style `getTrains.aspx`).
 *
 * Chosen because it is the only *stable machine interface* in this problem space that needs no key:
 * the payload is a flat, `~`-delimited record stream that these enquiry services have emitted for
 * over a decade, as opposed to their HTML (which changes weekly) or IRCTC's session-bound JSON
 * (which needs a login, a CSRF header and a server-side anti-bot dance — i.e. exactly the
 * fragile-proxy dependency this project is not allowed to have).
 *
 * Framing:
 *   `~~~~~~~~`  separates logical sections (header, schedule, live table)
 *   `~^`        separates records inside a section
 *   `~`         separates fields inside a record
 *   empty fields are dropped by the servers, so **field indices are not stable**.
 *
 * That last fact is the whole design: we classify each field by its *shape* (station code, HH:MM,
 * integer delay, platform, status word) instead of trusting a column number, and allow a positional
 * [columnHint] override so a future format change can be fixed from Settings without shipping an
 * APK. A parser that throws on an unexpected column would blank the live screen; this one degrades
 * to "we understood these 3 stops" and says so.
 */
object ErailWire {

    private val CODE_RE = Regex("^[A-Z][A-Z0-9]{1,5}$")
    private val TIME_RE = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
    private val SIGNED_DELAY_RE = Regex("^[+-]\\d{1,3}$")
    private val SECTION = Regex("~~~~~~~~")

    data class WireStop(
        val seq: Int,
        val code: String?,
        val name: String?,
        val schedArr: Int?,
        val schedDep: Int?,
        val actArr: Int?,
        val actDep: Int?,
        val delayArr: Int?,
        val delayDep: Int?,
        val platform: String?,
        val statusWord: String?,
        /** how many fields we could not classify — surfaced, so a format change is visible */
        val unknownFields: Int,
    )

    data class Header(
        val trainNo: String?,
        val trainName: String?,
        val srcCode: String?,
        val dstCode: String?,
        val trainType: String?,
        val totalKm: Int?,
        val note: String?,
        /** the "current position" sentence the service puts in the header, if present */
        val positionLine: String?,
    )

    data class Parsed(val header: Header?, val stops: List<WireStop>, val unclassifiedRows: Int) {
        val isEmpty: Boolean get() = header == null && stops.isEmpty()
    }

    fun splitRecords(text: String): List<List<String>> =
        text.split(SECTION)
            .flatMap { it.split("~^") }
            .map { row -> row.split("~").map(String::trim).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }

    fun parse(text: String, columnHint: String? = null): Parsed {
        val rows = splitRecords(text)
        var header: Header? = null
        val stops = ArrayList<WireStop>(rows.size)
        var unclassified = 0
        for ((index, fields) in rows.withIndex()) {
            val asStop = stopFrom(fields, index, columnHint)
            if (asStop != null && asStop.code != null) stops += asStop
            else if (header == null) header = headerFrom(fields, text)
            else if (fields.size >= 3) unclassified++
        }
        return Parsed(header, stops, unclassified)
    }

    /**
     * A record is a stop when it contains a station-code-shaped field and at least one time.
     * Deliberately not "row length == N": the servers drop empty columns (no arrival at the source,
     * no platform at most stations), so a length check fails on exactly the interesting rows.
     */
    fun stopFrom(fields: List<String>, seq: Int, columnHint: String?): WireStop? {
        if (fields.size < 3) return null
        if (columnHint != null) return stopByHint(fields, seq, columnHint)

        var code: String? = null
        var name: String? = null
        val times = ArrayList<Int>(6)
        val signed = ArrayList<Int>(2)
        var platform: String? = null
        var status: String? = null
        var unknown = 0
        var lastTimePos = -1
        for ((pos, f) in fields.withIndex()) {
            when {
                // A sign is the only unambiguous marker of a delay in this format: a bare small integer
                // is just as likely to be a distance, a day number or a platform, and reading one of
                // those as "+1 min late" would be a fabricated fact rather than a parsed one.
                SIGNED_DELAY_RE.matches(f) -> {
                    val v = f.toInt()
                    if (v in -900..900 && signed.size < 2) signed += v
                }
                code == null && pos < 4 && CODE_RE.matches(f) -> code = f
                TIME_RE.matches(f) -> {
                    RailTime.parseHm(f)?.let { times += it }
                    lastTimePos = pos
                }
                status == null && STATUS_WORDS.any { w -> f.contains(w, true) } -> status = f.lowercase()
                // A bare "2" is a platform *only* after the times: these tables lead with a row index,
                // and claiming that as the platform would put a number on screen that the train never
                // had. A prefixed value ("PF 3", "Platform 1") is unambiguous anywhere in the row.
                platform == null && PLATFORM_RE.matches(f) &&
                    (f.any { it.isLetter() } || (lastTimePos >= 0 && pos > lastTimePos)) ->
                    platform = f.filter { it.isDigit() }
                name == null && f.length > 2 && f.any { it.isLetter() } -> name = f
                // Unclassified fields are counted instead of being forced into a slot, so a format
                // change surfaces in `unclassifiedRows` rather than as a wrong number on screen.
                else -> unknown++
            }
        }
        if (code == null && times.isEmpty()) return null
        val sched = times.getOrNull(0) to times.getOrNull(1)
        val actual = times.getOrNull(2) to times.getOrNull(3)
        // Delay: a signed column when the feed sent one, otherwise derived from actual minus scheduled.
        var delayArr = signed.getOrNull(0)?.takeIf { it != 0 }
        var delayDep = signed.getOrNull(1)?.takeIf { it != 0 }
        if (delayArr == null && actual.first != null && sched.first != null) {
            delayArr = (actual.first - sched.first).takeIf { it != 0 }
        }
        if (delayDep == null && actual.second != null && sched.second != null) {
            delayDep = (actual.second - sched.second).takeIf { it != 0 }
        }
        return WireStop(
            seq = seq, code = code, name = name,
            schedArr = sched.first, schedDep = sched.second,
            actArr = actual.first, actDep = actual.second,
            delayArr = delayArr, delayDep = delayDep,
            platform = platform, statusWord = status, unknownFields = unknown,
        )
    }

    /** Settings-provided mapping, e.g. `0:seq,1:code,2:name,3:sa,4:sd,5:aa,6:ad,7:da,8:dd,9:pf`. */
    private fun stopByHint(fields: List<String>, seq: Int, hint: String?): WireStop {
        val map = HashMap<String, Int>()
        hint?.split(',')?.forEach { part ->
            val kv = part.split(':')
            if (kv.size == 2) map[kv[0].trim().lowercase()] = kv[1].trim().toIntOrNull() ?: -1
        }
        fun at(key: String): String? = map[key]?.let { fields.getOrNull(it)?.trim() }
        fun min(key: String): Int? = at(key)?.let { RailTime.parseHm(it) }
        fun int(key: String): Int? = at(key)?.let { s -> Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() }
        return WireStop(
            seq = int("seq") ?: seq,
            code = at("code")?.uppercase(),
            name = at("name"),
            schedArr = min("sa"), schedDep = min("sd"), actArr = min("aa"), actDep = min("ad"),
            delayArr = int("da"), delayDep = int("dd"), platform = at("pf"),
            statusWord = at("st")?.lowercase(),
            unknownFields = 0,
        )
    }

    private fun headerFrom(fields: List<String>, whole: String): Header? {
        if (fields.isEmpty()) return null
        val no = fields.firstOrNull { TRAIN_NO_RE.matches(it) }
        val name = fields.firstOrNull { it.length > 6 && it.any { c -> c.isLetter() } &&
            !CODE_RE.matches(it) && !TIME_RE.matches(it) }
        val codes = fields.filter { CODE_RE.matches(it) }
        val position = whole.lineSequence()
            .firstOrNull { it.contains("current", true) || it.contains("last", true) && it.contains("station", true) }
            ?.replace("~", " ")?.trim()?.takeIf { it.length in 12..300 }
        if (no == null && name == null) return null
        return Header(
            trainNo = no, trainName = name?.uppercase(),
            srcCode = codes.getOrNull(0), dstCode = codes.lastOrNull()?.takeIf { codes.size > 1 },
            trainType = fields.firstOrNull { TYPE_WORDS.any { w -> it.equals(w, true) } }?.uppercase(),
            totalKm = fields.mapNotNull { s -> KM_RE.find(s)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
                .firstOrNull(),
            note = null, positionLine = position,
        )
    }

    private val TRAIN_NO_RE = Regex("^\\d{5}$")
    private val PLATFORM_RE = Regex("^(platform|pf)?\\s?\\d{1,2}$", RegexOption.IGNORE_CASE)
    private val KM_RE = Regex("(\\d[\\d,]{2,5})\\s*(km|kms|kilometre)", RegexOption.IGNORE_CASE)
    private val STATUS_WORDS = listOf("departed", "arrived", "arrival", "halt", "passed", "cancelled",
        "running", "approaching", "source", "destination", "yet to start", "terminated")
    private val TYPE_WORDS = listOf("RAJDHANI", "SHATABDI", "SINGLE", "DURONTO", "VANDE", "SUPERFAST",
        "MAIL", "EXPRESS", "PASSENGER", "MEMU", "DEMU", "SUVIDHA", "SPECIAL", "TEJAS", "HUMSAFAR")
}
