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
    private const val MAX_TRIES = 11  // every variant, controls included - a probe that ran out of tries
                                    // before its own control host is a probe that proves nothing
    private const val TOTAL_BUDGET = 115_000 // three settled page reads now lead (rstrain text ~13 s,
    // rstrain dom ~10 s, and the tap read 20 s settle + tap + 6 s, 34 s cap) because the instant read
    // measured only navigation; the rest still skip with reported lines

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

    /**
     * The verdict line that makes a paste self-answering: how many time-like tokens the captured
     * page carries, with three examples in context. The owner's 14:40 capture rendered 513 B of
     * nav and halt NAMES with the table never painted - the scan is what says whether times exist
     * anywhere in what we captured, without anyone pasting kilobytes into a chat.
     */
    private val TIME_LIKE = Regex("""\d{1,2}[:.]\d{2}""")

    private fun scanForTimes(name: String, r: Http.Result) {
        val body = (r as? Http.Result.Ok)?.body ?: return
        val hits = TIME_LIKE.findAll(body).toList()
        val samples = hits.take(3).joinToString(" ; ") { m ->
            val a = (m.range.first - 24).coerceAtLeast(0)
            val b = (m.range.last + 12).coerceAtMost(body.length)
            "'" + body.substring(a, b).replace('\n', ' ') + "'"
        }
        Diary.get()?.log("PROBE") {
            "$name scan: ${hits.size} time-like token(s) in ${body.length}B" +
                (if (hits.isEmpty()) " - the capture holds no times" else "; first: $samples")
        }
    }

    /** Run one variant. Shared with [NtesProvider] so an adopted shape is requested exactly as it was found. */
    fun execute(http: Http, v: Variant, trainNo: String, journeyEpochDay: Long): Http.Result {
        val rebuilt = rebuild(v, trainNo, journeyEpochDay)
        return try {
            if (rebuilt.name == "webview-erail-rstrain") {
                // THE decisive read: their own page for THIS number, read SETTLED - 12 s after the
                // page finishes, 16 KB of text. Verdict from the owner's 14:40 paste: the wait was
                // honoured and the text STILL held only nav + halt names - so this read now also
                // reports how many time-like tokens it caught (scanForTimes), making every paste
                // self-answering.
                val r = BrowserFetch.pageText(rebuilt.url, 24_000, settleMillis = 12_000, textCap = 16_000)
                scanForTimes(rebuilt.name, r)
                r
            } else if (rebuilt.name == "webview-erail-rstrain-dom-tap") {
                // The last in-page lever: if 20 s of patience plus tapping their own tab still ends in
                // a DOM without times, the data channel is interaction- or gate-kept and no further
                // read variant of THIS page can succeed - the paste will have said so.
                val r = BrowserFetch.pageText(
                    rebuilt.url, 34_000, settleMillis = 20_000, textCap = 64_000, dom = true,
                    tapText = "Running status",
                )
                scanForTimes(rebuilt.name, r)
                r
            } else if (rebuilt.name == "webview-erail-rstrain-dom") {
                // The 14:40 paste's second verdict: the visible text never gains a table. Their own
                // scripts talk SignalR - the data can sit in the DOM invisibly - so this reads the
                // page as HTML (64 KB), scans it for time-like tokens and logs the verdict. Tokens
                // here means a parser follows; no tokens means their page fetches on interaction
                // and the click is next. Either way the next paste says which, no screenshots.
                val r = BrowserFetch.pageText(rebuilt.url, 24_000, settleMillis = 10_000, textCap = 64_000, dom = true)
                scanForTimes(rebuilt.name, r)
                r
            } else if (rebuilt.name.startsWith("webview-erail-rs")) {
                BrowserFetch.pageText(rebuilt.url, 14_000)
                // The rendered-page read: erail's live data arrives over their own SignalR inside their
                // own page (measured 2026-10-12 - no fetchable endpoint exists), so we load the page
                // they serve to every browser and read the text their own scripts render into it.
                BrowserFetch.pageText(rebuilt.url, 14_000)
            } else if (rebuilt.browser) {
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
                    if (v.url.contains("startDate=")) "&startDate=" + ddmmDate(journeyEpochDay) else "")
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

    /** erail's own pages ask for dd-MM-yyyy; the 404 on the owner's phone (ISO date) is the evidence. */
    private fun ddmmDate(epochDay: Long): String =
        SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).also {
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
        val ddmm = ddmmDate(startIso.let {
            // startIso is yyyy-MM-dd; convert through the epoch the same way isoDate built it.
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).also { tz -> tz.timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
                .parse(it)?.time ?: 0L
        })
        return listOf(
            // ORDER IS A BUDGET DECISION (18 s), v4 on the owner's 10:47 log: both folk-lore spellings of
            // the erail live endpoint measured 404 from a phone that passes the WAF - the guessing era
            // ends. The probe now OBSERVES: load erail's own site in the engine and record every URL the
            // site's own scripts call. Their live-status endpoint names itself; the next run asks THAT.
            Variant(
                "webview-erail-observe",
                "https://erail.in/",
                null, emptyMap(), false,
                "load erail's own page and record what its own scripts call (bounded list, diary only): " +
                    "the site's live-status XHR announces its real endpoint - no third spelling gets invented",
                false, true,
            ),
            Variant(
                "webview-erail-rspage",
                "https://erail.in/train-running-status",
                null, emptyMap(), false,
                "erail's own running-status PAGE (URL from their own menu, captured verbatim - no API " +
                    "guessing): loaded in the engine, THEIR SignalR populates it, and the rendered text " +
                    "is read back - what any browser user sees, no invented endpoint",
                false, true,
            ),
            Variant(
                "webview-erail-rstrain",
                "https://erail.in/train-running-status/$trainNo",
                null, emptyMap(), false,
                "the same page deep-linked with the number, read SETTLED: 12 s after the page " +
                    "finishes, 16 KB - the instant read caught only 426 B of navigation while their " +
                    "scripts filled the running table (paste 2026-10-12); a miss renders their 404 " +
                    "page and the text says so - no bytes can be mistaken for data",
                false, true,
            ),
            Variant(
                "webview-erail-rstrain-dom",
                "https://erail.in/train-running-status/$trainNo",
                null, emptyMap(), false,
                "the SAME page read as raw DOM (64 KB), not visible text: the settled text verdict " +
                    "was nav + halt NAMES, never a time (paste 2026-10-12 14:40) - if their SignalR " +
                    "scripts park the running data in the page invisibly, the DOM has it; the app " +
                    "scans its own capture for time-like tokens and the paste carries the verdict",
                false, true,
            ),
            Variant(
                "webview-erail-rstrain-dom-tap",
                "https://erail.in/train-running-status/$trainNo",
                null, emptyMap(), false,
                "the same page given PATIENCE and a TAP: 20 s settle (their SignalR hub may simply " +
                    "be slow on a phone), then their own 'Running status' tab is clicked and the DOM " +
                    "read 6 s later - paste #11 proved the DOM holds only meta text at 10 s, so this " +
                    "is the last in-page lever before their data channel is declared tap-gated",
                false, true,
            ),
            Variant(
                "get-erail-sitejs",
                "https://erail.in/js5/cmp/eRailMobileHTMLEN.js?v=22092026",
                null,
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Referer" to "https://erail.in/",
                    "Accept" to "*/*",
                ),
                false,
                "the site's OWN app script on the PLAIN stack (the owner's 11:23 log proves the plain stack " +
                    "answers erail.in - the 404s ARE responses; the WebView fetch stalled on their heavy " +
                    "homepage): its text names the SignalR hub and every data path the live page really uses",
            ),
            Variant(
                "get-api-runningstatus",
                "https://api.erail.in/runningstatus/?trainno=$trainNo&startDate=$ddmm",
                null,
                mapOf("User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Accept" to "application/json"),
                false,
                "api.erail.in runningstatus - the public API erail's own homepage advertises (Data -> API, " +
                    "captured verbatim on 5ea20a2). Per-stop ACTUALS if the keyless era survives. dd/MM/yyyy",
            ),
            Variant(
                "get-api-route",
                "https://api.erail.in/route/?trainno=$trainNo",
                null,
                mapOf("User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Accept" to "application/json"),
                false,
                "api.erail.in route - the per-stop SCHEDULE from their own public API. If only this answers " +
                    "keylessly, the details screen's stale route/times (T20.2's sequel) get their live fix",
            ),
            Variant(
                "get-erail-runtrain-xhr",
                "https://erail.in/rail/getRunTrain.aspx?trainno=$trainNo&startDate=$ddmm",
                null,
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Referer" to "https://erail.in/",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                false,
                "erail's live-status endpoint, the app's stack wearing the site's own headers, erail's own " +
                    "date format (dd-MM-yyyy): the phone measurably passes the WAF here (404 was the app's " +
                    "answer to a malformed date, not a block) - a 200 is the per-stop actuals door",
            ),
            Variant(
                "get-erail-runtrain-nodate",
                "https://erail.in/rail/getRunTrain.aspx?trainno=$trainNo",
                null,
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
                    "Referer" to "https://erail.in/",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                false,
                "the same endpoint without a date: if the endpoint serves the CURRENT run by default, the " +
                    "date parameter was the whole 404 story",
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
                        (if (r.head.isBlank()) "" else " | " + r.head) + " in " + ms + "ms" +
                        // A runtrain 200 is the capture the whole hunt is for: bounded body rides the line,
                        // because the parser (4.5) may only be written against exactly this text.
                        (if (got is Http.Result.Ok && v.url.contains("getRunTrain"))
                            " body=" + got.body.take(800) else "") +
                        // The observation run's whole product: what erail's own scripts called.
                        (if (v.name.startsWith("webview-erail-rs") && got is Http.Result.Ok)
                            " page-text=" + got.body.replace('\n', ' ').take(900) else "") +
                        (if (v.name == "webview-erail-observe") {
                            val obs = BrowserFetch.observedRequestsSnapshot()
                            if (obs.isEmpty()) " site-called=(none recorded)"
                            else " site-called=" + obs.take(14).joinToString(" ; ") { u -> u.take(200) }
                        } else "") +
                        // Their app script, filtered in the app: a window around every endpoint-ish
                        // keyword. Their code, their names - the parser meets only what was seen.
                        (if (v.name == "get-erail-sitejs" && got is Http.Result.Ok) {
                            val rx = Regex("aspx|getrun|signalr|hub|ajax|live|running", RegexOption.IGNORE_CASE)
                            " endpoint-hits=" + rx.findAll(got.body).take(10).map { m ->
                                val a = (m.range.first - 50).coerceAtLeast(0)
                                val b = (m.range.last + 70).coerceAtMost(got.body.length)
                                got.body.substring(a, b).replace('\n', ' ')
                            }.joinToString(" || ").take(1100)
                        } else "")
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
