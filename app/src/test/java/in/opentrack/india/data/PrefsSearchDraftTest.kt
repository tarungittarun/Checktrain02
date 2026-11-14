package `in`.opentrack.india.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSearchDraftTest {
    private val prefs by lazy { Prefs(RuntimeEnvironment.getApplication()) }

    @Before fun clearBefore() = prefs.clearCaches()
    @After fun clearAfter() = prefs.clearCaches()

    @Test fun unfinishedJourneyAndTrainQuerySurvivePreferenceRecreation() {
        prefs.saveSearchDraft(
            fromText = "Mysuru Jn", fromResolved = "MYS",
            toText = "Tumakuru", toResolved = "TK",
            trainText = "16227", trainResolved = "16227",
            mode = "TRAIN", epochDay = 20_751L, minuteOfDay = 1_045,
        )

        val restored = Prefs(RuntimeEnvironment.getApplication())
        assertTrue(restored.hasSearchDraft)
        assertEquals("Mysuru Jn", restored.searchFromText)
        assertEquals("MYS", restored.searchFromResolved)
        assertEquals("Tumakuru", restored.searchToText)
        assertEquals("TK", restored.searchToResolved)
        assertEquals("16227", restored.searchTrainText)
        assertEquals("16227", restored.searchTrainResolved)
        assertEquals("TRAIN", restored.searchMode)
        assertEquals(20_751L, restored.searchEpochDay)
        assertEquals(1_045, restored.searchMinuteOfDay)
    }

    @Test fun clearingSearchHistoryAlsoClearsTheSavedDraft() {
        prefs.saveSearchDraft("Mysuru", "MYS", "Tumakuru", "TK",
            "", null, "STATIONS", 20_751L, 1_045)

        prefs.clearCaches()

        assertFalse(prefs.hasSearchDraft)
        assertEquals("", prefs.searchFromText)
        assertEquals("", prefs.searchToText)
        assertEquals("", prefs.searchTrainText)
    }
}
