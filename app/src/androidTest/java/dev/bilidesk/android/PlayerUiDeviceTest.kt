// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.ui.PlayerControlView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlayerUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var done = false; instrumentation.runOnMainSync { done = condition() }
            if (done) return true
            SystemClock.sleep(200)
        }
        return false
    }
    @Test fun touchSeekingAndBothFullscreenExitPathsWork() {
        val context = instrumentation.targetContext
        assumeTrue(context.resources.configuration.smallestScreenWidthDp < 600)
        val bvid = InstrumentationRegistry.getArguments().getString("bvid") ?: "BV156xx6wEff"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/$bvid"), context, MainActivity::class.java)
        Configurator.getInstance().waitForIdleTimeout = 300
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            try {
                assertTrue("Video not playing", waitFor(60000) { Graph.player.exo.isPlaying })
                fun showNativeControls() {
                    scenario.onActivity {
                        it.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller).apply {
                            // UiAutomator may wait for window idle longer than the production hide timer.
                            // Disable only that timer while exercising real native touch controls.
                            showTimeoutMs = 0
                            show()
                        }
                    }
                }
                val enterDescription = context.getString(androidx.media3.ui.R.string.exo_controls_fullscreen_enter_description)
                showNativeControls()
                val enter = device.wait(Until.findObject(By.desc(enterDescription)), 10000)
                assertNotNull("Native fullscreen button missing", enter)
                enter.click()
                assertTrue("Did not enter landscape fullscreen", waitFor(10000) { device.displayWidth > device.displayHeight })
                assertTrue("Player layout did not complete fullscreen resize", waitFor(10000) {
                    val control = scenarioActivityControl(scenario)
                    control != null && control.isLaidOut && control.width > control.height
                })
                showNativeControls()
                val bar = device.wait(Until.findObject(By.res(context.packageName, "exo_progress")), 10000)
                assertNotNull("Native seek bar missing", bar)
                var duration = 0L
                scenario.onActivity { duration = Graph.player.exo.duration; Graph.player.seek(1000) }
                assertTrue("Duration unavailable", duration > 0)
                val bounds = bar.visibleBounds
                val startX = bounds.left + (bounds.width() * .1f).toInt()
                val targetX = bounds.left + (bounds.width() * .6f).toInt()
                device.swipe(startX, bounds.centerY(), targetX, bounds.centerY(), 35)
                val target = (duration * .6).toLong()
                assertTrue("Touch dragging did not seek near target", waitFor(20000) { Graph.player.exo.isPlaying && abs(Graph.player.exo.currentPosition - target) < maxOf(5000L, duration / 12) })
                val exit = device.wait(Until.findObject(By.desc("退出全屏")), 10000)
                assertNotNull("Persistent fullscreen exit button missing", exit)
                exit.click()
                assertTrue("Fullscreen exit button did not restore portrait", waitFor(10000) { device.displayWidth < device.displayHeight })
                showNativeControls()
                val again = device.wait(Until.findObject(By.desc(enterDescription)), 10000)
                assertNotNull(again); again.click()
                assertTrue(waitFor(10000) { device.displayWidth > device.displayHeight })
                device.pressBack()
                assertTrue("System Back failed to exit fullscreen", waitFor(10000) { device.displayWidth < device.displayHeight })
                assertTrue("Playback did not survive fullscreen transitions", waitFor(10000) { Graph.player.exo.isPlaying })
            } catch (failure: Throwable) {
                val directory = context.getExternalFilesDir(null)!!
                device.takeScreenshot(File(directory, "player-ui-failure.png"))
                device.dumpWindowHierarchy(File(directory, "player-ui-failure.xml"))
                throw failure
            } finally { scenario.onActivity { Graph.player.stop() } }
        }
    }
    private fun scenarioActivityControl(scenario: ActivityScenario<MainActivity>): PlayerControlView? {
        var control: PlayerControlView? = null
        scenario.onActivity { control = it.findViewById(androidx.media3.ui.R.id.exo_controller) }
        return control
    }
}
