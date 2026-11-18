package `in`.opentrack.india.remote

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local-only transport tests: no WIMT request is made. */
class WimtLiveProviderTransportTest {
    private data class Reply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = byteArrayOf())

    private fun withServer(replies: List<Reply>, test: (String, AtomicReference<Throwable?>) -> Unit) {
        val server = ServerSocket(0)
        server.soTimeout = 5_000
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                repeat(replies.size) { index ->
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val reply = replies[index]
                        val reason = when (reply.status) {
                            200 -> "OK"
                            429 -> "Too Many Requests"
                            503 -> "Service Unavailable"
                            else -> "Test"
                        }
                        val headers = buildString {
                            append("HTTP/1.1 ${reply.status} $reason\r\n")
                            reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                            append("Content-Length: ${reply.body.size}\r\nConnection: close\r\n\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(StandardCharsets.US_ASCII))
                            write(reply.body)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }.apply { name = "wimt-local-http-test"; isDaemon = true; start() }
        try {
            test("http://127.0.0.1:${server.localPort}/live_status", serverFailure)
        } finally {
            runCatching { server.close() }
            worker.join(5_000)
        }
        assertFalse("local test server did not terminate", worker.isAlive)
        assertEquals(null, serverFailure.get())
    }

    @Test
    fun transientServerFailureIsRetriedOnceThenReturnedWithAttemptEvidence() = withServer(
        listOf(
            Reply(503, mapOf("Retry-After" to "0")),
            Reply(200, mapOf("Content-Type" to "application/json"), "{}".toByteArray()),
        ),
    ) { url, _ ->
        val result = WimtLiveProvider.request(url)
        assertEquals(200, result.status)
        assertEquals("{}", result.body)
        assertEquals(2, result.attemptCount)
        assertEquals("HTTP_503_SERVER_ERROR", result.retryReason)
        assertEquals(null, result.failureClass)
    }

    @Test
    fun rateLimitIsNotRetried() = withServer(
        listOf(Reply(429, mapOf("Retry-After" to "120"))),
    ) { url, _ ->
        val result = WimtLiveProvider.request(url)
        assertEquals(429, result.status)
        assertEquals(1, result.attemptCount)
        assertEquals("HTTP_429_RATE_LIMITED", result.failureClass)
        assertEquals(120, result.retryAfterSeconds)
    }

    @Test
    fun gzipResponseIsDecodedBeforeParsingAndByteCounted() = withServer(
        listOf(Reply(200, mapOf("Content-Encoding" to "gzip"), gzip("{\"ok\":true}"))),
    ) { url, _ ->
        val result = WimtLiveProvider.request(url)
        assertEquals(200, result.status)
        assertEquals("{\"ok\":true}", result.body)
        assertEquals("gzip", result.contentEncoding)
        assertEquals(result.body!!.toByteArray().size, result.responseBytes)
        assertNotNull(result.receivedAtMillis)
        assertNotNull(result.headerWaitMillis)
        assertNotNull(result.bodyReadMillis)
        assertTrue(result.headerWaitMillis!! >= 0L)
        assertTrue(result.bodyReadMillis!! >= 0L)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
        output.toByteArray()
    }
}
