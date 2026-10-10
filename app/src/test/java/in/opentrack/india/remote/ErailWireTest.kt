package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the public enquiry wire parser, using payloads shaped like the real ones: `~^` between
 * records, `~` between fields, **empty fields dropped by the server**, and `~~~~~~~~` between sections.
 *
 * The fixtures are hand-built from the documented framing rather than captured, because a captured body
 * would silently start failing the day the site changes — and "the parser tolerates a changed column
 * order" is precisely the property under test here.
 */
class ErailWireTest {

    @Test
    fun splitsRecordsAndDropsEmpties() {
        val rows = ErailWire.splitRecords("12951~Mumbai Central~~Sabarmati~^12002~BCT~NDLS")
        assertEquals(2, rows.size)
        assertEquals(listOf("12951", "Mumbai Central", "Sabarmati"), rows[0])
        assertEquals(listOf("12002", "BCT", "NDLS"), rows[1])
    }

    @Test
    fun sectionsAreSeparatedFromRecords() {
        val text = "12951~Rajdhani~~~~~~~~~~~~~~~~~~~1~BCT~Mumbai Central~17:05~17:05~^2~BRC~Vadodara~22:31~22:41~+10~3"
        val parsed = ErailWire.parse(text)
        assertEquals(2, parsed.stops.size)
        assertEquals("BCT", parsed.stops[0].code)
        assertEquals(1025, parsed.stops[0].schedDep)
        assertEquals("BRC", parsed.stops[1].code)
        assertEquals(1351, parsed.stops[1].schedArr)
        assertEquals(1361, parsed.stops[1].schedDep)
        // The live row sent no actual times, so they stay null: "we do not know" beats "00:00".
        assertNull(parsed.stops[1].actArr)
        assertEquals(10, parsed.stops[1].delayArr)
        assertEquals("3", parsed.stops[1].platform)
    }

    @Test
    fun aHaltReachedAfterMidnightIsLateByMinutesNotEarlyByADay() {
        // Scheduled 23:50, reported by the feed as "00:20" with no day attached — an overnight train losing
        // half an hour, the normal case rather than the edge one. A plain subtraction of these two clocks
        // answers -1410, which becomes a "23 h 30 m early" chip, carries a 23-hour lead forward to every halt
        // still ahead, and bends the reported curve back on itself on the chart.
        val text = "12951~Night test~~~~~~~~~~~~~~~~~~~1~MID~Itarshi~23:50~23:55~00:20~00:25~^2~DST~Dest~00:30~00:35~00:40~00:45"
        val parsed = ErailWire.parse(text)
        assertEquals(2, parsed.stops.size)
        val late = parsed.stops[0]
        assertEquals(1430, late.schedArr)
        assertEquals(20, late.actArr)
        assertEquals("the delay is the short way round the clock", 30, late.delayArr)
        assertEquals(30, late.delayDep)
        // The next halt, whose *schedule* is already past midnight, keeps its own day: 00:40 against 00:30.
        assertEquals(10, parsed.stops[1].delayArr)
    }

    @Test
    fun aFeedThatSaysOnTimeIsAReportAndNotAnAbsenceOfOne() {
        // "+0" is a statement. Dropping it so the delay could be "re-derived" from the clocks made an on-time
        // halt past midnight inherit whatever the wrap-around subtraction happened to produce.
        val text = "12951~Night test~~~~~~~~~~~~~~~~~~~1~MID~Itarshi~23:50~23:55~23:50~23:55~+0"
        val stop = ErailWire.parse(text).stops[0]
        assertEquals("an on-time report stays a report", 0, stop.delayArr)
    }

    @Test
    fun aStationCodeAndATimeMakeARow() {
        val stop = ErailWire.stopFrom(listOf("NDLS", "New Delhi", "07:50", "07:55"), 0, null)
        assertNotNull(stop)
        assertEquals("NDLS", stop!!.code)
        assertEquals(RailTime.parseHm("07:50"), stop.schedArr)
        assertEquals(RailTime.parseHm("07:55"), stop.schedDep)
        // Source station: no arrival at all, and that must not reject the row.
        assertNull(stop.actArr)
    }

    @Test
    fun rowsWithoutCodeOrTimeAreNotStops() {
        assertNull(ErailWire.stopFrom(listOf("1", "2"), 0, null))
        assertNull(ErailWire.stopFrom(listOf("12951", "RAJDHANI", "EXPRESS"), 0, null))
    }

    @Test
    fun bareSmallIntegersAreNeverReadAsDelay() {
        // [code, name, SA, SD, distance, day] — "1" is the day of the run, not "1 minute late".
        val stop = ErailWire.stopFrom(listOf("BRC", "Vadodara Jn", "22:31", "22:41", "393", "1"), 0, null)
        assertNotNull(stop)
        assertNull(stop!!.delayArr)
        assertNull(stop.delayDep)
    }

    @Test
    fun signedDelayWinsAndDerivesOtherwise() {
        val signed = ErailWire.stopFrom(listOf("BRC", "Vadodara Jn", "22:31", "22:41", "+14", "-2"), 0, null)
        assertEquals(14, signed!!.delayArr)
        assertEquals(-2, signed.delayDep)

        val derived = ErailWire.stopFrom(listOf("BRC", "Vadodara Jn", "22:31", "22:41", "22:45", "22:55"), 0, null)
        assertEquals(14, derived!!.delayArr)
        assertEquals(14, derived.delayDep)
    }

    @Test
    fun columnHintOverridesTheShapeHeuristic() {
        // Settings said "the columns are 2:code, 3:sa, 4:sd, 5:da" for a feed that changed its order.
        val fields = listOf("x", "y", "RTM", "Ratlam Jn", "23:55", "00:05", "33")
        val stop = ErailWire.stopFrom(fields, 7, "2:code,4:sa,5:sd,6:da")
        assertEquals("RTM", stop!!.code)
        assertEquals(RailTime.parseHm("23:55"), stop.schedArr)
        assertEquals(RailTime.parseHm("00:05"), stop.schedDep)
        assertEquals(33, stop.delayArr)
        assertEquals(7, stop.seq)
    }

    @Test
    fun errorSentinelsProduceNothingUsefulAndSaySo() {
        // The service returns these as a single record; a parser that "found" a train here would show
        // the user a blank live screen instead of a retry.
        for (sentinel in listOf(
            "~~~~~Please try again after some time.",
            "~~~~~Train not found",
            "~~~~~From station not found",
        )) {
            val parsed = ErailWire.parse(sentinel)
            assertTrue("expected nothing from <$sentinel>", parsed.stops.isEmpty())
        }
    }

    @Test
    fun headerCarriesTrainIdentityAndPositionSentence() {
        val text = "12951~MUMBAI CENTRAL - SABARMATI EXPRESS~BCT~SBAT~1503 km~~~~~~~~~~~~~~~~~~~" +
            "Train is running, last reported at ITARSI BG"
        val parsed = ErailWire.parse(text)
        val header = parsed.header
        assertNotNull(header)
        assertEquals("12951", header!!.trainNo)
        assertEquals("BCT", header.srcCode)
        assertEquals("SBAT", header.dstCode)
        assertEquals(1503, header.totalKm)
        assertTrue(header.positionLine!!.contains("ITARSI"))
    }

    @Test
    fun truncatedTailStillYieldsTheStopsItUnderstood() {
        val text = "1~BCT~Mumbai Central~17:05~^2~BRC~Vadodara~22:31~^3~RTM~Ratlam~~~"
        val parsed = ErailWire.parse(text)
        assertEquals(3, parsed.stops.size)
        assertEquals("RTM", parsed.stops[2].code)
        assertNull(parsed.stops[2].schedDep)
    }
}
