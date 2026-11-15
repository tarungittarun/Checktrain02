package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebsiteGapSummaryUiTest {
    @Test fun compactGapDoesNotRedateOrInventArrival() {
        val full = "Page updated 04:50:08 AM; website has no YPR arrival row. " +
            "Last displayed: TK (comparison only); position unknown. This is not the phone app."
        assertEquals("No YPR site arrival · last listed TK · run/position unknown.",
            WebsiteGapSummaryUi.concise(full))
        assertEquals("Website date mismatch · site cards not attached.",
            WebsiteGapSummaryUi.concise("The .org.in page's visible run date does not match the selected origin date; " +
                "its station cards were not attached to this run."))
        assertEquals("Website route mismatch · site cards not attached.",
            WebsiteGapSummaryUi.concise("The .org.in page endpoints do not match the selected timetable; " +
                "its station cards were not attached to this route."))
        assertEquals("Website run window unverified · no event admitted.",
            WebsiteGapSummaryUi.concise("The website has no date selector and the selected origin run is not unambiguously active."))
        assertEquals("Website comparison only · see Source details.",
            WebsiteGapSummaryUi.concise("Unexpected diagnostic text that is long enough to overwhelm the first glance summary card."))
        assertEquals("other failure", WebsiteGapSummaryUi.concise("other failure"))
        assertNull(SummaryNoteUi.visible("No confirmed times from this site; timetable shown.",
            websiteGapShown = true))
        assertNull(SummaryNoteUi.visible("No confirmed times from this site; timetable shown."))
        assertEquals("Connectivity failed · previous report unchanged",
            SummaryNoteUi.visible("Connectivity failed · previous report unchanged", true))
    }
}
