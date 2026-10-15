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

/** A proportional marker inside a table leg. A blue projected marker is NOT GPS; an amber
 * web-cache candidate is a separate, unverified source claim, also NOT GPS. Neither animates
 * travel with wall-clock time. Ticks are static map distances, never station pass events. */
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

    var ageSeconds: Long? = null
        set(value) { field = value; invalidate() }

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
        val markerColor = context.getColor(if (leg.markerKind in listOf(
                RouteBetween.MarkerKind.PROJECTED, RouteBetween.MarkerKind.REPORTED_HALT))
            R.color.live else R.color.warn)
        paint.color = markerColor
        canvas.drawLine(x, top, x, top + span * leg.fraction, paint)
        paint.color = context.getColor(R.color.text2)
        paint.textSize = dp(11f)
        // Every validated intermediate point gets its distance-proportional tick.
        // Dense legs still show ticks; suppress only colliding TEXT labels (the
        // expandable station table names all points). Never drop the whole map at 9+.
        var lastLabelY = Float.NEGATIVE_INFINITY
        for (point in leg.points) {
            val tickY = top + span * point.fraction
            canvas.drawCircle(x, tickY, dp(3.5f), paint)
            if (tickY - lastLabelY >= dp(19f)) {
                canvas.drawText("${point.code} · map ${point.mapKm} km",
                    x + dp(18f), tickY + dp(4f), paint)
                lastLabelY = tickY
            }
        }
        val y = top + span * leg.fraction
        // A soft stationary halo says "this source claim is on screen"; the marker
        // does NOT travel with wall-clock time. Only a newly qualified source event
        // or distance claim may change its fraction.
        val elapsed = SystemClock.uptimeMillis() - pulseStartedAt
        if (isShown && pulseStartedAt > 0L && elapsed < 7_200L &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())) {
            val phase = (elapsed % 2400L) / 2400f
            paint.color = markerColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.alpha = ((1f - phase) * 80).toInt()
            canvas.drawCircle(x, y, dp(16f + phase * 10f), paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            postInvalidateDelayed(120L)
        }
        // Draw a small locomotive, not an ambiguous dot/rectangle: raised roof,
        // two cab windows, lit nose and paired wheels. Marker stays at the source
        // reading; only the halo animates, never invented movement along the line.
        paint.color = markerColor
        canvas.drawRoundRect(x - dp(11f), y - dp(14f), x + dp(9f), y - dp(10f),
            dp(2f), dp(2f), paint)
        carriage.set(x - dp(15f), y - dp(11f), x + dp(15f), y + dp(9f))
        canvas.drawRoundRect(carriage, dp(5f), dp(5f), paint)
        paint.color = context.getColor(R.color.ink_on_accent)
        canvas.drawRoundRect(x - dp(10f), y - dp(7f), x - dp(2f), y + dp(1f),
            dp(1.5f), dp(1.5f), paint)
        canvas.drawRoundRect(x + dp(2f), y - dp(7f), x + dp(9f), y + dp(1f),
            dp(1.5f), dp(1.5f), paint)
        canvas.drawCircle(x + dp(13f), y + dp(4f), dp(1.7f), paint)
        paint.color = markerColor
        canvas.drawCircle(x - dp(8f), y + dp(11f), dp(3f), paint)
        canvas.drawCircle(x + dp(8f), y + dp(11f), dp(3f), paint)
        paint.color = context.getColor(R.color.ink_on_accent)
        canvas.drawCircle(x - dp(8f), y + dp(11f), dp(1.2f), paint)
        canvas.drawCircle(x + dp(8f), y + dp(11f), dp(1.2f), paint)
        ageSeconds?.takeIf { it >= 60 }?.let { seconds ->
            paint.color = markerColor
            paint.textSize = dp(11f)
            val label = if (seconds < 3600) "${seconds / 60}m old"
                else "${seconds / 3600}h ${(seconds % 3600) / 60}m old"
            // The marker can coincide with a named static station tick. Put its
            // age beneath that label, not on top of it or outside the leg view.
            canvas.drawText(label, x + dp(21f),
                (y + dp(19f)).coerceAtMost(height - dp(3f)), paint)
        }
    }
}
