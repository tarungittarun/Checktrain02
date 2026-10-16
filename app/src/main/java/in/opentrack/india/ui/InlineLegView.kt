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

/** A single vertical distance ruler for every source. Source reliability belongs in the hero,
 * not in a changing route shape or colour. A stop report pins the icon at the first halt;
 * only a qualified measured distance may move it between the two halts. */
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
    /** Scheduled kilometres between bounding halts, never inferred from elapsed time. */
    var distanceKm: Int = 0
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var showLabels: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    private var pulseStartedAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carriage = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = LegGeometry.heightDp(distanceKm)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(h.toFloat()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leg = projection ?: return
        val x = dp(36f)
        val y0 = dp(23f)
        val y1 = height - dp(23f)
        val fraction = leg.fraction.coerceIn(0f, 1f)
        val y = y0 + (y1 - y0) * fraction
        val markerColor = context.getColor(R.color.live)
        paint.strokeWidth = dp(3f)
        paint.color = context.getColor(R.color.stroke)
        canvas.drawLine(x, y0, x, y1, paint)
        // The coloured portion requires a source-provided distance; at a reported
        // halt it is zero, even after twenty minutes without another report.
        paint.color = markerColor
        canvas.drawLine(x, y0, x, y, paint)
        paint.color = context.getColor(R.color.text2)
        paint.textSize = dp(11f)
        var lastLabelY = Float.NEGATIVE_INFINITY
        for (point in leg.points) {
            val tickY = y0 + (y1 - y0) * point.fraction.coerceIn(0f, 1f)
            canvas.drawCircle(x, tickY, dp(3f), paint)
            if (showLabels && tickY - lastLabelY >= dp(22f) && tickY > y0 + dp(19f)) {
                // These smaller, inset map labels are NOT arrival reports or timed halt rows.
                canvas.drawText("map · ${point.code} · ${point.mapKm} km",
                    x + dp(26f), tickY + dp(4f), paint)
                lastLabelY = tickY
            }
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

/** The leg is distance-scaled but capped to keep the next stop in view.
 * Expanding map points does not stretch the ruler or shift the train icon. */
object LegGeometry {
    fun heightDp(distanceKm: Int): Int =
        (88 + distanceKm.coerceAtLeast(0) * 4).coerceIn(104, 210)
}
