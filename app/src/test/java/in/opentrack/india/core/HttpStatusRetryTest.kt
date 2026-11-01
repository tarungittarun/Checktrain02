package `in`.opentrack.india.core

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local wire test: a real refusal must not be sent again as an IPv4 'recovery'. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HttpStatusRetryTest {
    @Test(timeout = 15_000)
    fun refusedAndRateLimitedGetAreNotAutomaticallyRetried() {
        for (code in listOf(403, 429)) {
            ServerSocket(0).use { server ->
                server.soTimeout = 300
                val running = AtomicBoolean(true)
                val calls = AtomicInteger()
                val failure = AtomicReference<Throwable?>()
                val responder = Thread {
                    while (running.get()) {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 3_000
                                val header = ByteArrayOutputStream()
                                val input = socket.getInputStream()
                                while (!header.toString("UTF-8").endsWith("\r\n\r\n")) {
                                    val b = input.read()
                                    if (b < 0 || header.size() > 8192) error("missing GET headers")
                                    header.write(b)
                                }
                                calls.incrementAndGet()
                                val response = "HTTP/1.1 $code Denied\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().apply {
                                    write(response.toByteArray(Charsets.US_ASCII))
                                    flush()
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            // Recheck the shutdown flag, not an extra network attempt.
                        } catch (_: SocketException) {
                            if (running.get()) failure.set(IllegalStateException("test socket closed"))
                        } catch (e: Throwable) {
                            failure.set(e)
                            break
                        }
                    }
                }
                responder.start()
                try {
                    val result = Http(RuntimeEnvironment.getApplication()).get(
                        "http://127.0.0.1:${server.localPort}/status?test=$code",
                        timeoutMillis = 2_000, retries = 1, budgetMillis = 4_000)
                    assertTrue("expected HTTP $code, got $result", result is Http.Result.Failed)
                    assertEquals("HTTP $code", (result as Http.Result.Failed).message)
                    assertEquals("HTTP $code should have exactly one request", 1, calls.get())
                } finally {
                    running.set(false)
                    server.close()
                    responder.run {
                        join(1_000)
                        assertFalse("test server blocked", isAlive)
                    }
                    assertNull("test server failed", failure.get())
                }
            }
        }
    }
}
