package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserWebsiteLinkTest {
    @Test fun `browser opens exactly the selected source URL without app parameters`() {
        assertEquals("https://whereismytrain.org.in/?trainNo=20689",
            BrowserWebsiteLink.url("20689"))
    }

    @Test fun `never let a malformed train number change the browser destination`() {
        for (number in listOf("", "2068", "206890", "20689&x=1", "../20689", "２０６８９")) {
            assertNull(number, BrowserWebsiteLink.url(number))
        }
    }
}
