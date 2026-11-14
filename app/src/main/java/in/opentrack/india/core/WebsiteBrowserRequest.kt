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
    // This site fills its station cards client-side; budget for the measured slow
    // WebView path without ever treating an unhydrated shell as station data.
    const val DOM_READINESS_WAIT_MS = 18_000L
    const val DOM_READINESS_POLL_MS = 250L
}

internal object WebsiteCardMarkup {
    private val stationCardRoot = Regex(
        """(?is)<(?:div|section|article|li|tr)\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bflex-grow\b)(?=[^>]*\bclass\s*=\s*["'][^"']*\brounded-lg\b)[^>]*>""")
    private val containerOpenTag = Regex("(?is)<(div|section|article|li|tr)\\b[^>]*>")
    private val containerTag = Regex("(?is)</?(div|section|article|li|tr)\\b[^>]*>")
    private val containerTagName = Regex("(?is)^</?\\s*(div|section|article|li|tr)\\b")
    private val stationCardMarker = Regex("(?is)^<!--\\s*Station Details Card\\s*-->$")
    private val stationHeading = Regex("(?is)<h[2-6]\\b[^>]*>(.*?)</h[2-6]>")
    private val cardClock = Regex("(?is)\\b(?:ARRIVAL|DEPARTURE)\\b.{0,900}?\\b\\d{1,2}:\\d{2}\\s*(?:AM|PM)\\b")
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
        val semanticRoots = semanticStationCardSectionStarts(html)
        val candidates = (markerRoots + classRoots + semanticRoots).distinct().mapNotNull { start ->
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

/** Pure DOM-readiness check: do not confuse a page shell or a timetable echo with populated cards. */
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
    private val gate = Any()
    private val allowed = Regex("https://whereismytrain\\.org\\.in/\\?trainNo=[0-9]{5}")
    fun document(context: Context, url: String): Http.Result {
        if (!allowed.matches(url)) return Http.Result.Failed(Http.Result.Kind.HTTP, "invalid website URL")
        if (Looper.myLooper() == Looper.getMainLooper())
            return Http.Result.Failed(Http.Result.Kind.HTTP, "browser document must run off the UI thread")
        synchronized(gate) {
            val pending = Pending(context.applicationContext, url)
            if (!main.post { pending.start() })
                return Http.Result.Failed(Http.Result.Kind.HTTP, "browser looper unavailable")
            val completed = pending.done.await(
                WebsiteBrowserRequestTiming.DOCUMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) pending.abort()
            val answer = if (completed) pending.result
                else Http.Result.Failed(Http.Result.Kind.TIMEOUT, "browser document timed out")
            // Only the main-frame navigation is counted: normal browser resource GETs may also occur.
            // Never log body, URL query, cookies, challenge tokens or raw CDN request IDs.
            if (pending.navigated) Diary.get()?.log("HTTP") {
                "GET whereismytrain.org.in try#1 -> " + when (answer) {
                    is Http.Result.Ok -> "answered ${answer.body.length}B (WebView document)"
                    is Http.Result.Failed -> "${answer.kind} ${answer.message} (WebView document)"
                }
            }
            return answer
        }
    }

    private class Pending(val context: Context, val url: String) {
        val done = CountDownLatch(1)
        private val finished = AtomicBoolean(false)
        private val captureStarted = AtomicBoolean(false)
        @Volatile var result: Http.Result = Http.Result.Failed(Http.Result.Kind.EMPTY, "no document")
            private set
        private var view: WebView? = null // main looper only
        private var readinessStartedAt = 0L // main looper only
        private val expectedTrain = url.substringAfter("trainNo=")
        @Volatile var navigated: Boolean = false
            private set

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
            done.countDown()
            // Do not destroy the engine from inside one of its own navigation callbacks.
            main.post { close() }
        }

        private fun captureRenderedDocument() {
            if (finished.get()) return
            val browser = view ?: return
            browser.evaluateJavascript("(function(){var h=document.documentElement&&document.documentElement.outerHTML||'';" +
                "return h.length<=${MAX_WEBSITE_HTML}?h:'';})()") { encoded ->
                if (finished.get()) return@evaluateJavascript
                val html = decodeJavascriptStringResult(encoded)
                val elapsed = SystemClock.elapsedRealtime() - readinessStartedAt
                val populated = hasPopulatedTrainDocument(html, expectedTrain)
                when {
                    populated -> finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                    elapsed >= WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS && html.isNotBlank() ->
                        // Preserve a real 200 document for explicit parser diagnostics; the
                        // source layer will reject it unless station cards/clocks really parse.
                        finish(Http.Result.Ok(html, fromCache = false, ageMillis = 0))
                    elapsed >= WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS ->
                        finish(Http.Result.Failed(Http.Result.Kind.EMPTY,
                            "browser document empty or oversized"))
                    else -> main.postDelayed({ captureRenderedDocument() },
                        WebsiteBrowserRequestTiming.DOM_READINESS_POLL_MS)
                }
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

                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        if (finished.get()) return
                        if (pageUrl != url) {
                            finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                                "website document URL changed; no train data"))
                            return
                        }
                        // The document may finish before client rendering/hydration has
                        // populated its visible timetable. Poll this same DOM briefly, without
                        // issuing another request; incomplete 200 markup remains diagnosable.
                        if (captureStarted.compareAndSet(false, true)) {
                            readinessStartedAt = SystemClock.elapsedRealtime()
                            captureRenderedDocument()
                        }
                    }
                }
                navigated = true
                browser.loadUrl(url)
            } catch (error: Exception) {
                finish(Http.Result.Failed(Http.Result.Kind.HTTP,
                    "browser unavailable: ${error.javaClass.simpleName}"))
            }
        }
    }
}
