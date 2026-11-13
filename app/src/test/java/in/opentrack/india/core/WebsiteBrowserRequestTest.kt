package `in`.opentrack.india.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteBrowserRequestTest {
    @Test fun evaluateJavascriptJsonStringIsDecodedWithoutAddingAnObjectWrapper() {
        val html = """<html data-quote="a\b"><h1>Train 16227</h1>
            <p>Source: MYS → Destination: TLGP</p></html>"""
        assertEquals(html, decodeJavascriptStringResult(JSONObject.quote(html)))
    }

    @Test fun nonStringOrInvalidJavascriptResultsCannotBecomePageMarkup() {
        assertEquals("", decodeJavascriptStringResult(null))
        assertEquals("", decodeJavascriptStringResult("null"))
        assertEquals("", decodeJavascriptStringResult("42"))
        assertEquals("", decodeJavascriptStringResult("{\"html\":\"<h1>16227</h1>\"}"))
        assertEquals("", decodeJavascriptStringResult("not-json"))
        assertEquals("", decodeJavascriptStringResult("\"markup\" trailing"))
    }

    @Test fun renderedStationCardIsTheReadinessSignalEvenWithoutHtmlComments() {
        val renderedDom = """<!doctype html><html><body>
            <h1>Train 16227 Running Status</h1>
            <p>Last Updated: 04:25:10 AM</p>
            <p>Source: MYS</p><p>Destination: TLGP</p>
            <div class="flex-grow p-3 rounded-lg bg-green-50 opacity-90">
              <h3><span>Arsikere (ASK)</span></h3>
              <div>ARRIVAL</div><div>04:20 AM</div>
            </div>
            </body></html>""".trimIndent()

        assertTrue(hasPopulatedTrainDocument(renderedDom, "16227"))
    }

    @Test fun identityOrClockTextWithoutARouteCardIsNotAReadinessSignal() {
        val partial = """<html><body>
            <h1>Train 16227 Running Status</h1>
            <p>Last Updated: 04:25:10 AM</p>
            <p>Source: MYS</p><p>Destination: TLGP</p>
            <p>Journey Timeline: ARRIVAL 04:20 AM</p>
            </body></html>""".trimIndent()
        val scriptOnlyIdentity = """<html><script>Train 16227</script><body>
            <p>Last Updated: 04:25:10 AM</p>
            <p>Source: MYS</p><p>Destination: TLGP</p>
            <div class="flex-grow rounded-lg"><h3>Arsikere (ASK)</h3>
            <div>ARRIVAL 04:20 AM</div></div>
            </body></html>""".trimIndent()

        assertFalse(hasPopulatedTrainDocument(partial, "16227"))
        assertFalse(hasPopulatedTrainDocument(scriptOnlyIdentity, "16227"))
        assertFalse(hasPopulatedTrainDocument(partial, "12627"))
    }
}
