// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertTrue

/** Queue a normal swipe without waiting for rendering between every input event. UiDevice's
 * synchronous injection can leave DOWN held beyond 400 ms on an emulated video surface,
 * turning the intended swipe into a real long press. Held-gesture tests stay synchronous.
 */
fun videoSwipe(instrumentation: Instrumentation, startX: Int, startY: Int, endX: Int, endY: Int, steps: Int = 8) {
    val down = SystemClock.uptimeMillis()
    fun send(action: Int, fraction: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, startX + (endX - startX) * fraction, startY + (endY - startY) * fraction, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, false)) }
        finally { event.recycle() }
    }
    val count = steps.coerceIn(4, 12)
    send(MotionEvent.ACTION_DOWN, 0f)
    for (i in 1..count) { SystemClock.sleep(12); send(MotionEvent.ACTION_MOVE, i.toFloat() / count) }
    send(MotionEvent.ACTION_UP, 1f)
    instrumentation.waitForIdleSync()
}
