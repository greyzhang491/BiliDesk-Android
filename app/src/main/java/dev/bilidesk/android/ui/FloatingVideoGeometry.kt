// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

data class FloatingVideoRect(val left: Float, val top: Float, val width: Float, val height: Float)
data class FloatingVideoLimits(val width: Float, val height: Float, val ratio: Float, val minWidth: Float, val maxWidth: Float, val margin: Float)
enum class FloatingVideoCorner(val left: Boolean, val top: Boolean, val label: String) {
    TOP_LEFT(true, true, "左上角"), TOP_RIGHT(false, true, "右上角"), BOTTOM_LEFT(true, false, "左下角"), BOTTOM_RIGHT(false, false, "右下角")
}

object FloatingVideoGeometry {
    fun resize(start: FloatingVideoRect, corner: FloatingVideoCorner, dx: Float, dy: Float, limits: FloatingVideoLimits): FloatingVideoRect {
        val ratio = limits.ratio.coerceIn(.3f, 3f)
        val horizontal = if (corner.left) -dx else dx
        val vertical = if (corner.top) -dy else dy
        val change = (horizontal + vertical / ratio) / (1f + 1f / (ratio * ratio))
        val max = minOf(limits.maxWidth, limits.width - 2 * limits.margin, (limits.height - 2 * limits.margin) * ratio).coerceAtLeast(1f)
        val width = (start.width + change).coerceIn(limits.minWidth.coerceIn(1f, max), max)
        val height = width / ratio
        val left = if (corner.left) start.left + start.width - width else start.left
        val top = if (corner.top) start.top + start.height - height else start.top
        return FloatingVideoRect(left.coerceIn(limits.margin, (limits.width - width - limits.margin).coerceAtLeast(limits.margin)),
            top.coerceIn(limits.margin, (limits.height - height - limits.margin).coerceAtLeast(limits.margin)), width, height)
    }
}
