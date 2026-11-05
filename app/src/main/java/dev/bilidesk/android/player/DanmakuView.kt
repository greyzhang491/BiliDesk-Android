// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import dev.bilidesk.android.data.Danmaku
import dev.bilidesk.android.data.Settings
import kotlin.math.max

/** Time derives from the player's media clock, so pause, speed changes and seek stay synchronized. */
class DanmakuView(context: Context): View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
    private var list = emptyList<Danmaku>()
    private var settings = Settings()
    internal val appliedSettings get() = settings
    private var clock: (() -> Long)? = null
    private var running: (() -> Boolean)? = null
    private var dirty = true
    private data class Placement(val dm: Danmaku, val lane: Int, val textWidth: Float, val end: Long, val speed: Float, val fixed: Boolean, val reverse: Boolean)
    private var placed = emptyList<Placement>()
    private var rowHeight = 30f
    private var regionHeight = 0f
    private var firstBaseline = 0f
    fun update(items: List<Danmaku>, next: Settings, exo: ExoPlayer) {
        bind(items, next, { exo.currentPosition }, { exo.isPlaying })
    }
    fun bind(items: List<Danmaku>, next: Settings, position: () -> Long, playing: () -> Boolean) {
        if (items != list || settings != next) dirty = true
        list = items; settings = next
        clock = position; running = playing; invalidate()
    }
    fun release() { clock = null; running = null; list = emptyList(); placed = emptyList() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { dirty = true }
    private fun arrange() {
        dirty = false
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, settings.fontSize, resources.displayMetrics)
        rowHeight = paint.fontMetrics.let { it.descent - it.ascent } * 1.15f
        regionHeight = height * settings.area.coerceIn(.25f, 1f)
        val padding = 3f * resources.displayMetrics.density
        firstBaseline = padding - paint.fontMetrics.ascent
        val rows = max(1, ((regionHeight - padding * 2) / rowHeight).toInt()).coerceAtMost(64)
        // All types use the same area and collision budget. Fixed bottom comments used to
        // anchor to the whole view, and two extra top rows made small areas overflow.
        val lanes = arrayOfNulls<Placement>(rows)
        val blocked = settings.blacklist.split(Regex("[,，\n]")).map { it.trim() }.filter { it.isNotBlank() }
        val seen = mutableMapOf<String, Long>()
        val duration = (8000 / settings.dmSpeed.coerceAtLeast(.2f)).toLong()
        val result = ArrayList<Placement>()
        for (dm in list) {
            if (blocked.any { dm.text.contains(it, ignoreCase = true) }) continue
            if (settings.deduplicate && seen[dm.text]?.let { dm.timeMs - it < 8000 } == true) continue
            if (dm.mode == 4 && !settings.bottom || dm.mode == 5 && !settings.top || dm.mode !in 4..5 && !settings.scroll) continue
            val textWidth = paint.measureText(dm.text)
            val fixed = dm.mode == 4 || dm.mode == 5
            val reverse = dm.mode == 6
            val speed = (width + textWidth) / duration
            val end = dm.timeMs + if (fixed) 4000 else duration
            val order = if (dm.mode == 4) lanes.indices.reversed() else lanes.indices
            val lane = order.firstOrNull { i ->
                val prev = lanes[i]
                if (prev == null || prev.end <= dm.timeMs) true
                else if (fixed || prev.fixed) false
                else if (prev.reverse != reverse) false
                else {
                    val elapsed = dm.timeMs - prev.dm.timeMs
                    val gap = prev.speed * elapsed - prev.textWidth - 16
                    gap >= 0 && (speed <= prev.speed || gap / (speed - prev.speed) >= prev.end - dm.timeMs)
                }
            } ?: -1
            if (lane < 0) continue
            val p = Placement(dm, lane, textWidth, end, speed, fixed, reverse)
            lanes[lane] = p
            seen[dm.text] = dm.timeMs; result.add(p)
        }
        placed = result
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val position = clock ?: return
        if (!settings.danmaku || width <= 0 || height <= 0) return
        if (dirty) arrange()
        val time = position()
        // Set drawing properties on every frame, including a paused frame after a setting changes.
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, settings.fontSize, resources.displayMetrics)
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), regionHeight)
        val horizon = (8000 / settings.dmSpeed.coerceAtLeast(.2f)).toLong().coerceAtLeast(4000)
        var lo = 0; var hi = placed.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (placed[mid].dm.timeMs < time - horizon) lo = mid + 1 else hi = mid }
        var index = lo
        while (index < placed.size) {
            val p = placed[index++]; if (p.dm.timeMs > time) break
            if (time > p.end) continue
            val x = when { p.fixed -> (width - p.textWidth) / 2; p.reverse -> -p.textWidth + p.speed * (time - p.dm.timeMs); else -> width - p.speed * (time - p.dm.timeMs) }
            val y = firstBaseline + p.lane * rowHeight
            paint.color = if (settings.colored) (0xff000000.toInt() or p.dm.color) else Color.WHITE
            paint.alpha = (settings.opacity * 255).toInt().coerceIn(0, 255)
            paint.setShadowLayer(3f, 1f, 1f, Color.argb(paint.alpha, 0, 0, 0))
            canvas.drawText(p.dm.text, x, y, paint)
        }
        canvas.restoreToCount(saved)
        if (running?.invoke() == true && isAttachedToWindow) postInvalidateOnAnimation()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); invalidate() }
}
