// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ImmersiveGesturesDeviceTest {
    private var fixture: InteractionFixture? = null
    @After fun releaseFixture() { fixture?.close(); fixture = null }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 10000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var result = false; instrumentation.runOnMainSync { result = condition() }
            if (result) return true
            SystemClock.sleep(100)
        }
        return false
    }
    private fun pointer(down: Long, action: Int, x: Float, y: Float) = instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0))
    @Test fun fullscreenHidesBarsAndControlsAndSupportsBrightnessVolumeAndHeldSeek() {
        Configurator.getInstance().waitForIdleTimeout = 100
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val originalSettings = Graph.store.settings.value
        fixture = if (InstrumentationRegistry.getArguments().getString("playback_fixture") == "true") InteractionFixture(instrumentation) else null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/BV156xx6wEff/"), context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var native: PlayerView? = null
            try {
                assertTrue("Public video must decode", waitFor(70000) { Graph.player.exo.isPlaying && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                scenario.onActivity { native = it.window.decorView.findViewWithTag("bilidesk_player"); native!!.controllerShowTimeoutMs = 0; native!!.showController(); Graph.player.speed(1.25f); Graph.store.update(Graph.store.settings.value.copy(holdSpeed = 3f, holdSpeedEnabled = true)) }
                device.wait(Until.findObject(By.desc(context.getString(androidx.media3.ui.R.string.exo_controls_fullscreen_enter_description))), 10000)!!.click()
                assertTrue(waitFor { native!!.width > native!!.height })
                device.wait(Until.findObject(By.text("Got it")), 2000)?.click()
                scenario.onActivity { native!!.controllerShowTimeoutMs = 4500; native!!.showController() }
                assertTrue("Production timeout must hide native controls", waitFor(8000) { !native!!.isControllerFullyVisible })
                assertTrue(device.wait(Until.gone(By.desc("退出全屏")), 5000))
                scenario.onActivity {
                    val insets = ViewCompat.getRootWindowInsets(it.window.decorView)!!
                    assertFalse("System bars stayed visible", insets.isVisible(WindowInsetsCompat.Type.systemBars()))
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "immersive-clean.png"))
                var left = 0; var top = 0; var w = 0; var h = 0
                scenario.onActivity { val xy = IntArray(2); native!!.getLocationOnScreen(xy); left = xy[0]; top = xy[1]; w = native!!.width; h = native!!.height; it.window.attributes = it.window.attributes.apply { screenBrightness = .5f } }
                videoSwipe(instrumentation, left + w / 4, top + h * 2 / 3, left + w / 4, top + h / 3)
                var bright = 0f; scenario.onActivity { bright = it.window.attributes.screenBrightness }
                assertTrue("Left vertical swipe did not raise window brightness", bright > .75f)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
                val beforeVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                videoSwipe(instrumentation, left + w * 3 / 4, top + h * 2 / 3, left + w * 3 / 4, top + h / 3)
                assertTrue("Right vertical swipe did not raise media volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > beforeVolume)
                scenario.onActivity { Graph.player.seek(10000); native!!.hideController() }
                assertTrue(waitFor { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 9500..15000 })
                val x = left + w * .4f; val y = top + h * .45f
                val down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y)
                try {
                    assertTrue("Long press did not accelerate", waitFor(2500) { Graph.player.exo.playbackParameters.speed == 3f })
                    pointer(down, MotionEvent.ACTION_MOVE, x + w * .25f, y)
                    SystemClock.sleep(200)
                } finally { pointer(down, MotionEvent.ACTION_UP, x + w * .25f, y) }
                assertTrue("Held horizontal drag did not seek", waitFor(25000) { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 37000..52000 })
                scenario.onActivity { assertEquals(1.25f, Graph.player.exo.playbackParameters.speed, .001f) }
                assertEquals(1.25f, Graph.store.settings.value.playbackSpeed, .001f)
                scenario.onActivity { native!!.showController(); native!!.controllerShowTimeoutMs = 0 }
                device.wait(Until.findObject(By.desc("退出全屏")), 8000)!!.click()
                assertTrue("Details disappeared after fullscreen exit", device.wait(Until.hasObject(By.desc("视频详情区域")), 10000))
            } catch (failure: Throwable) {
                device.takeScreenshot(File(context.getExternalFilesDir(null), "immersive-failure.png")); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "immersive-failure.xml")); throw failure
            } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0); scenario.onActivity { Graph.player.stop(); Graph.store.update(originalSettings) } }
        }
    }
}
