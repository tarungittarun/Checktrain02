package `in`.opentrack.india.remote

import `in`.opentrack.india.core.Http
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A bounded request experiment, run on the device, after the live feed refuses us.
 *
 * Why on the phone and not in CI: the railway's enquiry host answers Indian residential and mobile networks
 * and closes connections from datacenter ranges — six probes from GitHub runners in a row ended in TLS
 * timeouts, so the build system structurally cannot tell a wrong request from a blocked one. The only vantage
 * point that can is the one the app already runs on. So when [NtesProvider] is refused, the app tries the
 * plausible variants of its own request and records what each one got, and the long-press report turns that
 * into a paragraph a developer can act on. Without it, every fix to this layer is a guess tested on a stranger.
 *
 * It is deliberately: single-flight (a process tries this once), time-boxed (a dead host costs seconds, not a
 * frozen screen), and non-authoritative — nothing here changes what the screen shows. Only [NtesProvider]
 * answering normally can do that.
 */
object FeedProbe {

    /** One way of asking. The names appear verbatim in the report, so they read as sentences. */
    data class Variant(
        val name: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
        val tls12: Boolean,
        val note: String,
    )

    data class Result(val name: String, val outcome: String, val head: String, val millis: Long)

    private const val HEAD = 150
    private const val PER_TRY_TIMEOUT = 7_000
    private const val MAX_TRIES = 7

    @Volatile
    private var last: List<Result> = emptyList()

    @Volatile
    private var running = false

    /**
     * The name of a variant that produced a *usable* answer on this device, if any. [NtesProvider] asks with it
     * from the next refresh onward, which is the whole point: if a phone in Bengaluru can only get an answer
     * from, say, `post-tls12`, the app should use that shape for that user rather than wait for a rebuild.
     * Adoption is gated on the payload parsing into a real snapshot — a 200 from a captive portal or an HTML
     * error page changes nothing, which is what keeps this from becoming the app's worst failure mode.
     */
    @Volatile
    var adoptedName: String? = null
        private set

    /** True once a probe has finished in this process, so a report can say whether to wait for it. */
    val ready: Boolean get() = last.isNotEmpty()

    /** Run one variant. Shared with [NtesProvider] so an adopted shape is requested exactly as it was found. */
    fun execute(http: Http, v: Variant, trainNo: String, journeyEpochDay: Long): Http.Result {
        val rebuilt = rebuild(v, trainNo, journeyEpochDay)
        return try {
            if (rebuilt.body == null) http.get(rebuilt.url, PER_TRY_TIMEOUT, 0, rebuilt.headers)
            else http.post(rebuilt.url, rebuilt.body, rebuilt.headers, PER_TRY_TIMEOUT, 0, rebuilt.tls12)
        } catch (e: Throwable) {
            Http.Result.Failed(Http.Result.Kind.HTTP, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The same shape, asked about a different train or day. URLs that embed a train number and every envelope
     * are regenerated here rather than stored, so an adopted variant can never be replayed with the probe's
     * original question attached to it.
     */
    fun rebuild(v: Variant, trainNo: String, journeyEpochDay: Long): Variant {
        val plain = NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay)
        return when {
            v.body == null && v.url.contains("liveStatusJsonNew") ->
                v.copy(url = "https://enquiry.indianrail.gov.in/ntes/others/liveStatusJsonNew?trainNo=$trainNo" +
                    "&journeyDate=" + isoDate(journeyEpochDay))
            v.body == null && v.url.contains("howtherail") ->
                v.copy(url = "https://howtherail.in/railapi/getTrainLiveTrainStatusByStn?train_no=$trainNo")
            v.body == null && v.url.contains("getTrains.aspx") ->
                v.copy(url = "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0")
            v.body == null -> v
            v.headers["Content-Type"]?.startsWith("text/plain") == true -> v.copy(body = plain)
            else -> v.copy(body = NtesWire.envelope(plain))
        }
    }

    /** The variant the probe recorded as adopted, rebuilt for this train and day. Null if it was never seen. */
    fun adoptedFor(trainNo: String, journeyEpochDay: Long): Variant? {
        val name = adoptedName ?: return null
        return variants(trainNo, isoDate(journeyEpochDay), NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay))
            .firstOrNull { it.name == name }
    }

    private fun isoDate(epochDay: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).also {
            it.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(java.util.Date(epochDay * 86_400_000L))

    /**
     * The variants worth trying. Two axes matter for a server that hangs up: **what the request looks like**
     * (a Dalvik user agent with a JSON body versus a browser agent with none, since some deployments of this
     * endpoint sit behind a policy that drops POSTs it does not recognise) and **how the TLS session is built**
     * (an explicit TLS 1.2 socket, because a legacy-renegotiation refusal surfaces as a closed connection with
     * no HTTP status at all). The last two entries are separate public services rather than restatements of the
     * first, so a probe that fails every variant also proves the network is not simply down.
     */
    fun variants(trainNo: String, startIso: String, wirePlain: String): List<Variant> {
        val envelope = NtesWire.envelope(wirePlain)
        val dalvik = mapOf(
            "Content-Type" to "application/json",
            "charset" to "utf-8",
            "User-Agent" to NtesWire.USER_AGENT,
        )
        return listOf(
            Variant(
                "post-dalvik-json", NtesWire.BASE_URL, envelope, dalvik, false,
                "the shape this build ships",
            ),
            Variant(
                "post-tls12", NtesWire.BASE_URL, envelope, dalvik, true,
                "same body, explicit TLS 1.2 socket",
            ),
            Variant(
                "post-browser-agent", NtesWire.BASE_URL, envelope,
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-IN,en;q=0.9",
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                ),
                false,
                "browser user agent, no charset header",
            ),
            Variant(
                "post-plain-body", NtesWire.BASE_URL, wirePlain,
                mapOf(
                    "Content-Type" to "text/plain; charset=utf-8",
                    "User-Agent" to NtesWire.USER_AGENT,
                ),
                false,
                "the query string sent unencrypted, in case the envelope is the problem",
            ),
            Variant(
                "get-ntes-web",
                "https://enquiry.indianrail.gov.in/ntes/others/liveStatusJsonNew?trainNo=$trainNo" +
                    "&journeyDate=$startIso",
                null,
                mapOf(
                    "User-Agent" to NtesWire.USER_AGENT,
                    "Accept" to "application/json, text/plain, */*",
                    // Not decoration. That endpoint has answered browsers for years and closed sockets on
                    // clients that did not present themselves as the NTES site, and a mobile app is exactly
                    // such a client: the two headers below are the difference between a JSON answer and the
                    // "connection closed" this app is currently reporting - with no key and no session.
                    "Referer" to "https://enquiry.indianrail.gov.in/ntes/",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                false,
                "the old web JSON endpoint, GET, presenting itself as the NTES site",
            ),
            Variant(
                "get-howtherail",
                "https://howtherail.in/railapi/getTrainLiveTrainStatusByStn?train_no=$trainNo",
                null,
                mapOf("Accept" to "application/json, text/plain, */*"),
                false,
                "an independent Indian live-status service, as a control for reachability",
            ),
            Variant(
                "get-erail-control", "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0",
                null, emptyMap(), false,
                "the host this app's timetable source already reaches, so a green result here means the feed " +
                    "rejected us rather than the network failing",
            ),
        )
    }

    /**
     * Kick off a probe if one has not already run. Safe from any thread, never throws, and never waits: the
     * refresh path must not become slower because a diagnostic exists.
     */
    fun maybeStart(http: Http, trainNo: String, journeyEpochDay: Long) {
        if (running || ready) return
        running = true
        val tries = variants(trainNo, isoDate(journeyEpochDay),
            NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay)).take(MAX_TRIES)
        Thread({
            // try/finally because `running` is the single-flight latch: if the loop ever threw, every later
            // probe would be skipped for the life of the process and the report would quietly stop saying why.
            val out = ArrayList<Result>(tries.size)
            try {
            for (v in tries) {
                val t0 = System.currentTimeMillis()
                val got = execute(http, v, trainNo, journeyEpochDay)
                val ms = System.currentTimeMillis() - t0
                var usable = false
                if (got is Http.Result.Ok) {
                    val doc = NtesWire.unwrapJson(got.body)
                    val parsed = doc?.let { runCatching { NtesWire.parse(it.toString()) }.getOrNull() }
                    usable = parsed != null && parsed.hasLiveColumns &&
                        NtesAdapter.toSnapshot(parsed, trainNo, journeyEpochDay) != null
                }
                out += when (got) {
                    is Http.Result.Ok -> Result(
                        v.name,
                        "answered ${got.body.length}B" + if (usable) ", usable running data" else ", not running data",
                        head(got.body),
                        ms,
                    )
                    is Http.Result.Failed -> Result(v.name, "${got.kind} ${got.message}".trim(), "", ms)
                }
                if (usable) {
                    adoptedName = v.name
                    break
                }
                // Stop at the first answer we can actually use, so a probe that found nothing still records
                // every shape it tried and stops trying the rest at MAX_TRIES.
            }
            last = out
            } finally {
                running = false
            }
        }, "feed-probe").apply { isDaemon = true; start() }
    }

    /** The paragraph appended to the copied report. Empty until a probe has run in this process. */
    fun render(): String {
        val results = last
        if (results.isEmpty()) {
            return if (running) "probe running: results not ready when the report was copied — long-press again"
            else ""
        }
        return buildString {
            append("probe (one request shape per line, run on this device after the feed refused us):\n")
            for (r in results) {
                append("  ${r.name}: ${r.outcome} in ${r.millis}ms")
                if (r.head.isNotBlank()) append(" :: ${r.head}")
                append("\n")
            }
            append("  note: a line that answers while the first does not is the shape to ship; ")
            append("if only the control answers, the feed itself is rejecting us.")
        }
    }

    private fun head(body: String): String =
        body.replace('\n', ' ').replace('\r', ' ').trim().take(HEAD)
}
