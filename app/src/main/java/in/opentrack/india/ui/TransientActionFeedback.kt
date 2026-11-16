package `in`.opentrack.india.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import `in`.opentrack.india.R

/** A single bottom-of-screen feedback surface: duplicates coalesce and distinct actions summarize in place. */
internal class TransientActionFeedback(
    context: Context,
    private val host: FrameLayout,
    private val durationMillis: Long = 2_600L,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val messages = linkedSetOf<String>()
    private val visibleMessages = mutableListOf<String>()
    private var overflowCount = 0
    private val label = TextView(context).apply {
        textSize = 14f
        setTextColor(context.getColor(R.color.summary_ink))
        setPadding(dp(context, 16), dp(context, 13), dp(context, 16), dp(context, 13))
        setBackgroundResource(R.drawable.bg_summary_card)
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        elevation = dp(context, 8).toFloat()
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Action status"
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val hideTask = Runnable { hide() }

    init {
        host.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            val margin = dp(context, 16)
            leftMargin = margin
            rightMargin = margin
            bottomMargin = margin
        })
        label.translationY = dp(context, 12).toFloat()
    }

    fun show(message: String) {
        val normalized = message.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || !messages.add(normalized)) return
        if (visibleMessages.size < MAX_VISIBLE_MESSAGES) visibleMessages += normalized
        else overflowCount++
        label.text = buildString {
            append(visibleMessages.joinToString("  ·  "))
            if (overflowCount > 0) append("  ·  +$overflowCount more actions")
        }
        label.contentDescription = label.text
        label.visibility = View.VISIBLE
        label.animate().cancel()
        label.alpha = 1f
        label.translationY = dp(label.context, 0).toFloat()
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, durationMillis)
    }

    fun dispose() {
        handler.removeCallbacks(hideTask)
        label.animate().cancel()
        host.removeView(label)
        reset()
    }

    private fun hide() {
        label.animate().cancel()
        label.visibility = View.GONE
        reset()
    }

    private fun reset() {
        messages.clear()
        visibleMessages.clear()
        overflowCount = 0
        label.text = ""
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    companion object { private const val MAX_VISIBLE_MESSAGES = 3 }
}
