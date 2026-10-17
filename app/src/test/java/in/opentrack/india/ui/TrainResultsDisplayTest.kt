package `in`.opentrack.india.ui

import `in`.opentrack.india.model.TrainHit
import android.text.Spanned
import android.text.style.UnderlineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainResultsDisplayTest {
    private fun hit(no: String, day: Long, dep: Int, arr: Int): TrainHit = TrainHit(
        no, "Test", null, day, dep, arr, "SBC", "TK", 1, 2, 90, 0x7f)

    @Test fun sortsByAbsoluteTimesAcrossRunDaysAndByDuration() {
        val a = hit("A", 20200, 900, 1100)
        val b = hit("B", 20201, 5, 105)
        val c = hit("C", 20200, 600, 750)
        val input = listOf(a, b, c)
        assertEquals(input, TrainResultsDisplay.sorted(input, TrainResultsDisplay.Sort.SUGGESTED))
        assertEquals(listOf("C", "A", "B"), TrainResultsDisplay.sorted(input,
            TrainResultsDisplay.Sort.DEPARTURE).map { it.trainNo })
        assertEquals(listOf("C", "A", "B"), TrainResultsDisplay.sorted(input,
            TrainResultsDisplay.Sort.ARRIVAL).map { it.trainNo })
        assertEquals(listOf("B", "C", "A"), TrainResultsDisplay.sorted(input,
            TrainResultsDisplay.Sort.DURATION).map { it.trainNo })
    }

    @Test fun sundayToSaturdayUnderlineMatchesTheRailwayBits() {
        val text = TrainResultsDisplay.frequency(1 or (1 shl 6)) as Spanned
        assertEquals("Runs · S M T W T F S", text.toString())
        val spans = text.getSpans(0, text.length, UnderlineSpan::class.java)
        assertEquals(2, spans.size)
        assertEquals(listOf(7, 9), spans.map { text.getSpanStart(it) }.sorted())
        assertTrue(TrainResultsDisplay.active(1, 0))
        assertTrue(TrainResultsDisplay.active(1 shl 1, 6))
        assertEquals("Runs · days unavailable", TrainResultsDisplay.frequency(null))
    }
}
