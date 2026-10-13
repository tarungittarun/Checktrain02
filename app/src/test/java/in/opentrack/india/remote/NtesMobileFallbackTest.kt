package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** kilotrain's STNS example is a documented shape, not proof of a real NTES reply.
 * These tests pin the alternate request and the gates that prevent its future estimates being Act. */
class NtesMobileFallbackTest {
    private val day = 20739L // 13 Oct 2026
    private val body = """{"TrainNo":"20651","StartDate":"13-Oct-2026","STNS":[
        {"SC":"SBC","STD":"Source/15:00 13-Oct","ETD":"Source/15:00 13-Oct"},
        {"SC":"YPR","STA":"15:10","STD":"15:12","ETA":"15:22 13-Oct","ETD":"15:24 13-Oct","DARR":"12","DDEP":"12"},
        {"SC":"TK","STA":"16:00","STD":"16:02","ETA":"16:20 13-Oct","ETD":"16:22 13-Oct"},
        {"SC":"TTR","STA":"17:00","STD":"17:02","ETA":"17:20 13-Oct","ETD":"17:22 13-Oct"}
    ]}"""

    @Test
    fun `plain STNS is JSON not an AES envelope and echoes never become actual`() {
        val doc = NtesWire.unwrapJson(body)
        assertNotNull(doc)
        assertTrue(NtesMobileFallback.matchesRun(doc!!, "20651", day))
        assertFalse(NtesMobileFallback.matchesRun(doc, "20652", day))
        assertFalse(NtesMobileFallback.matchesRun(doc, "20651", day + 1))
        // R53's real encrypted STNS reply has no top-level StartDate. Dated, changed,
        // delay-bearing stop cells are still run evidence; schedule dates alone are not.
        assertTrue(NtesMobileFallback.matchesRun(JSONObject(body.replace("13-Oct-2026", "")), "20651", day))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(body.replace("13-Oct-2026", "").replace("15:22 13-Oct", "15:22 12-Oct")
                .replace("15:24 13-Oct", "15:24 12-Oct")), "20651", day,
        ))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(body.replace("13-Oct-2026", "").replace("\"DARR\":\"12\",\"DDEP\":\"12\"", "\"DARR\":\"\",\"DDEP\":\"\"")),
            "20651", day,
        ))
        val parsed = NtesWire.parse(doc.toString())!!
        assertEquals(4, parsed.rows.size)
        assertNull(parsed.rows[0].actDep) // schedule echoed in mobile ETD
        assertEquals(922, parsed.rows[1].actArr)
        assertEquals(924, parsed.rows[1].actDep)
        val now = RailTime.railMinuteToEpochMillis(day, 16 * 60 + 11)
        val safe = NtesMobileFallback.trustedRows(parsed, route(), day, now)
        assertEquals(922, safe.rows[1].actArr)
        assertEquals(924, safe.rows[1].actDep)
        assertNull("estimate after clock", safe.rows[2].actArr)
        assertNull("future estimate", safe.rows[3].actDep)
        assertNull("a rejected estimate cannot anchor delay", safe.rows[3].delayDep)
    }

    @Test
    fun `mobile request warms the same host and carries cookies into JSON POST`() {
        val seen = ArrayList<String>()
        var sent = ""
        val response = NtesMobileFallback.fetch("20651", day, open = { url ->
            seen.add(url.path)
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int {
                    if (url.path != "/mntes/") {
                        assertEquals("SID=abc", getRequestProperty("Cookie"))
                        assertEquals("https://enquiry.indianrail.gov.in/mntes/", getRequestProperty("Referer"))
                    }
                    return 200
                }
                override fun getHeaderFields(): Map<String, List<String>> =
                    if (url.path == "/mntes/") mapOf("Set-Cookie" to listOf("SID=abc; Path=/"))
                    else emptyMap()
                override fun getOutputStream() = ByteArrayOutputStream().also { out ->
                    // The caller writes after getOutputStream returns. Capture bytes at inputStream.
                    output = out
                }
                private var output: ByteArrayOutputStream? = null
                override fun getInputStream(): ByteArrayInputStream {
                    sent = output?.toString("UTF-8").orEmpty()
                    return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                }
            }
        })
        assertTrue("second request must read a body", response is `in`.opentrack.india.core.Http.Result.Ok)
        assertEquals(listOf("/mntes/", "/crisns/AppServAnd"), seen)
        assertTrue(sent.contains("\"queryType\":\"6\""))
        assertTrue(sent.contains("\"startDate\":\"13-Oct-2026\""))
    }

    private fun route(): TrainRoute = TrainRoute(
        "20651", "SBC TLGP", "SBC", "Bengaluru", "TTR", "Tiptur", 100, 120, null, null,
        listOf(
            StopRow(1, "SBC", "Bengaluru", null, null, null, null, 900, 1, 0, true),
            StopRow(2, "YPR", "Yesvantpur", null, null, null, 910, 912, 1, 5, true),
            StopRow(3, "TK", "Tumkur", null, null, null, 960, 962, 1, 70, true),
            StopRow(4, "TTR", "Tiptur", null, null, null, 1020, 1022, 1, 100, true),
        ), emptyList(),
    )
}
