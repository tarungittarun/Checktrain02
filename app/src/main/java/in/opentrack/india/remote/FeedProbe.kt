package `in`.opentrack.india.remote

import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Diary
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
        /**
         * Ask through the first A record rather than letting the stack pick an address. One variant of the
         * matrix exists because a phone on Wi-Fi with a half-alive IPv6 path sees every request die as
         * "closed" while a browser loads the same page - the browser's only advantage is that it retries the
         * other family. If a pinned line answers and the unpinned one does not, the fix belongs in [Http] for
         * every host, and no railway-side explanation survives.
         */
        val pin4: Boolean = false,
        /**
         * Ask through the system browser engine ([in.opentrack.india.core.BrowserFetch]) instead of the app's
         * own HTTP stack. One field rather than a second code path, so the probe and the adopted shape go
         * through the same dispatcher: on a device where the far end only answers a browser, that variant
         * becomes the transport without another install - which is the whole point of measuring this on the
         * phone instead of guessing in a sandbox.
         */
        val browser: Boolean = false,
        /** A page to visit first, so the engine holds the site's own cookies when the API is asked. */
        val warm: String = "",
    )

    data class Result(val name: String, val outcome: String, val head: String, val millis: Long)

    private const val HEAD = 150
    // 4s per try, and a total budget, because every request now passes through Http's single socket
    // monitor: a diagnostic that can hold the user's own refresh behind it for half a minute is a
    // diagnostic that costs the app the thing it is trying to measure.
    private const val PER_TRY_TIMEOUT = 4_000
    // A cold web view is not a socket write: the engine answered in ~1.1 s of its own on a device, and a
    // 5 s cap turned a page load plus a request into a timeout. Generous, because it runs after a failure.
    private const val BROWSER_TIMEOUT = 8_000
    private const val MAX_TRIES = 9   // every variant, controls included - a probe that ran out of tries
                                    // before its own control host is a probe that proves nothing
    private const val TOTAL_BUDGET = 18_000

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

    /**
     * True once the browser engine has been *accepted* by a host that hung up on the app's own client - before
     * any payload proved usable. Transport and protocol are different failures, and this is what separates
     * them: a device that got a 159-byte answer through the engine is a device where asking again through
     * HttpURLConnection is known-futile, so the providers switch transport while the payload is still being
     * argued about. Adoption of a *usable* shape stays gated exactly as before; this flag changes who asks,
     * never what is believed.
     */
    @Volatile
    var browserWorks: Boolean = false
        private set

    /**
     * The page the accepted request rode on, if the winning variant named one. Empty means "the host root",
     * which is what [BrowserFetch.originPage] derives - and the reason to remember this at all is that a site
     * which answers from /ntes/ and not from / is a site whose cookies matter, so the transport cannot be
     * adopted without the origin that produced it.
     */
    @Volatile
    var browserPage: String = ""
        private set

    /**
     * Set when the traveller asks for data: a refresh tap outranks a background experiment, because the
     * experiment exists only to explain why that refresh failed. A phone's log showed one variant taking
     * 162 s while the user waited - the probe holding a socket the UI needed, on a network that was already
     * failing every attempt at 5 s. Nothing is lost by yielding: it runs again on the next refusal.
     */
    @Volatile
    private var cancelRequested = false

    fun requestCancel() {
        cancelRequested = true
    }

    /** True once a probe has finished in this process, so a report can say whether to wait for it. */
    val ready: Boolean get() = last.isNotEmpty()

    /** Run one variant. Shared with [NtesProvider] so an adopted shape is requested exactly as it was found. */
    fun execute(http: Http, v: Variant, trainNo: String, journeyEpochDay: Long): Http.Result {
        val rebuilt = rebuild(v, trainNo, journeyEpochDay)
        return try {
            if (rebuilt.browser) {
                // One path for everything browser-side, and it carries the headers - which the renderer it
                // replaced could not: `postUrl` fixes Content-Type and drops everything else, so every header
                // question this app asked "through a browser" was actually asked with no headers at all.
                val page = if (rebuilt.warm.isNotBlank()) rebuilt.warm else BrowserFetch.originPage(rebuilt.url)
                BrowserFetch.apiCall(rebuilt.url, rebuilt.body, page, rebuilt.headers, BROWSER_TIMEOUT)
            } else if (rebuilt.body == null) {
                http.get(rebuilt.url, PER_TRY_TIMEOUT, 0, rebuilt.headers, rebuilt.pin4,
                    budgetMillis = PER_TRY_TIMEOUT + 1_500)
            } else {
                http.post(
                    rebuilt.url, rebuilt.body, rebuilt.headers, PER_TRY_TIMEOUT, 0, rebuilt.tls12, rebuilt.pin4,
                    PER_TRY_TIMEOUT + 1_500,
                )
            }
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
        if (v.body != null && v.body.startsWith("jsonIn=")) {
            // Key and value, not a stored body: the value is this question's wrapped envelope, re-encoded.
            return v.copy(body = "jsonIn=" + java.net.URLEncoder.encode(NtesWire.wrap(plain), "UTF-8"))
        }
        return when {
            v.body == null && v.url.contains("liveStatusJsonNew") ->
                v.copy(url = "https://enquiry.indianrail.gov.in/ntes/others/liveStatusJsonNew?trainNo=$trainNo" +
                    "&journeyDate=" + isoDate(journeyEpochDay))
            v.body == null && v.url.contains("railradar") ->
                v.copy(url = "https://api.railradar.in/v1/trains/$trainNo/live")
            v.body == null && v.url.contains("getRunTrain") ->
                v.copy(url = "https://erail.in/rail/getRunTrain.aspx?trainno=$trainNo" +
                    "&startDate=" + isoDate(journeyEpochDay))
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
            // ORDER IS A BUDGET DECISION (18 s): the first variants below are the only questions never
            // answered anywhere - erail's live endpoint via erail's own browser engine (cookies and all).
            // Everything about NTES has been measured dead nine times over on every transport; those
            // variants run after, and the budget-skips are reported lines, not silences.
            Variant(
                "webview-erail-runtrain",
                "https://erail.in/rail/getRunTrain.aspx?trainno=$trainNo&startDate=$startIso",
                null, emptyMap(), false,
                "erail's live-status endpoint asked from erail's OWN engine, erail.in warmed first: CI measured " +
                    "403 to every header identity (run a339ab6) and the owner's phone 'closed' on the Dalvik " +
                    "path - but erail's own live page must fetch this data somehow, and whatever it uses, this " +
                    "engine holds its cookies. A 200 here is the per-stop actuals door on the phone",
                false, true, "https://erail.in/",
            ),
            Variant(
                "get-erail-runtrain-xhr",
                "https://erail.in/rail/getRunTrain.aspx?trainno=$trainNo&startDate=$startIso",
                null,
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Referer" to "https://erail.in/",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                false,
                "the same live endpoint, the app's own stack, browser identity headers: the discriminator " +
                    "between a header-based refusal (fixable in Http for everyone) and an IP-class refusal (not ours to fix)",
            ),
            Variant(
                "post-dalvik-json", NtesWire.BASE_URL, envelope, dalvik, false,
                "the shape this build ships - the control that is not the thing under test",
            ),
            Variant(
                "webview-post", NtesWire.BASE_URL, envelope,
                mapOf("Content-Type" to "application/json", "charset" to "utf-8"), false,
                "the shipped request sent from the railway's own origin with its content type declared: the " +
                    "same bytes the app posts, minus the client signature that gets hung up on",
                false, true,
            ),
            Variant(
                "webview-get-ntes",
                "https://enquiry.indianrail.gov.in/ntes/others/liveStatusJsonNew?trainNo=$trainNo" +
                    "&journeyDate=$startIso",
                null,
                mapOf("Accept" to "application/json, text/plain, */*"),
                false,
                "the GET the NTES site itself makes. No Referer is set here on purpose: fetch ignores that " +
                    "header, and riding a page of theirs supplies a real one - which is the only kind of " +
                    "Referer an endpoint would honour anyway",
                false, true,
            ),
            Variant(
                "webview-post-form", NtesWire.BASE_URL,
                "jsonIn=" + java.net.URLEncoder.encode(NtesWire.wrap(wirePlain), "UTF-8"),
                mapOf("Content-Type" to "application/x-www-form-urlencoded"), false,
                "the envelope as a form field, declared as one: the endpoint's own web client is a jQuery " +
                    "application and the answer of 159 bytes we mistook for its refusal was this platform's " +
                    "XML parser complaining about JSON, so the payload question is still open",
                false, true,
            ),
            Variant(
                "webview-post-session", NtesWire.BASE_URL, envelope,
                mapOf("Content-Type" to "application/json", "charset" to "utf-8"), false,
                "the NTES page loaded first so its own session cookie is in the jar, then the API asked from " +
                    "inside it: the difference between an anonymous POST and the request the page makes",
                false, true, "https://enquiry.indianrail.gov.in/ntes/",
            ),
            Variant(
                "webview-erail",
                "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0",
                null, emptyMap(), false,
                "the alternate source through the same engine - if it answers here, run data from erail becomes " +
                    "reachable on exactly the devices where the app's own client was refused",
                false, true,
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
                "get-railradar-no-key",
                "https://api.railradar.in/v1/trains/$trainNo/live",
                null,
                mapOf("Accept" to "application/json"),
                false,
                "a keyed service, asked without a key: any HTTP status at all - including 401 - proves HTTPS " +
                    "leaves this app fine and that the problem is the feed, not the device",
            ),
            Variant(
                "get-egress-trace", "https://one.one.one.one/cdn-cgi/trace", null, emptyMap(), false,
                "reachability control that also says which country this traffic appears to leave from",
            ),
            Variant(
                "get-erail-control", "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0",
                null, emptyMap(), false,
                "the host this app's timetable source already reaches, so a green result here means the feed " +
                    "rejected us rather than the network failing",
            ),
            Variant(
                "post-ipv4-pinned", NtesWire.BASE_URL,
                envelope,
                mapOf(
                    "Content-Type" to "application/json",
                    "charset" to "utf-8",
                    "User-Agent" to NtesWire.USER_AGENT,
                ),
                false,
                "the shipped request, pinned to the IPv4 answer - the discriminator between a broken v6 path " +
                    "on this network and a server that is refusing us",
                true,
            ),
            Variant(
                "get-erail-ipv4", "https://erail.in/rail/getTrains.aspx?TrainNo=$trainNo&DataSource=1&Language=0",
                null, emptyMap(), false,
                "the same pin on a host we know answers, which is what proves the pinned route itself works " +
                    "rather than merely failing differently",
                true,
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
        cancelRequested = false
        val tries = variants(trainNo, isoDate(journeyEpochDay),
            NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay)).take(MAX_TRIES)
        Thread({
            // try/finally because `running` is the single-flight latch: if the loop ever threw, every later
            // probe would be skipped for the life of the process and the report would quietly stop saying why.
            val out = ArrayList<Result>(tries.size)
            try {
            val started = System.currentTimeMillis()
            for (v in tries) {
                if (cancelRequested) {
                    out += Result(v.name, "skipped (a refresh was asked for - the diagnostic yields)", "", 0L)
                    break
                }
                if (System.currentTimeMillis() - started > TOTAL_BUDGET) {
                    out += Result(v.name,
                        "skipped (probe budget of " + TOTAL_BUDGET / 1000 + "s reached; the rest were not tried)",
                        "", 0L)
                    break
                }
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
                        // The egress control is answered with a trace that includes the device's public IP.
                        // Only the country and the proxy class are reportable: the clipboard is text the user
                        // pastes into a bug report, and an app with "no analytics" on its own screen must not be
                        // the thing that puts their IP address into one.
                        if (v.name == "get-egress-trace") egressSummary(got.body) else head(got.body),
                        ms,
                    )
                    is Http.Result.Failed -> Result(v.name, "${got.kind} ${got.message}".trim(), "", ms)
                }
                // Logged after the append: a line written before it reports the *previous* variant's verdict
                // against this variant's clock, which is how one field report came to read "nothing in 1005ms".
                if (got is Http.Result.Ok && v.browser) {
                    browserWorks = true
                    browserPage = v.warm
                }
                Diary.get()?.log("PROBE") {
                    val r = out.lastOrNull()
                    if (r == null) "nothing in " + ms + "ms"
                    else r.name + ": " + r.outcome +
                        // The head used to be dropped here. That is how the first answer this app ever got
                        // from the railway - 159 bytes, on the line above "not running data" - reached the
                        // copied report and never the log file, which is the artifact people actually send.
                        (if (r.head.isBlank()) "" else " | " + r.head) + " in " + ms + "ms"
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

    /**
     * The two fields of a Cloudflare trace that say something useful without saying who the device is.
     * Anything containing the ip line is dropped by construction: this function only ever returns the keys it
     * names, so a future edit that prints more of the body cannot quietly start shipping an address.
     */
    internal fun egressSummary(body: String): String {
        val keep = listOf("loc", "proxy", "http")
        val parts = body.lines().map { it.trim() }.filter { it.contains('=') }.mapNotNull { line ->
            val k = line.substringBefore('=').trim().lowercase()
            if (k in keep) k + "=" + line.substringAfter("=").trim() else null
        }
        return if (parts.isEmpty()) "answered, fields unreadable" else parts.joinToString(" ")
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
