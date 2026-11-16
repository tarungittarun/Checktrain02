package `in`.opentrack.india.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** WebView returns a JavaScript string as a JSON string literal, not as a JSON object. */
internal fun decodeJavascriptStringResult(encoded: String?): String {
    val token = encoded?.trim() ?: return ""
    if (token.length < 2 || token.first() != '\"' || token.last() != '\"') return ""
    // JSONTokener also accepts bare JavaScript-like literals (for example, `not-json`) as strings.
    // Require one complete quoted token before asking it to decode WebView's escaping.
    var escaped = false
    for (index in 1 until token.lastIndex) {
        when {
            escaped -> escaped = false
            token[index] == '\\' -> escaped = true
            token[index] == '\"' -> return ""
        }
    }
    if (escaped) return ""
    return runCatching {
        JSONTokener(token).nextValue() as? String ?: ""
    }.getOrDefault("")
}

private const val MAX_WEBSITE_HTML = 1_000_000

/** A single honest WebView navigation gets a bounded chance to load and hydrate its own DOM. */
internal object WebsiteBrowserRequestTiming {
    const val DOCUMENT_TIMEOUT_MS = 40_000L
    // A safety cap, not the expected latency: the DOM sentinel returns as soon as the selected
    // train identity and a station card's own clock are rendered. Do not turn a sentinel miss
    // into a long wait for a page whose content can still be shown diagnostically.
    const val DOM_READINESS_WAIT_MS = 6_000L
    const val DOM_READINESS_POLL_MS = 250L
}

/** Concurrent callers for one URL share a single in-flight network/navigation result. */
internal class InFlightRequestRegistry<K, V> {
    data class Lease<V>(val result: CompletableFuture<V>, val owner: Boolean)

    private val lock = Any()
    private val inFlight = mutableMapOf<K, CompletableFuture<V>>()

    fun acquire(key: K): Lease<V> = synchronized(lock) {
        inFlight[key]?.let { return@synchronized Lease(it, owner = false) }
        val result = CompletableFuture<V>()
        inFlight[key] = result
        Lease(result, owner = true)
    }

    fun release(key: K, lease: Lease<V>) {
        if (!lease.owner) return
        synchronized(lock) {
            if (inFlight[key] === lease.result) inFlight.remove(key)
        }
    }
}

internal object WebsiteCardMarkup {
    private val stationCardRoot = Regex(
        """(?is)<(?:div|section|article|li|tr)\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bflex-grow\b)(?=[^>]*\bclass\s*=\s*["'][^"']*\brounded-lg\b)[^>]*>""")
    private val containerOpenTag = Regex("(?is)<(div|section|article|li|tr)\\b[^>]*>")
    private val containerTag = Regex("(?is)</?(div|section|article|li|tr)\\b[^>]*>")
    private val containerTagName = Regex("(?is)^</?\\s*(div|section|article|li|tr)\\b")
    private val stationCardMarker = Regex("(?is)^<!--\\s*Station Details Card\\s*-->$")
    private val stationHeading = Regex("(?is)<h[2-6]\\b[^>]*>(.*?)</h[2-6]>")
    private val cardClock = Regex("(?is)\\b(?:ARRIVAL|DEPARTURE)\\b.{0,900}?\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)\\b")
    private val htmlSpaceEntity = Regex("&(?:nbsp;|#160;|#x0*a0;)", RegexOption.IGNORE_CASE)
    private val scriptOrStyle = Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)\\s*>")

    /** HTML after scripts, styles and comments are masked; normalized entities preserve offsets. */
    internal data class VisiblePageMarkup(
        val html: String,
        val stationCardMarkerOffsets: List<Int>,
    )

    /** Only actual document markup can satisfy readiness; card-looking script/comment text is invisible. */
    internal fun visiblePageMarkup(html: String): VisiblePageMarkup {
        val source = scriptOrStyle.replace(html) { match -> " ".repeat(match.value.length) }
        val visible = StringBuilder(source.length)
        val markers = mutableListOf<Int>()
        var cursor = 0
        while (cursor < source.length) {
            val commentStart = source.indexOf("<!--", cursor)
            if (commentStart < 0) {
                visible.append(source, cursor, source.length)
                break
            }
            visible.append(source, cursor, commentStart)
            val closeStart = source.indexOf("-->", commentStart + 4)
            val end = if (closeStart < 0) source.length else closeStart + 3
            val comment = source.substring(commentStart, end)
            if (closeStart >= 0 && stationCardMarker.matches(comment)) markers += end
            visible.append(" ".repeat(comment.length))
            cursor = end
        }
        val normalized = htmlSpaceEntity.replace(visible.toString()) {
            " ".repeat(it.value.length)
        }
        return VisiblePageMarkup(normalized, markers)
    }

    /** One root per heading, even when a marker, a styled root and a semantic fallback overlap. */
    internal fun stationCardSectionStarts(document: VisiblePageMarkup): List<Int> {
        val html = document.html
        val classRoots = stationCardRoot.findAll(html).map { it.range.first }.toList()
        val markerRoots = document.stationCardMarkerOffsets.mapNotNull { marker ->
            val nextMarker = document.stationCardMarkerOffsets.firstOrNull { it > marker }
                ?: html.length
            classRoots.firstOrNull { it >= marker && it < nextMarker }
                ?: containerOpenTag.find(html, marker)
                    ?.takeIf { it.range.first < nextMarker }?.range?.first
        }
        fun resolve(starts: List<Int>): List<Int> {
            val candidates = starts.distinct().mapNotNull { start ->
                val section = containerSectionAt(html, start) ?: return@mapNotNull null
                val headings = stationHeading.findAll(section).take(2).toList()
                if (headings.size != 1 || !cardClock.containsMatchIn(section)) return@mapNotNull null
                val priority = when {
                    start in markerRoots -> 0
                    start in classRoots -> 1
                    else -> 2
                }
                RootCandidate(start, start + headings[0].range.first, section.length, priority)
            }
            return candidates.groupBy { it.headingStart }.values.mapNotNull { roots ->
                roots.minWithOrNull(compareBy<RootCandidate> { it.priority }
                    .thenByDescending { it.length })?.start
            }.sorted()
        }
        val primaryRoots = (markerRoots + classRoots).distinct()
        val primary = resolve(primaryRoots)
        // The live page supplies one styled root per station card. Once those roots
        // cover the structural candidates, avoid the much costlier broad semantic
        // ancestor search across every heading in the full DOM.
        if (classRoots.size >= 3 && primary.size == primaryRoots.size) return primary
        return resolve(primaryRoots + semanticStationCardSectionStarts(html))
    }

    private data class RootCandidate(val start: Int, val headingStart: Int,
                                    val length: Int, val priority: Int)

    /** A station heading plus its own labelled clock is a structural fallback when classes/comments change. */
    private fun semanticStationCardSectionStarts(html: String): List<Int> {
        val headings = stationHeading.findAll(html).take(100).toList()
        if (headings.isEmpty()) return emptyList()
        val openings = containerOpenTag.findAll(html).take(10_000)
            .map { it.range.first }.toList()
        return headings.mapNotNull { heading ->
            var low = 0
            var high = openings.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (openings[middle] < heading.range.first) low = middle + 1 else high = middle
            }
            val lastBeforeHeading = low - 1
            if (lastBeforeHeading < 0) return@mapNotNull null
            val earliest = (lastBeforeHeading - 96).coerceAtLeast(0)
            for (index in lastBeforeHeading downTo earliest) {
                val start = openings[index]
                val section = containerSectionAt(html, start) ?: continue
                if (heading.range.last >= start + section.length) continue
                if (stationHeading.findAll(section).take(2).count() != 1) continue
                if (!cardClock.containsMatchIn(section)) continue
                return@mapNotNull start
            }
            null
        }
    }

    /** Extract a balanced div subtree for compatibility with focused parser tests. */
    internal fun divSectionAt(html: String, rootStart: Int, limit: Int = html.length): String? {
        val open = containerOpenTag.find(html, rootStart)?.takeIf { it.range.first == rootStart }
            ?: return null
        if (!containerTagName.find(open.value)?.groupValues?.get(1).orEmpty()
                .equals("div", ignoreCase = true)) return null
        return containerSectionAt(html, rootStart, limit)
    }

    /** Extract a balanced div/section/article/list-row subtree without crossing its sibling. */
    internal fun containerSectionAt(html: String, rootStart: Int,
                                    limit: Int = html.length): String? {
        if (rootStart !in html.indices || limit <= rootStart) return null
        val open = containerOpenTag.find(html, rootStart)?.takeIf { it.range.first == rootStart }
            ?: return null
        val rootName = containerTagName.find(open.value)?.groupValues?.get(1)
            ?.lowercase(java.util.Locale.ROOT) ?: return null
        val stack = mutableListOf<String>()
        for (tag in containerTag.findAll(html, rootStart)) {
            if (tag.range.first >= limit) break
            val name = containerTagName.find(tag.value)?.groupValues?.get(1)
                ?.lowercase(java.util.Locale.ROOT) ?: continue
            val closing = tag.value.trimStart().startsWith("</")
            if (closing) {
                val matching = stack.indexOfLast { it == name }
                if (matching >= 0) {
                    while (stack.size > matching) stack.removeAt(stack.lastIndex)
                }
            } else if (!tag.value.trimEnd().endsWith("/>")) {
                if (stack.isEmpty() && name != rootName) return null
                stack += name
            }
            if (stack.isEmpty()) return html.substring(rootStart, tag.range.last + 1)
        }
        return null
    }

    internal fun isPopulatedStationCard(card: String): Boolean {
        val heading = stationHeading.find(card)?.groupValues?.get(1)
            ?.replace(Regex("(?s)<[^>]*>"), " ")
            ?.replace("&nbsp;", " ", ignoreCase = true)
            ?.replace("&#160;", " ", ignoreCase = true)
            ?.replace(Regex("\\s+"), " ")?.trim()
        return !heading.isNullOrBlank() && cardClock.containsMatchIn(card)
    }
}

/**
 * Cheap DOM-readiness check: the requested train, update/source/destination header, and at least
 * one visible station card with its own labelled clock are present. Header labels only say the
 * page is hydrated; selected-run date, route endpoints, event time, and source qualification are
 * still validated independently by PublicPageChecks and OrgWebsiteStatus after capture.
 */
internal fun hasPopulatedTrainDocument(html: String, trainNo: String): Boolean {
    if (html.isBlank() || html.length > MAX_WEBSITE_HTML) return false
    val document = WebsiteCardMarkup.visiblePageMarkup(html)
    val visibleHtml = document.html
    val visible = visibleHtml.replace(Regex("(?s)<[^>]*>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
    val identity = Regex("(?i)\\b${Regex.escape(trainNo)}\\b").containsMatchIn(visible)
    val update = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)")
        .containsMatchIn(visible)
    fun hasLabeledValue(label: String, nextLabel: String): Boolean {
        val start = Regex("(?i)\\b${Regex.escape(label)}\\s*:\\s*").find(visible)
            ?: return false
        val rest = visible.substring(start.range.last + 1)
        val end = Regex("(?i)\\b(?:${nextLabel}|Last\\s+Updated)\\s*:")
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end).trim().take(100).isNotEmpty()
    }
    val source = hasLabeledValue("Source", "Destination")
    val destination = hasLabeledValue("Destination", "Journey\\s+Timeline")
    val starts = WebsiteCardMarkup.stationCardSectionStarts(document)
    val populatedCard = starts.indices.any { index ->
        val limit = starts.getOrNull(index + 1) ?: visibleHtml.length
        WebsiteCardMarkup.containerSectionAt(visibleHtml, starts[index], limit)
            ?.let { WebsiteCardMarkup.isPopulatedStationCard(it) } == true
    }
    return identity && update && source && destination && populatedCard
}

/** The selected independent website's document navigation through Android's actual WebView engine.
 * This is NOT a forged Chrome header set, Google's WIMT phone app, or a way to solve a challenge.
 * WebView has its own browser version and network/session identity; a 403 remains a refusal.
 * The only source of accepted data is this very document's DOM after a successful main-frame load.
 */
object WebsiteBrowserRequest {
    private val main = Handler(Looper.getMainLooper())
    /** WebView supports one active navigation; same-URL callers join instead of queueing duplicates. */
    private val gate = Any()
    private val inFlight = InFlightRequestRegistry<String, NavigationOutcome>()
    private data class NavigationOutcome(val answer: Http.Result, val timing: String?,
                                         val navigated: Boolean)
    private val allowed = Regex("https://whereismytrain\\.org\\.in/\\?trainNo=[0-9]{5}")
    /** Instrumentation-only barrier, run off the UI thread before any WebView network navigation. */
    @Volatile internal var beforeDocumentNavigationForTest: (() -> Unit)? = null
    /** Bounded timing fields only; no page body, URL query, cookie, or refusal identifier. */
    @Volatile internal var timingObserverForTest: ((String) -> Unit)? = null

    fun document(context: Context, url: String): Http.Result {
        if (!allowed.matches(url)) return Http.Result.Failed(Http.Result.Kind.HTTP, "invalid website URL")
        if (Looper.myLooper() == Looper.getMainLooper())
            return Http.Result.Failed(Http.Result.Kind.HTTP, "browser document must run off the UI thread")
        val requestCallAt = SystemClock.elapsedRealtime()
        val lease = inFlight.acquire(url)
        val outcome = if (lease.owner) {
            try {
                val generated = try {
                    synchronized(gate) {
                        navigateDocument(context.applicationContext, url, requestCallAt)
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    NavigationOutcome(Http.Result.Failed(Http.Result.Kind.TIMEOUT,
                        "browser document interrupted"), null, false)
                } catch (error: Exception) {
                    NavigationOutcome(Http.Result.Failed(Http.Result.Kind.HTTP,
                        "browser document failed (${error.javaClass.simpleName})"), null, false)
                } catch (error: Throwable) {
                    // Unblock same-URL callers even if a fatal error escapes the WebView owner.
                    lease.result.complete(NavigationOutcome(
                        Http.Result.Failed(Http.Result.Kind.HTTP, "browser document failed"), null, false))
                    throw error
                }
                lease.result.complete(generated)
                generated
            } finally {
                inFlight.release(url, lease)
            }
        } else {
            try {
                lease.result.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return Http.Result.Failed(Http.Result.Kind.TIMEOUT, "browser document wait interrupted")
            } catch (error: Exception) {
                return Http.Result.Failed(Http.Result.Kind.HTTP, "browser document result unavailable")
            }
        }
        if (outcome.navigated) {
            val timing = outcome.timing.orEmpty()
            // One actual main-frame navigation is logged, even when several callers share it.
            // Never log body, URL query, cookies, challenge tokens or raw CDN request IDs.
            if (lease.owner) Diary.get()?.log("HTTP") {
                "GET whereismytrain.org.in try#1 $timing -> " + when (val answer = outcome.answer) {
                    is Http.Result.Ok -> "answered ${answer.body.length}B (WebView document)"
                    is Http.Result.Failed -> "${answer.kind} ${answer.message} (WebView document)"
                }
            }
            val observedTiming = timing + if (lease.owner) "" else " shared=true"
            runCatching { timingObserverForTest?.invoke(observedTiming) }
        }
        return outcome.answer
    }

    /** Called under [gate]; identical requests are already coalesced by [inFlight]. */
    private fun navigateDocument(context: Context, url: String,
                                 requestCallAt: Long): NavigationOutcome {
        val gateAcquiredAt = SystemClock.elapsedRealtime()
        val gateWait = (gateAcquiredAt - requestCallAt).coerceAtLeast(0L)
        beforeDocumentNavigationForTest?.invoke()
        val requestStartedAt = SystemClock.elapsedRealtime()
        val pending = Pending(context, url)
        if (!main.post { pending.start() }) return NavigationOutcome(
            Http.Result.Failed(Http.Result.Kind.HTTP, "browser looper unavailable"), null, false)
        var interrupted = false
        val completed = try {
            pending.done.await(WebsiteBrowserRequestTiming.DOCUMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            interrupted = true
            pending.abort()
            Thread.currentThread().interrupt()
            false
        }
        if (!completed && !interrupted) pending.abort()
        val answer = when {
            completed -> pending.result
            interrupted -> Http.Result.Failed(Http.Result.Kind.TIMEOUT, "browser document interrupted")
            else -> Http.Result.Failed(Http.Result.Kind.TIMEOUT, "browser document timed out")
        }
        val total = SystemClock.elapsedRealtime() - requestStartedAt
        val timing = if (pending.navigated) pending.timingSummary(total, gateWait) else null
        return NavigationOutcome(answer, timing, pending.navigated)
    }

    private class Pending(val context: Context, val url: String) {
        val done = CountDownLatch(1)
        private val finished = AtomicBoolean(false)
        private val captureStarted = AtomicBoolean(false)
        @Volatile var result: Http.Result = Http.Result.Failed(Http.Result.Kind.EMPTY, "no document")
            private set
        private var view: WebView? = null // main looper only
        @Volatile private var readinessStartedElapsed = 0L
        private val expectedTrain = url.substringAfter("trainNo=")
        @Volatile private var navigationStartedElapsed = 0L
        @Volatile private var pageCommitVisibleElapsed = 0L
        @Volatile private var pageFinishedElapsed = 0L
        @Volatile private var completedElapsed = 0L
        @Volatile private var readinessPollCount = 0
        @Volatile var navigated: Boolean = false
            private set

        fun timingSummary(totalElapsed: Long, gateWaitElapsed: Long): String {
            val navigation = navigationStartedElapsed
            val committed = pageCommitVisibleElapsed
            val pageFinished = pageFinishedElapsed
            val completed = completedElapsed.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            val pageLoad = if (navigation > 0L && pageFinished >= navigation)
                pageFinished - navigation else null
            val readinessStart = readinessStartedElapsed
            val domWait = if (readinessStart > 0L && completed >= readinessStart)
                completed - readinessStart else null
            return "gateWait=${gateWaitElapsed}ms total=${totalElapsed}ms " +
                (pageLoad?.let { "page=${it}ms " } ?: "") +
                (domWait?.let {
                    if (committed > 0L) "DOM-ready-from-commit=${it}ms "
                    else "DOM-ready-from-page-finish=${it}ms "
                } ?: "") +
                "DOM-polls=$readinessPollCount"
        }

        fun abort() {
            if (finished.compareAndSet(false, true)) main.post { close() }
        }

        private fun close() {
            view?.let {
                view = null
                runCatching { it.stopLoading() }
                runCatching { it.destroy() }
            }
        }

        private fun finish(value: Http.Result) {
            if (!finished.compareAndSet(false, true)) return
            result = value
            completedElapsed = SystemClock.elapsedRealtime()
            done.countDown()
            // Do not destroy the engine from inside one of its own navigation callbacks.
            main.post { close() }
        }

        /**
         * Cheap readiness sentinel: inspect card-sized ancestor text first; serialize the large
         * document once the requested train and a station heading with its own labelled clock
         * are visible. Source/date/route qualification runs independently after capture.
         */
        private fun populatedDocumentScript(): String = """
            (function(){
              var root=document.documentElement;
              if(!root)return '';
              function normalize(value){return (value||'').replace(/\s+/g,' ').trim().toUpperCase();}
              var pageBody=document.body;
              var text=normalize((pageBody&&pageBody.innerText)||(pageBody&&pageBody.textContent)||
                root.innerText||root.textContent||'');
              var identity=/\b${expectedTrain}\b/.test(text);
              var update=/\bLAST\s+UPDATED\s*:\s*\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)\b/.test(text);
              var sourceStart=/\bSOURCE\s*:\s*/.exec(text);
              var destinationStart=/\bDESTINATION\s*:\s*/.exec(text);
              function hasValue(start,nextLabel){
                if(!start)return false;
                var tail=text.substring(start.index+start[0].length);
                var end=nextLabel.exec(tail);
                return tail.substring(0,end?end.index:tail.length).trim().length>0;
              }
              var header=update&&hasValue(sourceStart,/\bDESTINATION\s*:/)&&
                hasValue(destinationStart,/\b(?:LAST\s+UPDATED|JOURNEY\s+TIMELINE)\s*:/);
              function hasClock(value){
                return /\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)\b/.test(value);
              }
              var headings=root.querySelectorAll('h2,h3,h4,h5,h6');
              var populated=false;
              for(var i=0;i<headings.length&&!populated;i++){
                if(!normalize(headings[i].innerText||headings[i].textContent||''))continue;
                var candidate=headings[i].parentElement;
                for(var depth=0;candidate&&candidate!==root&&depth<96;depth++,candidate=candidate.parentElement){
                  var tag=candidate.tagName;
                  if(tag!=='DIV'&&tag!=='SECTION'&&tag!=='ARTICLE'&&tag!=='LI'&&tag!=='TR')continue;
                  var nested=candidate.querySelectorAll('h2,h3,h4,h5,h6');
                  if(nested.length>1)break;
                  if(nested.length!==1)continue;
                  var body=normalize(candidate.innerText||candidate.textContent||'');
                  if((body.indexOf('ARRIVAL')>=0||body.indexOf('DEPARTURE')>=0)&&hasClock(body)){
                    populated=true;break;
                  }
                }
              }
              if(!identity||!header||!populated)return '';
              var html=root.outerHTML||'';
              return html.length<=${MAX_WEBSITE_HTML}?html:'';
            })()
        """.trimIndent()

        private fun captureRenderedDocument() {
            if (finished.get()) return
            val browser = view ?: return
            readinessPollCount += 1
            try {
                browser.evaluateJavascript(populatedDocumentScript()) { encoded ->
                    if (finished.get()) return@evaluateJavascript
                    val html = decodeJavascriptStringResult(encoded)
                    val elapsed = SystemClock.elapsedRealtime() - readinessStartedElapsed
                    when {
                        html.isNotBlank() && hasPopulatedTrainDocument(html, expectedTrain) ->
                            finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                        elapsed >= WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS && html.isNotBlank() ->
                            // Preserve a real 200 document for parser diagnostics if the quick
                            // sentinel and Kotlin's stricter structural parser disagree.
                            finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                        elapsed >= WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS ->
                            captureDiagnosticDocument(browser)
                        else -> main.postDelayed({ captureRenderedDocument() },
                            WebsiteBrowserRequestTiming.DOM_READINESS_POLL_MS)
                    }
                }
            } catch (error: Exception) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "browser DOM read failed (${error.javaClass.simpleName})"))
            }
        }

        /** Capture once at the bounded deadline so an incomplete HTTP-200 body stays diagnosable. */
        private fun captureDiagnosticDocument(browser: WebView) {
            if (finished.get()) return
            try {
                browser.evaluateJavascript("(function(){var h=document.documentElement&&document.documentElement.outerHTML||'';" +
                    "return h.length<=${MAX_WEBSITE_HTML}?h:'';})()") { encoded ->
                    if (finished.get()) return@evaluateJavascript
                    val html = decodeJavascriptStringResult(encoded)
                    if (html.isNotBlank()) finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                    else finish(Http.Result.Failed(Http.Result.Kind.EMPTY,
                        "browser document empty or oversized"))
                }
            } catch (error: Exception) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "browser DOM read failed (${error.javaClass.simpleName})"))
            }
        }

        private fun startReadinessPolling(pageUrl: String, fromCommit: Boolean) {
            if (finished.get()) return
            if (pageUrl != url) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "website document URL changed; no train data"))
                return
            }
            if (captureStarted.compareAndSet(false, true)) {
                val started = SystemClock.elapsedRealtime()
                readinessStartedElapsed = started
                if (fromCommit) pageCommitVisibleElapsed = started
                captureRenderedDocument()
            }
        }

        fun start() {
            if (finished.get()) return
            try {
                val browser = WebView(context)
                view = browser
                browser.settings.apply {
                    // Standard WebView page capabilities, NOT proof that Hostinger will admit us.
                    // No forged UA, copied Chrome cookies, JS bridge or challenge solver.
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    // The document and its CDN are first-party on this host; do not
                    // allow unrelated third-party tracking cookies without a need.
                    setAcceptThirdPartyCookies(browser, false)
                }
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame) {
                            finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                                "website redirected or navigated; no train document"))
                            return true
                        }
                        return false
                    }

                    override fun onReceivedHttpError(v: WebView, request: WebResourceRequest,
                                                     response: WebResourceResponse) {
                        if (!request.isForMainFrame) return // an image's 403 is not the train page's 403
                        if (response.statusCode == 403) {
                            // Capture the exact header before finishing the failed navigation.
                            // This is private, opt-in device evidence; it is never in the UI,
                            // result message, public CI comment or uploaded device artifact.
                            val id = WebsiteRefusalMetadata.header(response.responseHeaders)
                            Diary.get()?.logWebsiteRefusal(request.url.toString(), id,
                                System.currentTimeMillis())
                        }
                        finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                            "HTTP ${response.statusCode}"))
                    }

                    override fun onReceivedError(v: WebView, request: WebResourceRequest,
                                                 error: WebResourceError) {
                        if (request.isForMainFrame) finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                            "browser network error ${error.errorCode}"))
                    }

                    override fun onPageCommitVisible(v: WebView, pageUrl: String) {
                        // Start inspecting the same visible document at first commit rather
                        // than waiting for unrelated late page resources to finish loading.
                        startReadinessPolling(pageUrl, fromCommit = true)
                    }

                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        if (finished.get()) return
                        pageFinishedElapsed = SystemClock.elapsedRealtime()
                        // Some WebView versions do not send onPageCommitVisible; this is also
                        // the fallback. The atomic gate prevents duplicate DOM polling.
                        startReadinessPolling(pageUrl, fromCommit = false)
                    }
                }
                navigated = true
                navigationStartedElapsed = SystemClock.elapsedRealtime()
                browser.loadUrl(url)
            } catch (error: Exception) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "browser unavailable: ${error.javaClass.simpleName}"))
            }
        }
    }
}
