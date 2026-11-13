package `in`.opentrack.india.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    }
}
