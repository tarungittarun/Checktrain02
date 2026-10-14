package `in`.opentrack.india.ui

import android.content.Context
import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.RouteBetween

/** The same projected leg as the top card, now on the table's rail between its two halt rows.
 * Intermediate ticks are placed by their STATIC MAP distances; the train's blue glyph is
 * elapsed-time interpolation of NTES, never a phone/GPS observation or a station crossing. */
class InlineLegView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    var projection: RouteBetween.LegProjection? = null
        set(value) {
            if (field == value) return
            field = value
            pulseStartedAt = if (value != null) SystemClock.uptimeMillis() else 0L
            requestLayout()
            invalidate()
        }

    private var pulseStartedAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carriage = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = projection?.points?.size?.coerceAtMost(8) ?: 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            dp((110 + count * 28).coerceAtMost(330).toFloat()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leg = projection ?: return
        val x = dp(121f) // same horizontal rail axis as the station dots in item_stop
        val top = dp(14f)
        val end = height - dp(14f)
        val span = end - top
        paint.strokeWidth = dp(3f)
        paint.color = context.getColor(R.color.stroke)
        canvas.drawLine(x, top, x, end, paint)
        paint.color = context.getColor(R.color.live)
        canvas.drawLine(x, top, x, top + span * leg.fraction, paint)
        paint.color = context.getColor(R.color.text2)
        paint.textSize = dp(11f)
        val labels = if (leg.points.size <= 8) leg.points else emptyList()
        for (point in labels) {
            val y = top + span * point.fraction
            canvas.drawCircle(x, y, dp(3.5f), paint)
            canvas.drawText("${point.code} · map ${point.mapKm} km", x + dp(18f), y + dp(4f), paint)
        }
        val y = top + span * leg.fraction
        // A soft stationary halo says "this estimate is on screen"; the marker does NOT
        // travel with wall-clock time. Only a new NTES answer may change its fraction.
        val elapsed = SystemClock.uptimeMillis() - pulseStartedAt
        if (isShown && pulseStartedAt > 0L && elapsed < 7_200L &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            val phase = (elapsed % 2400L) / 2400f
            paint.color = context.getColor(R.color.accent)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.alpha = ((1f - phase) * 80).toInt()
            canvas.drawCircle(x, y, dp(16f + phase * 10f), paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            postInvalidateDelayed(120L)
        }
        carriage.set(x - dp(13f), y - dp(11f), x + dp(13f), y + dp(10f))
        paint.color = context.getColor(R.color.accent)
        canvas.drawRoundRect(carriage, dp(5f), dp(5f), paint)
        paint.color = context.getColor(R.color.ink_on_accent)
        canvas.drawRoundRect(x - dp(9f), y - dp(7f), x + dp(9f), y + dp(1f),
            dp(2f), dp(2f), paint)
        canvas.drawCircle(x - dp(7f), y + dp(11f), dp(2.5f), paint)
        canvas.drawCircle(x + dp(7f), y + dp(11f), dp(2.5f), paint)
    }
}
