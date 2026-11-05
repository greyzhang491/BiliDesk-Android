// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import kotlin.math.abs
import kotlin.math.roundToInt

data class PressEffect(val speed: Float? = null, val seekTo: Long? = null)
enum class PressDrag { SPEED, SEEK, LOCK }

/** Gesture decisions are independent of Android touch delivery and never persist temporary speed. */
class PressPlaybackGesture {
    var active = false; private set
    var lockedSpeed: Float? = null; private set
    var targetPosition: Long? = null; private set
    var drag = PressDrag.SPEED; private set
    private var baseline: Float? = null
    private var position = 0L
    private var duration = 0L
    private var requested = 2f
    private var allowLock = false
    private var startedLocked = false
    var lockChoice: Float? = null; private set
    private var lockOrigin = 0f
    private var initialChoice = 0
    val choosingLock get() = active && drag == PressDrag.LOCK

    fun begin(current: Float, rate: Float, enabled: Boolean, lockAllowed: Boolean, positionMs: Long, durationMs: Long): PressEffect {
        if (active) return PressEffect()
        if (baseline == null) baseline = current
        requested = rate.takeIf { it.isFinite() && it in .5f..4f } ?: 2f
        allowLock = lockAllowed && enabled
        startedLocked = lockedSpeed != null
        position = positionMs.coerceAtLeast(0); duration = durationMs.coerceAtLeast(0)
        drag = PressDrag.SPEED; targetPosition = null; active = true
        lockChoice = null
        return PressEffect(if (enabled) lockedSpeed ?: requested else null)
    }

    fun move(dx: Float, dy: Float, width: Float, density: Float): PressEffect {
        if (!active) return PressEffect()
        if (drag == PressDrag.SPEED) {
            if (abs(dx) > 24 * density && abs(dx) > abs(dy) * 1.2f && duration > 0) drag = PressDrag.SEEK
            else if (allowLock && dy > 56 * density && dy > abs(dx) * 1.2f) {
                drag = PressDrag.LOCK
                lockOrigin = dy
                initialChoice = if (startedLocked) -1 else LOCK_RATES.indices.minBy { abs(LOCK_RATES[it] - requested) }
                lockChoice = LOCK_RATES.getOrNull(initialChoice)
                return PressEffect(lockChoice ?: baseline)
            }
        }
        if (drag == PressDrag.LOCK) {
            val index = (initialChoice + ((dy - lockOrigin) / (40 * density)).roundToInt()).coerceIn(-1, LOCK_RATES.lastIndex)
            lockChoice = LOCK_RATES.getOrNull(index)
            return PressEffect(lockChoice ?: baseline)
        }
        if (drag == PressDrag.SEEK) {
            // A full-width drag spans at most two minutes, even for an hour-long video.
            targetPosition = (position + (dx / width.coerceAtLeast(1f) * minOf(duration, 120000L)).toLong()).coerceIn(0, duration)
            return PressEffect(lockedSpeed ?: baseline)
        }
        return PressEffect()
    }

    fun end(cancelled: Boolean): PressEffect {
        if (!active) return PressEffect()
        if (cancelled) return reset()
        if (drag == PressDrag.LOCK) lockedSpeed = lockChoice
        val seek = targetPosition
        val speed = lockedSpeed ?: baseline
        active = false; targetPosition = null; drag = PressDrag.SPEED
        if (lockedSpeed == null) baseline = null
        return PressEffect(speed, seek)
    }

    fun reset(): PressEffect {
        val result = PressEffect(baseline)
        baseline = null; active = false; lockedSpeed = null; lockChoice = null; targetPosition = null; drag = PressDrag.SPEED
        return result
    }
    companion object { val LOCK_RATES = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f) }
}

enum class VerticalAdjustment { BRIGHTNESS, VOLUME }
object VerticalGesturePolicy {
    fun target(startX: Float, width: Float) = if (startX < width / 2) VerticalAdjustment.BRIGHTNESS else VerticalAdjustment.VOLUME
    fun value(start: Float, dy: Float, height: Float) = (start - dy / (height * .75f).coerceAtLeast(1f)).coerceIn(0f, 1f)
}
