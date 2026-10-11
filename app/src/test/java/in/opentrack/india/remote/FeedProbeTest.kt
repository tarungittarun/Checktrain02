package `in`.opentrack.india.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-side request experiment, tested on the parts that are pure.
 *
 * The variants themselves need a network, so what is pinned here is the reasoning the probe depends on: that
 * every variant is a different question rather than a restatement, that a shape adopted for one train is
 * rebuilt for the next one instead of replayed, and that the report stays silent until something has actually
 * been measured. A diagnostic that lies by omission ("probe: nothing to report") while the network is down is
 * how a support conversation ends up chasing a request shape that was never wrong.
 */
class FeedProbeTest {

    @Test
    fun `every variant asks a different question over https`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L))
        assertTrue("a probe with ${vs.size} variants cannot distinguish much", vs.size >= 6)
        assertEquals("variant names must be unique", vs.size, vs.map { it.name }.distinct().size)
        assertEquals("the shipped shape is tried first", "post-dalvik-json", vs.first().name)
        for (v in vs) {
            assertTrue("${v.name} must not send data in the clear", v.url.startsWith("https://"))
            assertTrue("${v.name} needs an explanation for the report", v.note.length > 8)
            // The control host is the only variant allowed to be non-feed, and it is a GET of a page.
            if (v.name == "get-erail-control") assertNull("${v.name} must not POST", v.body)
        }
        // A reachability control that is a different *host* is what separates "the feed refused us" from
        // "this device has no route", so at least two distinct hosts must appear in the matrix.
        assertEquals(
            "hosts must actually be extracted (an empty or single-element set would make this vacuous)",
            4,
            vs.map { it.url.substringAfter("://").substringBefore("/") }.distinct().size,
        )
        assertEquals(
            "probe must span more than one host",
            true,
            vs.map { it.url.substringAfter("://").substringBefore("/") }.distinct().size >= 2,
        )
    }

    @Test
    fun `an adopted shape is rebuilt for the train being asked about`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L))
        val post = vs.first { it.name == "post-dalvik-json" }
        val other = FeedProbe.rebuild(post, "22691", 20738L)
        assertEquals("the URL is the same shape", post.url, other.url)
        assertFalse(
            "the envelope must be regenerated, not replayed",
            other.body == post.body,
        )
        // The body is an encrypted envelope, so the check is on the *shape* of the request and on the plain
        // variant, whose body is the query string and therefore readable here.
        val plainVariant = vs.first { it.name == "post-plain-body" }
        val rebuiltPlain = FeedProbe.rebuild(plainVariant, "22691", 20738L)
        assertNotNull(rebuiltPlain.body)
        assertTrue(
            "rebuilt body: ${rebuiltPlain.body}",
            rebuiltPlain.body!!.contains("trainNo=22691") && rebuiltPlain.body!!.contains("startDate=12-Oct-2026"),
        )
        assertFalse(
            "a control host that never resolves is not a control",
            vs.any { it.url.contains("howtherail") },
        )
        assertTrue(
            "the no-key control must exist: it separates 'this app cannot do TLS' from 'the feed refuses us'",
            vs.any { it.name == "get-railradar-no-key" },
        )
        val getNtes = vs.first { it.name == "get-ntes-web" }
        assertTrue(
            "a GET variant must carry the new train number",
            FeedProbe.rebuild(getNtes, "22691", 20738L).url.contains("trainNo=22691"),
        )
    }

    @Test
    fun `the report says nothing until a probe has run`() {
        assertNull(
            "no variant may be adopted before an answer is verified",
            FeedProbe.adoptedName,
        )
        assertFalse(FeedProbe.ready)
        assertEquals("", FeedProbe.render())
        assertNull(FeedProbe.adoptedFor("12627", 20737L))
    }

    @Test
    fun `the legacy web shape carries the headers that endpoint has always demanded`() {
        val vs = FeedProbe.variants("12627", "2026-10-11", "x")
        val legacy = vs.first { it.name == "get-ntes-web" }
        // Not decoration: that endpoint answers a request that presents itself as the NTES site, and answers
        // with a redirect or a closed socket to one that does not. Getting this into the probe is what makes
        // the difference between a fix and a mystery in the report.
        assertTrue(legacy.headers["Referer"]?.contains("enquiry.indianrail.gov.in") == true)
        assertTrue(legacy.headers.containsKey("X-Requested-With"))
    }

    @Test
    fun `the egress control never carries an ip address into the report`() {
        val trace = "ip=103.21.58.11\nts=1760181000\nvisit_scheme=https\nuag=okhttp\ncolo=BOM\n" +
            "http=http/1.1\nloc=IN\nsloc=in\nsla=\nslop=tls\notxt=1\nproxy=none\nip_version=4\n"
        val summary = FeedProbe.egressSummary(trace)
        assertTrue("summary was: $summary", summary.contains("loc=IN"))
        assertTrue("summary was: $summary", summary.contains("proxy=none"))
        assertFalse("the device's address must not be copyable", summary.contains("103.21.58.11"))
        assertFalse(summary.contains("ip="))
        assertEquals("answered, fields unreadable", FeedProbe.egressSummary("<html>no fields here</html>"))
    }
}
