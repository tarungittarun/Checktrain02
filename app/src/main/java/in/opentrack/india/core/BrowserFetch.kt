package `in`.opentrack.india.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * A request issued through the system browser engine instead of `java.net.HttpURLConnection`.
 *
 * Why this exists, stated plainly, because it is the unusual part of this app: a phone in Bengaluru connected
 * the TCP socket to the railway enquiry endpoint in 94 ms, resolved cleanly, and then had every TLS handshake
 * hung up on - `IOException: closed`, ~400 ms, eleven times, across every request shape and both TLS profiles
 * this app could produce. Chrome on the same phone, same Wi-Fi, same minute, loaded the same site. The only
 * remaining difference between the two was the client: the browser's TLS fingerprint, ALPN set and header
 * order are examined by the far end, and this app's platform HTTP stack does not survive that examination.
 *
 * So rather than ship a proxy or a key, the app asks with the browser's own network stack - which is already
 * on the device, is the thing that demonstrably works there, and costs nothing to use.
 *
 * What this is NOT: a browser, a web view in the UI, or a way for a page to reach the app.
 *
 *  - The view is never attached to a window and is never shown. The traveller sees the same screens as before.
 *  - JavaScript is enabled only to read back the response text of the API document itself; there is no
 *    `addJavascriptInterface`, so no page - not even a hostile error page served in place of the API - can
 *    call into this process.
 *  - File, content and database access are off; the cache is off; nothing is persisted by us. The WebView's
 *    own cookie jar stays in the app's data directory, which is what lets a session cookie work, and it is
 *    cleared when the user clears the live cache from Settings.
 *  - No third-party page is ever loaded here: the only URLs are the railway endpoints this app already calls.
 *
 * Threading: `WebView` insists on one thread, so the work is posted to the main looper and awaited on the
 * caller's (an IO) thread. The main thread is never blocked - the latch is on the other side - and a load that
 * never finishes is abandoned at the timeout, after which the view is torn down so one stuck page cannot
 * poison the next refresh.
 */
object BrowserFetch {

    @Volatile
    private var view: WebView? = null

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())
    private val creating = Any()

    /** True once a fetch has been attempted, so a report can tell "not tried" from "tried and refused". */
    @Volatile
    var tried: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** A cold web view plus a page load is not a socket write: 5 s was too tight, measured on a device. */
    val defaultTimeout = 9_000

    fun get(url: String, timeoutMillis: Int = defaultTimeout, headers: Map<String, String> = emptyMap()): Http.Result =
        exchange(url, null, headers, timeoutMillis, "GET", read = true)

    /**
     * The envelope as a form field. `postUrl` fixes Content-Type at `application/x-www-form-urlencoded`, so a
     * raw JSON body is a request whose declared shape does not match its contents - which is exactly the kind
     * of thing an ASP.NET endpoint answers with a short error object. The 159-byte answer this produced is the
     * reason the variant exists, and the log carries what it said.
     */
    fun postForm(url: String, formKey: String, value: String, timeoutMillis: Int = defaultTimeout): Http.Result =
        exchange(url, formKey + "=" + java.net.URLEncoder.encode(value, "UTF-8"), emptyMap(), timeoutMillis, "POST-form")

    /**
     * Visit the site, then post - so any cookie the site hands its own scripts is in the jar when the API is
     * asked. The warm-up document is deliberately not read back: only whether a page finished matters, and a
     * 400 KB HTML page has no business in a log or a parser.
     */
    fun postAfterSession(siteUrl: String, url: String, body: String, timeoutMillis: Int = defaultTimeout): Http.Result {
        runCatching { exchange(siteUrl, null, emptyMap(), timeoutMillis, "WARM", read = false) }
        return exchange(url, body, emptyMap(), timeoutMillis, "POST+site-session")
    }

    /**
     * `postUrl` fixes the Content-Type at `application/x-www-form-urlencoded`, so the JSON envelope travels as
     * the raw body the endpoint reads from its input stream. Whether this server inspects the header is exactly
     * what the probe line answers; nothing is assumed.
     */
    fun post(url: String, body: String, timeoutMillis: Int): Http.Result =
        exchange(url, body, emptyMap(), timeoutMillis, "POST")

    /** Clears the browser engine's cookie jar; wired to the same button that clears the app's live cache. */
    fun clearCookies() {
        val ctx = appContext ?: return
        runCatching {
            main.post {
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                view?.let { dead ->
                    view = null
                    runCatching { dead.loadUrl("about:blank") }
                    runCatching { dead.destroy() }
                }
            }
        }
        Diary.get()?.log("BROWSER", "cookies cleared for ctx=" + ctx.javaClass.simpleName)
    }

    private fun exchange(
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMillis: Int,
        kind: String,
        read: Boolean = true,
    ): Http.Result {
        val ctx = appContext ?: return Http.Result.Failed(
            Http.Result.Kind.HTTP, "BrowserFetch not initialised (no application context)",
        )
        tried = true
        val t0 = System.currentTimeMillis()
        val done = CountDownLatch(1)
        val text = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)

        runCatching {
            main.post {
                runCatching {
                    val w = ensureView(ctx)
                    var pending = true
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, pageUrl: String) {
                            if (!pending) return
                            pending = false
                            if (read) readBack(v, text, done) else done.countDown()
                        }

                        override fun onReceivedError(
                            v: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame || !pending) return
                            pending = false
                            failure.set("webview " + (error.description ?: "load error"))
                            done.countDown()
                        }
                    }
                    if (body == null) w.loadUrl(url, headers) else w.postUrl(url, body.toByteArray(Charsets.UTF_8))
                }.onFailure {
                    failure.set(it.javaClass.simpleName + ": " + it.message)
                    pendingAll(done)
                }
            }
        }.onFailure {
            failure.set("main-looper unavailable: " + it.javaClass.simpleName)
            pendingAll(done)
        }

        val answered = done.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        val ms = System.currentTimeMillis() - t0
        val verdict: Http.Result = when {
            !answered -> {
                abandon()
                Http.Result.Failed(Http.Result.Kind.TIMEOUT, "BrowserFetch $kind got no document in ${timeoutMillis}ms")
            }
            failure.get() != null -> Http.Result.Failed(Http.Result.Kind.HTTP, failure.get()!!)
            else -> {
                val got = text.get().orEmpty()
                if (got.isBlank()) Http.Result.Failed(Http.Result.Kind.EMPTY, "browser answered with an empty document")
                else Http.Result.Ok(got, fromCache = false, ageMillis = 0)
            }
        }
        Diary.get()?.log("BROWSER") {
            kind + " " + url.substringAfter("://", url).substringBefore("/") +
                (if (body == null) "" else " body=" + body.length + "B") + " -> " +
                (if (verdict is Http.Result.Ok) "answered " + verdict.body.length + "B"
                 else (verdict as Http.Result.Failed).let { it.kind.toString() + " " + it.message }) +
                " in " + ms + "ms"
        }
        lastError = (verdict as? Http.Result.Failed)?.message
        return verdict
    }

    private fun pendingAll(done: CountDownLatch) {
        runCatching { done.countDown() }
    }

    /**
     * The document is JSON or text, so `documentElement.textContent` is the payload itself. What
     * evaluateJavascript hands back is a JSON-encoded string literal, so it is decoded as one - which also
     * means a page that tries to smuggle quotes or newlines into the parse gets a failure, not a splice.
     */
    private fun readBack(v: WebView, text: AtomicReference<String?>, done: CountDownLatch) {
        runCatching {
            v.evaluateJavascript(
                "(document.body && document.body.innerText !== undefined && document.body.innerText.length > 0)" +
                    " ? document.body.innerText : (document.documentElement ? document.documentElement.textContent : '')",
            ) { raw ->
                val decoded = runCatching { JSONObject("{\"v\":" + (raw ?: "null") + "}").optString("v") }
                    .getOrNull()
                if (decoded.isNullOrBlank()) text.set(raw?.trim()?.trim('"')) else text.set(decoded)
                done.countDown()
            }
        }.onFailure {
            text.set(null)
            done.countDown()
        }
    }

    private fun ensureView(ctx: Context): WebView {
        view?.let { return it }
        synchronized(creating) {
            view?.let { return it }
            val w = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportZoom(false)
                isVerticalScrollBarEnabled = false
                setBackgroundColor(0)
            }
            view = w
            return w
        }
    }

    /** A load that never reported back is not reusable: drop the engine so the next attempt starts clean. */
    private fun abandon() {
        val dead = view ?: return
        view = null
        main.post {
            runCatching { dead.loadUrl("about:blank") }
            runCatching { dead.destroy() }
        }
    }
}
