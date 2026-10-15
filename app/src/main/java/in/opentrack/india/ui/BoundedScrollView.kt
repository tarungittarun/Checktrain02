package `in`.opentrack.india.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** The journey summary wraps its content instead of reserving half the screen. On a
 * small/large-text device cap it so the route still has a usable viewport. */
class BoundedScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(heightMeasureSpec)
        val screen = resources.displayMetrics.heightPixels
        val cap = (screen * 0.53f).toInt()
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
            minOf(available.takeIf { it > 0 } ?: cap, cap), View.MeasureSpec.AT_MOST))
    }
}
