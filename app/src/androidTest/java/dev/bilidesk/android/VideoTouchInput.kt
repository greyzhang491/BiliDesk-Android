// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertTrue

/** Have the system generate the complete trajectory. Repeated UiAutomation binder calls can
 * stall the first MOVE for over 600 ms on an emulator, turning an intended swipe into a
 * genuine long press. The separate held-gesture tests still use explicit down/hold/up input.
 */
fun videoSwipe(instrumentation: Instrumentation, startX: Int, startY: Int, endX: Int, endY: Int, steps: Int = 8) {
    val duration = (steps.coerceIn(4, 12) * 50).coerceIn(320, 400)
    val pipe = instrumentation.uiAutomation.executeShellCommand("input touchscreen swipe $startX $startY $endX $endY $duration")
    val output = ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
    assertTrue("System touch injection failed: $output", output.isBlank())
    instrumentation.waitForIdleSync()
}
