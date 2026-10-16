package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.RouteBetween
import kotlin.math.roundToInt

/** Source-neutral, vertically bounded route ruler. Its train is at the last reported
 * event (or at a qualified source distance); wall-clock time only changes its age badge. */
class InlineLegView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    var projection: RouteBetween.LegProjection? = null
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var ageSeconds: Long? = null
        set(value) { field = value; invalidate() }
    var distanceKm: Int = 0
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var showLabels: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            dp(LegGeometry.heightDp(distanceKm, screenDp).toFloat()).roundToInt())
    }

    // An unattached renderer can be skipped by Robolectric. Exercise the same
    // painter directly in the bitmap test, without changing production draw logic.
    internal fun paintForTest(canvas: Canvas) = onDraw(canvas)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leg = projection ?: return
        val x = dp(36f)
        val y0 = dp(24f)
        val y1 = height - dp(24f)
        val y = y0 + (y1 - y0) * leg.fraction.coerceIn(0f, 1f)
        val blue = context.getColor(R.color.live)
        val ink = context.getColor(R.color.bg)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = context.getColor(R.color.stroke)
        // Continue the stop rows' rail right to both edges of this View. A
        // 24dp blank at each end used to leave a broken-looking route line.
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        if (leg.fraction > 0f) {
            paint.color = blue
            canvas.drawLine(x, y0, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = context.getColor(R.color.text2)
        paint.textSize = sp(11f)
        var lastLabelY = Float.NEGATIVE_INFINITY
        for (point in leg.points) {
            val tickY = y0 + (y1 - y0) * point.fraction.coerceIn(0f, 1f)
            canvas.drawCircle(x, tickY, dp(2.5f), paint)
            if (showLabels && tickY - lastLabelY >= dp(22f) &&
                kotlin.math.abs(tickY - y) > dp(25f) && tickY > y0 + dp(20f)) {
                canvas.drawText("map · ${point.code} · ${point.mapKm} km",
                    x + dp(26f), tickY + dp(4f), paint)
                lastLabelY = tickY
            }
        }
        // A quiet, fixed-size locomotive: no pulsing/moving halo that could imply
        // fresh telemetry. Badge and rail have the same shape for every source.
        paint.color = blue
        canvas.drawCircle(x, y, dp(18f), paint)
        paint.color = ink
        box.set(x - dp(9f), y - dp(10f), x + dp(9f), y + dp(7f))
        canvas.drawRoundRect(box, dp(4f), dp(4f), paint)
        paint.color = context.getColor(R.color.text1)
        canvas.drawRoundRect(x - dp(6f), y - dp(7f), x - dp(1f), y - dp(1f),
            dp(1f), dp(1f), paint)
        canvas.drawRoundRect(x + dp(1f), y - dp(7f), x + dp(6f), y - dp(1f),
            dp(1f), dp(1f), paint)
        canvas.drawCircle(x - dp(5f), y + dp(9f), dp(2f), paint)
        canvas.drawCircle(x + dp(5f), y + dp(9f), dp(2f), paint)

        val age = ageSeconds ?: return  // Unknown event time must never earn a freshness icon.
        if (age < 60L) {
            // Check means the SOURCE EVENT was less than a minute ago, not GPS or motion.
            val cx = x + dp(15f)
            val cy = y - dp(14f)
            paint.color = context.getColor(R.color.bg)
            canvas.drawCircle(cx, cy, dp(9f), paint)
            paint.color = context.getColor(R.color.accent)
            canvas.drawCircle(cx, cy, dp(7f), paint)
            paint.color = ink
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.8f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val check = android.graphics.Path().apply {
                moveTo(cx - dp(3f), cy)
                lineTo(cx - dp(0.5f), cy + dp(2.5f))
                lineTo(cx + dp(3.5f), cy - dp(2f))
            }
            canvas.drawPath(check, paint)
            paint.style = Paint.Style.FILL
        } else {
            val label = LegGeometry.ageLabel(age)
            paint.textSize = sp(11f)
            val left = x + dp(23f)
            val right = left + paint.measureText(label) + dp(14f)
            box.set(left, y - dp(11f), right, y + dp(11f))
            paint.color = context.getColor(R.color.surface2)
            canvas.drawRoundRect(box, dp(11f), dp(11f), paint)
            paint.color = context.getColor(R.color.text1)
            canvas.drawText(label, left + dp(7f), y + dp(4f), paint)
        }
    }
}

/** A short leg leaves room for three halt rows in the first route viewport.
 * Both spacing and age are functions of source/map facts, never of elapsed train travel. */
object LegGeometry {
    fun heightDp(distanceKm: Int, screenHeightDp: Float = 760f): Int {
        val ceiling = (screenHeightDp * 0.19f).roundToInt().coerceIn(100, 150)
        return (80 + distanceKm.coerceAtLeast(0) * 0.85f).roundToInt().coerceIn(80, ceiling)
    }

    fun stopGapDp(distanceKm: Int, screenHeightDp: Float = 760f): Int {
        val ceiling = (screenHeightDp * 0.035f).roundToInt().coerceIn(12, 27)
        return (6 + distanceKm.coerceAtLeast(0) * 0.45f)
            .roundToInt().coerceIn(6, ceiling)
    }

    fun ageLabel(ageSeconds: Long): String = when {
        ageSeconds < 60 -> "under 1 min" // drawn as a check, never as fresh GPS
        ageSeconds < 3600 -> "${ageSeconds / 60} min ago"
        ageSeconds < 86400 -> {
            val hours = ageSeconds / 3600
            val minutes = ageSeconds % 3600 / 60
            "$hours ${if (hours == 1L) "hour" else "hours"}" +
                (if (minutes == 0L) "" else " $minutes min") + " ago"
        }
        else -> "${ageSeconds / 86400} d ago"
    }
}
