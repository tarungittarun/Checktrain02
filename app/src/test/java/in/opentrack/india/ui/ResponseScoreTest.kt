package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseScoreTest {
    @Test fun secondsAreOnlyShownWhenSourceClockActuallyHasThem() {
        val stamp = 1_787_000_723_000L
        assertEquals("1 sec —", ResponseScore.text(751, null))
        val minute = ResponseScore.text(751, stamp)
        val precise = ResponseScore.text(751, stamp, hasSeconds = true)
        assertEquals("1 sec " + `in`.opentrack.india.core.RailTime.formatClock(stamp), minute)
        assertEquals("1 sec " + `in`.opentrack.india.core.RailTime.formatClockSeconds(stamp), precise)
        assertEquals(1, minute.count { it == ':' })
        assertEquals(2, precise.count { it == ':' })
        assertEquals(751L, ResponseScore.durationMillis("answered · checked in 751 ms"))
        assertEquals(null, ResponseScore.durationMillis("no response"))
    }
}
