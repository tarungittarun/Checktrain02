package `in`.opentrack.india.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import `in`.opentrack.india.core.Diary
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * The app's entire networking surface: one GET helper.
 *
 * Why not OkHttp/Retrofit: they would add ~350 kB + a transitive tree, and every feature we need of
 * them — a timeout, a retry, a gzip header, a response cache — is ~40 lines here. The other reason is
 * the failure mode this app keeps hitting: a proxy-free client must degrade *predictably* when the
 * endpoint is slow or hostile, and with a 40-line helper the degradation is auditable rather than
 * buried in an interceptor chain.
 *
 * Cache policy: every successful body is written to `cacheDir/http/<sha1>`. On failure we serve the
 * cached copy and mark it stale — that is what makes "live status" still show *something* (with an
 * explicit age) inside a tunnel, and it means a provider outage degrades freshness instead of
 * blanking the screen.
 */
class Http(context: Context) {

    private val cacheRoot = File(context.cacheDir, "http").apply { mkdirs() }

    /**
     * Every socket operation in the app is serialised on this monitor.
     *
     * The reason is a bug this class caused rather than one it observed. Android's HttpURLConnection keeps a
     * process-wide pool of keep-alive sockets, and `disconnect()` does not merely drop *this* connection - it
     * closes the pooled socket. With one shared [Http] and several things fetching at once (the startup read in
     * `App`, the 60-second auto-refresh, a fallback source, a diagnostic probe), thread A could `disconnect()`
     * while thread B was still mid-handshake on that socket, and B would see `IOException: closed` without
     * anything having been wrong with its request.
     *
     * That is what a live-feed report from a phone in Bengaluru looked like: three unrelated hosts, every one
     * "closed" in about 400 ms, including a site this app has never failed against. A field that rejects us
     * answers; a torn connection pool hangs up. Serialising costs the app almost nothing (all of its traffic is
     * a few small requests per minute, to one public endpoint that deserves that pacing anyway) and buys the
     * only measurement of the feed that can be trusted.
     */
    private val socketLock = Any()

    private inline fun <T> serial(block: () -> T): T = synchronized(socketLock) { block() }

    sealed class Result {
        data class Ok(val body: String, val fromCache: Boolean, val ageMillis: Long) : Result()
        data class Failed(val kind: Kind, val message: String) : Result()
        enum class Kind { OFFLINE, TIMEOUT, HTTP, EMPTY, PARSE }
    }

    /**
     * Blocking GET. Call from an IO dispatcher.
     *
     * @param retries 1 retry for a GET is free of side effects and covers the single dropped
     *   connection that dominates failures on mobile networks; more would just stack latency.
     */
    fun get(
        url: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT,
        retries: Int = 1,
        headers: Map<String, String> = emptyMap(),
    ): Result {
        var last: Result = Result.Failed(Result.Kind.EMPTY, "not attempted")
        var attempt = 0
        while (attempt <= retries) {
            attempt++
            last = tryOnce(url, timeoutMillis, headers)
            Diary.get()?.log("HTTP") { "GET " + hostOf(url) + " try#" + attempt + " -> " + verdict(last) }
            if (last is Result.Ok || last !is Result.Failed) return last
            if (last.kind == Result.Kind.HTTP) break            // 4xx/5xx will not fix itself
            if (attempt <= retries) sleep(300L * attempt)
        }
        if (last is Result.Failed) {
            Diary.get()?.log("HTTP", "GET " + hostOf(url) + " served the response cache instead: " + verdict(last))
            cached(url)?.let { (body, age) ->
                Log.d(TAG, "serving cached body (${age / 1000}s old) after ${last.kind}")
                return Result.Ok(body, fromCache = true, ageMillis = age)
            }
        }
        return last
    }

    /**
     * Blocking POST with an explicit body and headers. Uncached on purpose: the cache here is keyed by URL,
     * and a live-status POST asks a different question on every call — caching it would let the app serve a
     * ten-minute-old answer as though it had just arrived, which is the one thing this product must not do.
     */
    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMillis: Int = DEFAULT_TIMEOUT,
        retries: Int = 0,
        tls12: Boolean = false,
    ): Result {
        var attempt = 0
        var last: Result = Result.Failed(Result.Kind.EMPTY, "not attempted")
        while (attempt <= retries) {
            attempt++
            last = tryPost(url, body, headers, timeoutMillis, tls12)
            Diary.get()?.log("HTTP") {
                "POST " + hostOf(url) + " try#" + attempt + (if (tls12) " tls1.2" else "") +
                    " body=" + body.length + "B -> " + verdict(last)
            }
            if (last is Result.Ok) return last
            if (last is Result.Failed && last.kind == Result.Kind.HTTP) break
            if (attempt <= retries) sleep(400L * attempt)
        }
        return last
    }

    private fun tryPost(
        url: String, body: String, headers: Map<String, String>, timeoutMillis: Int, tls12: Boolean = false,
    ): Result = serial {
        var conn: HttpURLConnection? = null
        var bodyRead = false
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Connection", "close")
                for ((k, v) in headers) setRequestProperty(k, v)
                if (tls12 && this is HttpsURLConnection) this.sslSocketFactory = tls12Factory()
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code >= 400) return Result.Failed(Result.Kind.HTTP, "HTTP $code")
            val stream: InputStream = conn.inputStream.use { raw ->
                if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(raw) else raw
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            stream.close()
            bodyRead = true
            if (text.isBlank()) Result.Failed(Result.Kind.EMPTY, "empty body")
            else Result.Ok(text, fromCache = false, ageMillis = 0)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Result.Kind.TIMEOUT, failText(e, "timeout"))
        } catch (e: IOException) {
            val offline = e.message?.let {
                it.contains("Unable to resolve host") || it.contains("ENETUNREACH") || it.contains("ENONET")
            } == true
            // The class name travels with the message on purpose. "The live feed refused the request (closed)"
            // was true and useless: an SSLException closed mid-handshake, a server that answers HTTP 403, and a
            // carrier middlebox that resets the socket are different bugs with different fixes, and only the
            // device can tell them apart. Costs 20 characters, saves a round trip measured in days.
            Result.Failed(
                if (offline) Result.Kind.OFFLINE else Result.Kind.HTTP,
                "${e.javaClass.simpleName}: ${e.message ?: "io error"}",
            )
        } catch (e: Throwable) {
            Result.Failed(Result.Kind.PARSE, failText(e, "unexpected"))
        } finally {
            if (!bodyRead) runCatching { conn?.disconnect() }
        }
    }

    /**
     * A TLS 1.2 socket from the platform trust anchors. Not a trust *relaxation* — the default trust manager is
     * kept — only the protocol choice, which is what a legacy server that refuses modern renegotiation needs.
     * Built lazily and never cached across failures, because a broken SSLContext must not poison every later
     * request the app makes.
     */
    private fun tls12Factory(): javax.net.ssl.SSLSocketFactory =
        javax.net.ssl.SSLContext.getInstance("TLSv1.2").apply {
            init(null, null, null)
        }.socketFactory

    /**
     * One line per attempt: host and verdict. The query string is left out deliberately - a query is where a
     * train number and a travel date live, and a log the user pastes into a public issue should not become a
     * record of the trains they followed. Body size yes, body contents never; that is also why the encrypted
     * POST envelope is measured rather than quoted.
     */
    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore("/").substringBefore("?")

    /**
     * "IOException: closed" rather than "closed" - and built by concatenation rather than a template on
     * purpose. This project has twice shipped diagnostic text where a patch script's escaping leaked into the
     * source, so a literal template placeholder reached a user's screen; the string is load-bearing enough
     * should not depend on any tooling understanding it. tools/ci/check_literals.py fails the build on a leak.
     */
    private fun failText(e: Throwable, fallback: String): String =
        e.javaClass.simpleName + ": " + (e.message ?: fallback)

    private fun verdict(r: Result): String = when (r) {
        is Result.Ok -> "answered " + r.body.length + "B" +
            (if (r.fromCache) " from cache age=" + (r.ageMillis / 1000) + "s" else "")
        is Result.Failed -> r.kind.toString() + " " + r.message
    }

    private fun tryOnce(url: String, timeoutMillis: Int, headers: Map<String, String> = emptyMap()): Result = serial {
        var conn: HttpURLConnection? = null
        var bodyRead = false
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = true
                requestMethod = "GET"
                // A browser-ish UA is required: several public Indian Railways endpoints reset
                // connections from clients with no User-Agent at all.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                // Some of these endpoints answer with a redirect to a maintenance page; follow once.
                val loc = conn.getHeaderField("Location")
                    ?: return Result.Failed(Result.Kind.HTTP, "redirect without location")
                conn.disconnect()
                conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMillis; readTimeout = timeoutMillis
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept-Encoding", "gzip")
                }
            }
            if (conn.responseCode >= 400) {
                return Result.Failed(Result.Kind.HTTP, "HTTP ${conn.responseCode}")
            }
            val stream: InputStream = conn.inputStream.use { raw ->
                if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(raw) else raw
            }
            val body = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            stream.close()
            bodyRead = true
            if (body.isBlank()) return Result.Failed(Result.Kind.EMPTY, "empty body")
            store(url, body)
            Result.Ok(body, fromCache = false, ageMillis = 0)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Result.Kind.TIMEOUT, failText(e, "timeout"))
        } catch (e: IOException) {
            val offline = e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("ENETUNREACH") == true || e.message?.contains("ENONET") == true
            Result.Failed(
                if (offline) Result.Kind.OFFLINE else Result.Kind.HTTP,
                failText(e, "io error"),
            )
        } catch (e: Throwable) {
            Result.Failed(Result.Kind.HTTP, failText(e, "unexpected"))
        } finally {
            // Only a request that never finished reading may disconnect. A completed one hands its socket back
            // to the pool on purpose; pulling the rug out from under the pool is the fault described above.
            if (!bodyRead) runCatching { conn?.disconnect() }
        }
    }

    private fun cacheFile(url: String): File =
        File(cacheRoot, Hash.sha1(url))

    private fun store(url: String, body: String) {
        runCatching {
            val f = cacheFile(url)
            f.parentFile?.mkdirs()
            // header line = fetch time; a corrupt cache file must never be fatal, hence runCatching
            f.writeText(System.currentTimeMillis().toString() + "\n" + body)
        }
    }

    private fun cached(url: String): Pair<String, Long>? {
        val f = cacheFile(url)
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return null
            val ts = text.substring(0, nl).toLongOrNull() ?: return null
            val age = System.currentTimeMillis() - ts
            if (age > MAX_CACHE_AGE || age < 0) return null
            text.substring(nl + 1) to age
        }.getOrNull()
    }

    /** Drop every cached body — used by "reset data" in Settings. */
    fun clearCache() = runCatching { cacheRoot.listFiles()?.forEach { it.delete() } }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        private const val TAG = "Http"
        const val DEFAULT_TIMEOUT = 12_000
        private const val MAX_CACHE_AGE = 24 * 60 * 60_000L
        private const val USER_AGENT =
            "OpenTrackIndia/1.0 (offline-first railway tracker; no accounts, no tracking)"
    }
}

/** Tiny hashing helpers; MessageDigest is cheaper than pulling in a library for two uses. */
object Hash {
    fun sha1(text: String): String = digest("SHA-1", text)

    fun sha256Hex(bytes: ByteArray): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { "" }

    private fun digest(algo: String, text: String): String = runCatching {
        java.security.MessageDigest.getInstance(algo).digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { text.hashCode().toString(16) }
}
