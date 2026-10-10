package in.opentrack.india.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.GZIPInputStream

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
    fun get(url: String, timeoutMillis: Int = DEFAULT_TIMEOUT, retries: Int = 1): Result {
        var last: Result = Result.Failed(Result.Kind.EMPTY, "not attempted")
        var attempt = 0
        while (attempt <= retries) {
            attempt++
            last = tryOnce(url, timeoutMillis)
            if (last is Result.Ok || last !is Result.Failed) return last
            if (last.kind == Result.Kind.HTTP) break            // 4xx/5xx will not fix itself
            if (attempt <= retries) sleep(300L * attempt)
        }
        if (last is Result.Failed) {
            cached(url)?.let { (body, age) ->
                Log.d(TAG, "serving cached body (${age / 1000}s old) after ${last.kind}")
                return Result.Ok(body, fromCache = true, ageMillis = age)
            }
        }
        return last
    }

    private fun tryOnce(url: String, timeoutMillis: Int): Result {
        var conn: HttpURLConnection? = null
        return try {
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
            if (body.isBlank()) return Result.Failed(Result.Kind.EMPTY, "empty body")
            store(url, body)
            Result.Ok(body, fromCache = false, ageMillis = 0)
        } catch (e: SocketTimeoutException) {
            Result.Failed(Result.Kind.TIMEOUT, e.message ?: "timeout")
        } catch (e: IOException) {
            val offline = e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("ENETUNREACH") == true || e.message?.contains("ENONET") == true
            Result.Failed(if (offline) Result.Kind.OFFLINE else Result.Kind.HTTP,
                e.message ?: "io error")
        } catch (e: Throwable) {
            Result.Failed(Result.Kind.HTTP, e.message ?: "unexpected")
        } finally {
            runCatching { conn?.disconnect() }
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
