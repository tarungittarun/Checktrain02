package `in`.opentrack.india.ui

import android.widget.Button
import `in`.opentrack.india.R
import `in`.opentrack.india.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchDraftPersistenceTest {
    private val prefs by lazy { Prefs(RuntimeEnvironment.getApplication()) }

    @Before fun clearBefore() = prefs.clearCaches()
    @After fun clearAfter() = prefs.clearCaches()

    @Test fun journeyAndTrainQuerySurviveModeSwitchAndActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val from = activity.findViewById<SuggestBox>(R.id.fromBox)
        val to = activity.findViewById<SuggestBox>(R.id.toBox)
        val train = activity.findViewById<SuggestBox>(R.id.trainBox)
        from.setTextShown("Mysuru Jn")
        from.resolvedCode = "MYS"
        to.setTextShown("KSR Bengaluru")
        to.resolvedCode = "SBC"
        train.setTextShown("16227")
        train.resolvedCode = "16227"

        activity.findViewById<Button>(R.id.modeTrain).performClick()
        assertTrue("mode changes should save the current search draft", prefs.hasSearchDraft)
        controller.recreate()

        val restored = controller.get()
        assertEquals("Mysuru Jn", restored.findViewById<SuggestBox>(R.id.fromBox).text)
        assertEquals("MYS", restored.findViewById<SuggestBox>(R.id.fromBox).resolvedCode)
        assertEquals("KSR Bengaluru", restored.findViewById<SuggestBox>(R.id.toBox).text)
        assertEquals("SBC", restored.findViewById<SuggestBox>(R.id.toBox).resolvedCode)
        assertEquals("16227", restored.findViewById<SuggestBox>(R.id.trainBox).text)
        assertEquals("16227", restored.findViewById<SuggestBox>(R.id.trainBox).resolvedCode)
        assertEquals("TRAIN", prefs.searchMode)
    }
}
