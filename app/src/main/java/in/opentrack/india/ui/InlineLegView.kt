package `in`.opentrack.india.ui

import android.content.Context
import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.DashPathEffect
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

    // Age lives in its own TextView beside the source caption, never painted on
    // top of a static station name. The property is retained for older callers.
    var ageSeconds: Long? = null
        set(value) { field = value; invalidate() }
    var delayed: Boolean = false
        set(value) { field = value; invalidate() }
    var showLabels: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    private var pulseStartedAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carriage = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = projection?.points?.size ?: 0
        val h = if (showLabels) (110 + count * 24).coerceIn(130, 350) else 52
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(h.toFloat()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leg = projection ?: return
        // The resting view is a compact horizontal distance ruler, so the next
        // scheduled stop is visible without scrolling past a tall empty leg. On
        // disclosure it becomes a vertical, labelled map. Both use the SAME
        // measured fraction; expanding cannot move the train.
        val vertical = showLabels
        val x0 = if (vertical) dp(36f) else dp(80f)
        val x1 = if (vertical) x0 else width - dp(22f)
        val y0 = if (vertical) dp(22f) else dp(26f)
        val y1 = if (vertical) height - dp(20f) else y0
        paint.strokeWidth = dp(3f)
        paint.color = context.getColor(if (delayed) R.color.late else R.color.stroke)
        // Red dashes mark DELAY across this scheduled leg; they are not
        // the blue solid portion, which requires a qualified km measurement.
        paint.pathEffect = if (delayed) DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f) else null
        canvas.drawLine(x0, y0, x1, y1, paint)
        paint.pathEffect = null
        val markerColor = context.getColor(if (delayed) R.color.late else if (leg.markerKind in listOf(
                RouteBetween.MarkerKind.PROJECTED, RouteBetween.MarkerKind.REPORTED_HALT))
            R.color.live else R.color.warn)
        val fraction = leg.fraction.coerceIn(0f, 1f)
        val x = x0 + (x1 - x0) * fraction
        val y = y0 + (y1 - y0) * fraction
        paint.color = markerColor
        canvas.drawLine(x0, y0, x, y, paint)
        paint.color = context.getColor(R.color.text2)
        paint.textSize = dp(11f)
        var lastLabelY = Float.NEGATIVE_INFINITY
        for (point in leg.points) {
            val tickX = x0 + (x1 - x0) * point.fraction
            val tickY = y0 + (y1 - y0) * point.fraction
            if (vertical) {
                canvas.drawCircle(tickX, tickY, dp(3f), paint)
                if (tickY - lastLabelY >= dp(20f) && tickY > y0 + dp(22f)) {
                    canvas.drawText("${point.code} · ${point.mapKm} km (map)",
                        tickX + dp(27f), tickY + dp(4f), paint)
                    lastLabelY = tickY
                }
            } else canvas.drawLine(tickX, tickY - dp(5f), tickX, tickY + dp(5f), paint)
        }
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
    }
}
