package `in`.opentrack.india.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** The top source card must remain scrollable without stealing the route viewport.
 * A large font makes three halt rows taller, so a fixed percentage of the screen
 * cannot promise that those rows stay visible. This budget reserves their space. */
internal object RouteViewportBudget {
    fun sourceCapPx(availablePx: Int, screenPx: Int, density: Float, fontScale: Float): Int {
        val available = availablePx.takeIf { it > 0 } ?: screenPx
        // Three 80dp main halts grow with text; reserve their enlarged body plus
        // the route heading/archives. The summary is still at least 210dp and
        // scrolls independently when expanded or when the source has a long note.
        val reserve = (360f * density * fontScale.coerceIn(1f, 1.4f)).toInt()
        val minimumSummary = (210f * density).toInt()
        return minOf(available, (screenPx * .53f).toInt(),
            (available - reserve).coerceAtLeast(minimumSummary)).coerceAtLeast(0)
    }
}

class BoundedScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = RouteViewportBudget.sourceCapPx(
            View.MeasureSpec.getSize(heightMeasureSpec), resources.displayMetrics.heightPixels,
            resources.displayMetrics.density, resources.configuration.fontScale,
        )
        super.onMeasure(widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
