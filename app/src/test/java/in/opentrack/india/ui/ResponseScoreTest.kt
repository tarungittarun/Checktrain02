package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseScoreTest {
    @Test fun secondsAreOnlyShownWhenSourceClockActuallyHasThem() {
        val stamp = 1_787_000_723_000L
        assertEquals("Took: 0.8 sec\nAt: —", ResponseScore.text(751, null))
        val minute = ResponseScore.text(751, stamp)
        val precise = ResponseScore.text(751, stamp, hasSeconds = true)
        assertEquals("Took: 0.8 sec\nAt: " + `in`.opentrack.india.core.RailTime.formatClock(stamp), minute)
        assertEquals("Took: 0.8 sec\nAt: " + `in`.opentrack.india.core.RailTime.formatClockSeconds(stamp), precise)
        assertEquals(1, minute.substringAfter("At: ").count { it == ':' })
        assertEquals(2, precise.substringAfter("At: ").count { it == ':' })
        assertEquals("Took: 1.0 sec\nAt: —", ResponseScore.text(999, null))
        assertEquals("Took: 0.0 sec\nAt: —", ResponseScore.text(-3, null))
        assertEquals(751L, ResponseScore.durationMillis("answered · checked in 751 ms"))
        assertEquals(null, ResponseScore.durationMillis("no response"))
    }
}
