package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import `in`.opentrack.india.R

/** A direction cue, not a font glyph: ↘ arrives, ↖ departs. The illuminated tip is
 * deliberately at the destination of the arrow, never at the tail. Static times
 * use the quieter skin; neither direction nor meaning depends on colour alone. */
class DirectionArrowDrawable(private val context: Context,
                             val arriving: Boolean, val reported: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val x0 = b.left + dp(if (arriving) 3f else 18f)
        val y0 = b.top + dp(if (arriving) 3f else 17f)
        val x1 = b.left + dp(if (arriving) 18f else 3f)
        val y1 = b.top + dp(if (arriving) 17f else 3f)
        val blue = context.getColor(R.color.live)
        val tail = if (reported) Color.argb(opacity * 120 / 255, 52, 127, 177)
                   else Color.argb(opacity * 100 / 255, 89, 129, 158)
        val tip = if (reported) blue else Color.rgb(111, 185, 226)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tip; alpha = opacity *
            (if (reported) 44 else 26) / 255 }
        canvas.drawCircle(x1, y1, dp(6f), glow)
        glow.alpha = opacity * (if (reported) 90 else 53) / 255
        canvas.drawCircle(x1, y1, dp(3.5f), glow)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.shader = LinearGradient(x0, y0, x1, y1, tail, tip, Shader.TileMode.CLAMP)
        canvas.drawLine(x0, y0, x1, y1, paint)
        val sign = if (arriving) 1f else -1f
        canvas.drawLine(x1 - sign * dp(7f), y1, x1, y1, paint)
        canvas.drawLine(x1, y1 - sign * dp(7f), x1, y1, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = tip
        paint.alpha = opacity
        canvas.drawCircle(x1, y1, dp(1.6f), paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun attach(text: android.widget.TextView, arriving: Boolean, reported: Boolean) {
            val d = text.resources.displayMetrics.density
            val arrow = DirectionArrowDrawable(text.context, arriving, reported)
            arrow.setBounds(0, 0, (22 * d).toInt(), (20 * d).toInt())
            text.setCompoundDrawables(arrow, null, null, null)
            text.compoundDrawablePadding = (3 * d).toInt()
        }
    }
}
