package `in`.opentrack.india.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.widget.TextView
import `in`.opentrack.india.R

/** A short transport-failure cue on the TOP summary, not on the train icon.
 * Six low-contrast dips at three per second, then a persistent, readable reason.
 * No position/age changes and no endless or full-white flashing. */
class ConnectionIssueCue(private val card: View, private val alert: TextView,
                         private val evidence: View? = null) {
    companion object {
        const val PULSE_MILLIS = 333L
        const val PULSES = 6
        const val MIN_ALPHA = .72f
    }

    private var animation: AnimatorSet? = null
    var reason: String? = null
        private set

    fun show(newReason: String, source: String) {
        if (reason == "$source:$newReason") return
        stopMotion()
        reason = "$source:$newReason"
        alert.text = "$newReason · $source. Previous report unchanged; retry or change source."
        alert.visibility = View.VISIBLE
        evidence?.alpha = 0f // share the evidence slot, never cover readable text
        evidence?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        card.setBackgroundResource(R.drawable.bg_summary_connection)
        val pulse = ObjectAnimator.ofFloat(card, View.ALPHA, 1f, MIN_ALPHA, 1f).apply {
            duration = PULSE_MILLIS
            repeatCount = PULSES - 1
        }
        val drift = 5f * card.resources.displayMetrics.density
        val shake = ObjectAnimator.ofFloat(card, View.TRANSLATION_X,
            0f, -drift, drift, -drift / 2f, drift / 2f, 0f).apply {
            duration = 600L
        }
        animation = AnimatorSet().apply { playTogether(pulse, shake); start() }
    }

    /** Stop an active cue on navigation; keep the explanatory message. */
    fun pause() = stopMotion()

    fun clear() {
        stopMotion()
        reason = null
        alert.text = ""
        // The warning overlays the existing evidence slot, so an ordinary
        // summary has no empty reserved line above the report.
        alert.visibility = View.GONE
        evidence?.alpha = 1f
        evidence?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        card.setBackgroundResource(R.drawable.bg_summary_card)
    }

    private fun stopMotion() {
        animation?.cancel()
        animation = null
        card.alpha = 1f
        card.translationX = 0f
    }
}
