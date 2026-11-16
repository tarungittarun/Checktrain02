package `in`.opentrack.india.ui

import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransientActionFeedbackTest {
    @Test fun repeatedOperationsCoalesceWhileDistinctActionsShareOneFeedbackSurface() {
        val context = RuntimeEnvironment.getApplication()
        val host = FrameLayout(context)
        val feedback = TransientActionFeedback(context, host, durationMillis = 60_000L)
        try {
            feedback.show("  Live cache cleared  ")
            feedback.show("Live cache cleared")
            feedback.show("Log deleted from this device")
            feedback.show("Log deleted from this device")

            val label = host.getChildAt(0) as TextView
            assertEquals("Live cache cleared  ·  Log deleted from this device",
                label.text.toString())
            assertEquals(android.view.View.VISIBLE, label.visibility)
        } finally {
            feedback.dispose()
        }
    }

    @Test fun overflowIsSummarizedInsteadOfStackingAnotherBottomNotice() {
        val context = RuntimeEnvironment.getApplication()
        val host = FrameLayout(context)
        val feedback = TransientActionFeedback(context, host, durationMillis = 60_000L)
        try {
            listOf("Cache cleared", "Log deleted", "Defaults saved", "Settings saved")
                .forEach(feedback::show)

            val label = host.getChildAt(0) as TextView
            assertEquals("Cache cleared  ·  Log deleted  ·  Defaults saved  ·  +1 more actions",
                label.text.toString())
        } finally {
            feedback.dispose()
        }
    }
}
