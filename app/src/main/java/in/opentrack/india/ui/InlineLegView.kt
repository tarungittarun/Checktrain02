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
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    var showTrain: Boolean = true
        set(value) { field = value; invalidate() }
    /** Untimed map-only legs need a full touch target, not a tall empty train leg. */
    /** Distances rescaled from an older corridor, never a current train timetable. */
    var historicalMap: Boolean = false
        set(value) { field = value; invalidate() }
    var compactStatic: Boolean = false
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            dp((if (showLabels && projection?.points?.isNotEmpty() == true)
                LegGeometry.expandedHeightDp(projection!!.points, distanceKm)
                else if (compactStatic) LegGeometry.compactMappedHeightDp(distanceKm)
                else LegGeometry.heightDp(distanceKm, screenDp)).toFloat()).roundToInt())
    }

    // An unattached renderer can be skipped by Robolectric. Exercise the same
    // painter directly in the bitmap test, without changing production draw logic.
    internal fun paintForTest(canvas: Canvas) = onDraw(canvas)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val leg = projection ?: return
        val x = dp(RouteRailGeometry.X_DP)
        val y0 = dp(24f)
        val y1 = height - dp(24f)
        val positions = if (showLabels) LegGeometry.pointPositionsDp(leg.points, distanceKm)
            .map(::dp) else emptyList()
        val breaks = listOf(0f) + leg.points.map { it.fraction.coerceIn(0f, 1f) } + listOf(1f)
        val heights = listOf(y0) + positions + listOf(y1)
        val fraction = leg.fraction.coerceIn(0f, 1f)
        val segment = (0 until breaks.lastIndex).indexOfFirst { fraction <= breaks[it + 1] }
            .coerceAtLeast(0)
        val proportion = ((fraction - breaks[segment]) /
            (breaks[segment + 1] - breaks[segment]).coerceAtLeast(.001f)).coerceIn(0f, 1f)
        val y = if (showLabels && positions.isNotEmpty())
            heights[segment] + (heights[segment + 1] - heights[segment]) * proportion
            else y0 + (y1 - y0) * fraction
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
        // Closed really means closed: no anonymous third/fourth dots left on the
        // rail after hiding the named intermediate stations. The locomotive (if
        // a source actually places it on this leg) remains independent.
        if (showLabels) for ((index, point) in leg.points.withIndex()) {
            val tickY = positions.getOrNull(index) ?:
                y0 + (y1 - y0) * point.fraction.coerceIn(0f, 1f)
            paint.color = context.getColor(R.color.text2)
            canvas.drawCircle(x, tickY, dp(4f), paint)
            val clock = point.passageMinute?.let {
                (if (point.approximate) "~" else "") +
                    `in`.opentrack.india.core.RailTime.formatRailMinute(it)
            }.orEmpty()
            paint.textSize = sp(12f)
            val clockWidth = paint.measureText(clock)
            paint.textAlign = Paint.Align.RIGHT
            if (clock.isNotEmpty()) canvas.drawText(clock, width - dp(12f), tickY + dp(4f), paint)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = sp(13f)
            val left = x + dp(26f)
            val right = width - dp(12f) -
                (if (clock.isEmpty()) 0f else clockWidth + dp(10f))
            val room = (right - left).coerceAtLeast(dp(40f))
            val label = android.text.TextUtils.ellipsize(
                RowText.of(point.name), android.text.TextPaint(paint), room,
                android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, left, tickY + dp(2f), paint)
            paint.color = context.getColor(R.color.text3)
            paint.textSize = sp(11f)
            canvas.drawText(if (point.mapKm >= 0)
                (if (historicalMap) "~${point.mapKm} km" else "${point.mapKm} km · route map")
                else "distance unavailable",
                left, tickY + dp(17f), paint)
        }
        if (!showTrain) return // Static stations are not evidence of the train's location.
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
        // Keep three named halts in the first phone viewport even on a 65-km
        // last-reported leg. Still grows with the *scheduled distance*, never time.
        val ceiling = (screenHeightDp * 0.135f).roundToInt().coerceIn(90, 112)
        return (72 + distanceKm.coerceAtLeast(0) * 0.55f).roundToInt().coerceIn(72, ceiling)
    }

    /** Enough for an accessible whole-rail tap without pushing mapped halts off-screen. */
    fun compactMappedHeightDp(distanceKm: Int): Int =
        (28 + distanceKm.coerceAtLeast(0) * .55f).roundToInt().coerceIn(30, 78)

    fun stopGapDp(distanceKm: Int, screenHeightDp: Float = 760f): Int {
        val ceiling = (screenHeightDp * 0.07f).roundToInt().coerceIn(22, 56)
        return (8 + distanceKm.coerceAtLeast(0) * 0.8f)
            .roundToInt().coerceIn(10, ceiling)
    }

    /** Every disclosed dot has a readable minimum gap; the remaining pixels are
     * distributed by the source map's kilometre fractions. No elapsed-time movement. */
    fun pointPositionsDp(points: List<RouteBetween.LegPoint>, distanceKm: Int): List<Float> {
        val ratios = (listOf(0f) + points.map { it.fraction.coerceIn(0f, 1f) } + listOf(1f))
        val bonus = distanceKm.coerceIn(0, 100) * 1.4f
        var y = 24f
        return points.mapIndexed { i, _ ->
            y += 36f + bonus * (ratios[i + 1] - ratios[i]).coerceAtLeast(0f)
            y
        }
    }

    fun expandedHeightDp(points: List<RouteBetween.LegPoint>, distanceKm: Int): Int {
        val positions = pointPositionsDp(points, distanceKm)
        val remaining = (1f - (points.lastOrNull()?.fraction ?: 0f)).coerceIn(0f, 1f)
        return ((positions.lastOrNull() ?: 24f) + 36f +
            distanceKm.coerceIn(0, 100) * 1.4f * remaining + 24f).roundToInt()
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
