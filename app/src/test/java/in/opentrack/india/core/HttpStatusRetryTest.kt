package `in`.opentrack.india.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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
            val calls = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/status") { request ->
                calls.incrementAndGet()
                request.sendResponseHeaders(code, -1)
                request.close()
            }
            server.start()
            try {
                val result = Http(RuntimeEnvironment.getApplication()).get(
                    "http://127.0.0.1:${server.address.port}/status?test=$code",
                    timeoutMillis = 2_000, retries = 1, budgetMillis = 4_000)
                assertTrue("expected HTTP $code, got $result", result is Http.Result.Failed)
                assertEquals("HTTP $code", (result as Http.Result.Failed).message)
                assertEquals("HTTP $code should have exactly one request", 1, calls.get())
            } finally {
                server.stop(0)
            }
        }
    }
}
