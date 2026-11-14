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
            <!-- Station Details Card -->
            <div class="flex-grow p-3 rounded-lg bg-green-50 opacity-90">
              <h3><span>Arsikere (ASK)</span></h3>
              <div>ARRIVAL</div><div>04:20 AM</div>
            </div>
            </body></html>""".trimIndent()

        assertTrue(hasPopulatedTrainDocument(renderedDom, "16227"))
        assertTrue("a visible route card can also be found without source comments",
            hasPopulatedTrainDocument(renderedDom.replace("<!-- Station Details Card -->", ""), "16227"))
        val restyledCard = renderedDom.replace(
            "class=\"flex-grow p-3 rounded-lg bg-green-50 opacity-90\"",
            "class=\"site-card-layout\"")
        assertTrue("the source card boundary survives a CSS restyle",
            hasPopulatedTrainDocument(restyledCard, "16227"))
        val namedEndpoints = renderedDom.replace("Source: MYS", "Source: Mysuru Junction")
            .replace("Destination: TLGP", "Destination: Talguppa")
        assertTrue("visible station names, not only short codes, indicate a hydrated document",
            hasPopulatedTrainDocument(namedEndpoints, "16227"))
    }

    @Test fun oneWebsiteNavigationGetsARealBoundedLoadAndDomHydrationWindow() {
        assertTrue(WebsiteBrowserRequestTiming.DOCUMENT_TIMEOUT_MS >= 35_000L)
        assertTrue("browser wait must fit inside the source acceptance's 45-second window",
            WebsiteBrowserRequestTiming.DOCUMENT_TIMEOUT_MS < 45_000L)
        assertTrue("client-side timetable cards need a useful hydration window",
            WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS >= 15_000L)
        assertTrue(WebsiteBrowserRequestTiming.DOM_READINESS_WAIT_MS <
            WebsiteBrowserRequestTiming.DOCUMENT_TIMEOUT_MS)
        assertTrue(WebsiteBrowserRequestTiming.DOM_READINESS_POLL_MS in 100L..500L)
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

        val clockOutsideCard = partial.replace(
            "<p>Journey Timeline: ARRIVAL 04:20 AM</p>",
            "<div class=\"flex-grow rounded-lg\"><h3>Arsikere (ASK)</h3></div>" +
                "<p>Journey Timeline: ARRIVAL 04:20 AM</p>")
        val scriptOnlyCard = partial.replace("</body>",
            "<script><!-- Station Details Card --><div class='flex-grow rounded-lg'>" +
                "<h3>Arsikere (ASK)</h3><div>ARRIVAL 04:20 AM</div></div></script></body>")
        val commentOnlyCard = partial.replace("</body>",
            "<!-- Station Details Card <div class='flex-grow rounded-lg'>" +
                "<h3>Arsikere (ASK)</h3><div>ARRIVAL 04:20 AM</div></div> -->" +
                "</body>")
        val unclosedCommentCard = partial.replace("</body>",
            "<!-- Station Details Card <div class='flex-grow rounded-lg'>" +
                "<h3>Arsikere (ASK)</h3><div>ARRIVAL 04:20 AM</div></div>")
        val blankHeadingCard = partial.replace("</body>",
            "<!-- Station Details Card --><div class='flex-grow rounded-lg'>" +
                "<h3><span>&nbsp;</span> &#160; </h3><div>ARRIVAL 04:20 AM</div></div></body>")

        assertFalse(hasPopulatedTrainDocument(partial, "16227"))
        assertFalse(hasPopulatedTrainDocument(scriptOnlyIdentity, "16227"))
        assertFalse("a page clock elsewhere cannot make an empty card ready",
            hasPopulatedTrainDocument(clockOutsideCard, "16227"))
        assertFalse("card-like script text is not a visible timetable",
            hasPopulatedTrainDocument(scriptOnlyCard, "16227"))
        assertFalse("card-like comment text is not a visible timetable",
            hasPopulatedTrainDocument(commentOnlyCard, "16227"))
        assertFalse("an unclosed source comment hides all following card-looking markup",
            hasPopulatedTrainDocument(unclosedCommentCard, "16227"))
        assertFalse("a heading containing only HTML space entities is not a station label",
            hasPopulatedTrainDocument(blankHeadingCard, "16227"))
        assertFalse(hasPopulatedTrainDocument(partial, "12627"))
    }
}
