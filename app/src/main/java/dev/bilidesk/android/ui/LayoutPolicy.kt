// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

data class AppWindow(val width: Float = 0f, val height: Float = 0f) {
    val useRail get() = width >= 840f || width >= 600f && height < 600f
    val shortHeight get() = height < 520f
    val cardMinWidth get() = if (minOf(width, height) >= 600f) 220f else 155f
}

enum class PlayerLayoutMode { STACKED, SIDE_BY_SIDE, FULLSCREEN }
data class PlayerLayoutSpec(val mode: PlayerLayoutMode, val videoWidth: Float, val videoHeight: Float, val detailsWidth: Float, val detailsHeight: Float)

object PlayerLayoutPolicy {
    fun calculate(width: Float, height: Float, fullscreen: Boolean): PlayerLayoutSpec {
        val w = width.coerceAtLeast(0f); val h = height.coerceAtLeast(0f)
        if (fullscreen) return PlayerLayoutSpec(PlayerLayoutMode.FULLSCREEN, w, h, 0f, 0f)
        if (w >= 840f && w > h && h >= 300f) {
            val details = (w * .3f).coerceIn(300f, 400f)
            return PlayerLayoutSpec(PlayerLayoutMode.SIDE_BY_SIDE, w - details, h, details, h)
        }
        val videoHeight = minOf(w * 9f / 16f, h * .58f)
        return PlayerLayoutSpec(PlayerLayoutMode.STACKED, w, videoHeight, w, h - videoHeight)
    }
}

/** Tracks only a temporary hold; ordinary playback speed is never persisted as the hold speed. */
class HoldSpeedState {
    private var original: Float? = null
    val active get() = original != null
    fun begin(current: Float, requested: Float, enabled: Boolean): Float? {
        if (!enabled || active || !current.isFinite() || current <= 0f || !requested.isFinite() || requested !in .5f..4f) return null
        original = current
        return requested
    }
    fun end(): Float? = original.also { original = null }
}
