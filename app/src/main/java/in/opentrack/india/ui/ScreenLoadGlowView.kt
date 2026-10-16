package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R

/** Decorative sweep within the LOCAL screen-load clock; not a train-motion indicator. */
class ScreenLoadGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.live) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        if (width <= 0f) return
        paint.alpha = 64
        canvas.drawRoundRect(0f, height / 2f - 1f, width, height / 2f + 1f, 1f, 1f, paint)
        val phase = (SystemClock.uptimeMillis() % 1800L) / 1800f
        val head = width * phase
        paint.alpha = 210
        canvas.drawRoundRect((head - width * .22f).coerceAtLeast(0f), 0f,
            head.coerceAtLeast(1f), height.toFloat(), height / 2f, height / 2f, paint)
        if (isAttachedToWindow && isShown) postInvalidateDelayed(50L)
    }
}
