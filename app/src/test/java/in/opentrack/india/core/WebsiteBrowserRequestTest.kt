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
        val classlessRenderedDom = renderedDom
            .replace("<!-- Station Details Card -->", "")
            .replace("class=\"flex-grow p-3 rounded-lg bg-green-50 opacity-90\"",
                "class=\"station-timeline-item\"")
        assertTrue("a hydrated station card remains discoverable without source comments or known CSS classes",
            hasPopulatedTrainDocument(classlessRenderedDom, "16227"))
        val entityClock = classlessRenderedDom.replace("04:20 AM", "04:20&nbsp;AM")
        assertTrue("HTML non-breaking spaces between a clock and meridiem remain readable",
            hasPopulatedTrainDocument(entityClock, "16227"))
        val namedEndpoints = renderedDom.replace("Source: MYS", "Source: Mysuru Junction")
            .replace("Destination: TLGP", "Destination: Talguppa")
        assertTrue("visible station names, not only short codes, indicate a hydrated document",
            hasPopulatedTrainDocument(namedEndpoints, "16227"))
    }

    @Test fun hydratedCardsWithSemanticWrappersAndHeadingLevelsPassReadiness() {
        val cards = listOf(
            """<section class="flex-grow rounded-lg"><h2>Arasalu (ARU)</h2>
                <div>ARRIVAL</div><div>04:20 AM</div></section>""",
            """<article class="flex-grow rounded-lg"><h4>Birur (RRB)</h4>
                <div>DEPARTURE</div><div>04:25 AM</div></article>""",
            """<li class="timeline-station"><h5>Tarikere (TKE)</h5>
                <div>ARRIVAL</div><div>04:30 AM</div></li>""",
            """<table><tr class="timeline-station"><td><h6>Bhadravati (BDVT)</h6>
                <div>ARRIVAL</div><div>04:35 AM</div></td></tr></table>""",
        )
        for (card in cards) {
            val document = """<html><body><h1>Train 16227 Running Status</h1>
                <p>Last Updated: 04:25:10 AM</p><p>Source: MYS</p>
                <p>Destination: TLGP</p>$card</body></html>""".trimIndent()
            assertTrue("semantic card was not recognized: $card",
                hasPopulatedTrainDocument(document, "16227"))
        }
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
