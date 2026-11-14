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
    const val DOM_READINESS_WAIT_MS = 8_000L
    const val DOM_READINESS_POLL_MS = 250L
}

private val stationCardRoot = Regex(
    """(?is)<div\b(?=[^>]*\bclass\s*=\s*["'][^"']*\bflex-grow\b)(?=[^>]*\bclass\s*=\s*["'][^"']*\brounded-lg\b)[^>]*>""")

/** Pure DOM-readiness check: do not confuse a page shell or a timetable echo with populated cards. */
internal fun hasPopulatedTrainDocument(html: String, trainNo: String): Boolean {
    if (html.isBlank() || html.length > MAX_WEBSITE_HTML) return false
    val visible = html.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        .replace(Regex("(?s)<[^>]*>"), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&#160;", " ", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
    val identity = Regex("(?i)\\b${Regex.escape(trainNo)}\\b").containsMatchIn(visible)
    val update = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)")
        .containsMatchIn(visible)
    val source = Regex("(?i)\\bSource:\\s*[A-Z0-9]{1,6}\\b").containsMatchIn(visible)
    val destination = Regex("(?i)\\bDestination:\\s*[A-Z0-9]{1,6}\\b")
        .containsMatchIn(visible)
    val card = Regex("(?i)<!--\\s*Station Details Card\\s*-->").containsMatchIn(html) ||
        stationCardRoot.containsMatchIn(html)
    val clock = Regex("(?is)\\b(?:ARRIVAL|DEPARTURE)\\b.{0,900}?\\b\\d{1,2}:\\d{2}\\s*(?:AM|PM)\\b")
        .containsMatchIn(html)
    return identity && update && source && destination && card && clock
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
