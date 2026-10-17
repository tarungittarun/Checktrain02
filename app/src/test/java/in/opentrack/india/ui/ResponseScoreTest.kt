package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseScoreTest {
    @Test fun secondsAreOnlyShownWhenSourceClockActuallyHasThem() {
        val stamp = 1_787_000_723_000L
        assertEquals("Took: 1 sec\nAt: —", ResponseScore.text(751, null))
        val minute = ResponseScore.text(751, stamp)
        val precise = ResponseScore.text(751, stamp, hasSeconds = true)
        assertEquals("Took: 1 sec\nAt: " + `in`.opentrack.india.core.RailTime.formatClock(stamp), minute)
        assertEquals("Took: 1 sec\nAt: " + `in`.opentrack.india.core.RailTime.formatClockSeconds(stamp), precise)
        assertEquals(1, minute.substringAfter("At: ").count { it == ':' })
        assertEquals(2, precise.substringAfter("At: ").count { it == ':' })
        assertEquals(751L, ResponseScore.durationMillis("answered · checked in 751 ms"))
        assertEquals(null, ResponseScore.durationMillis("no response"))
    }
}
