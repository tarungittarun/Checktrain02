package `in`.opentrack.india.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRecencyTest {
    @Test fun newerPastMarkedHaltCanBeOfferedAsUnverifiedComparison() {
        assertTrue(SourceRecency.showComparison(10, 11, 12 * 60_000L, 30_000L))
        assertFalse("site page clock alone is not movement",
            SourceRecency.showComparison(10, 10, 12 * 60_000L, 0L))
        assertFalse("regressing page cannot be offered",
            SourceRecency.showComparison(10, 9, 12 * 60_000L, 0L))
        assertFalse("stale website page cannot win",
            SourceRecency.showComparison(10, 11, 12 * 60_000L, 6 * 60_000L))
        assertFalse("fresh official report should not be displaced",
            SourceRecency.showComparison(10, 11, 60_000L, 0L))
    }
}
