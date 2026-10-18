package `in`.opentrack.india.ui

/** Refresh is an overlay, not a row in the scrolling source card. Coordinates are
 * relative to the content viewport (excluding status/navigation bars). The button
 * may be moved only within the lower half; fractions survive orientation changes. */
object RefreshPlacement {
    data class Point(val x: Float, val y: Float)

    fun at(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int, inset: Int,
           centerX: Float = 0.875f, centerY: Float = 0.8f): Point {
        val left = inset.toFloat()
        val right = (width - buttonWidth - inset).coerceAtLeast(inset).toFloat()
        val top = (height / 2f).coerceAtMost((height - buttonHeight).coerceAtLeast(0).toFloat())
        val bottom = (height - buttonHeight - inset).toFloat().coerceAtLeast(top)
        return Point((width * centerX - buttonWidth / 2f).coerceIn(left, right),
            (height * centerY - buttonHeight / 2f).coerceIn(top, bottom))
    }

    fun drag(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int, inset: Int,
             x: Float, y: Float): Point = at(width, height, buttonWidth, buttonHeight, inset,
        (x + buttonWidth / 2f) / width.coerceAtLeast(1),
        (y + buttonHeight / 2f) / height.coerceAtLeast(1))
}
