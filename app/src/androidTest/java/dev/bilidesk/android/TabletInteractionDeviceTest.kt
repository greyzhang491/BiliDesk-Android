// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TabletInteractionDeviceTest {
    private var fixture: InteractionFixture? = null
    @After fun releaseFixture() { fixture?.close(); fixture = null }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 12000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return true
            SystemClock.sleep(150)
        }
        return false
    }
    private fun click(description: String) {
        device.findObject(By.text("Got it"))?.click()
        val node = device.wait(Until.findObject(By.desc(description)), 12000)
        assertNotNull("Missing control: $description", node); node.click()
    }
    @Test fun tabletRotationFullscreenHoldSpeedAndLiveDanmakuSettings() {
        assumeTrue(instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600)
        fixture = if (InstrumentationRegistry.getArguments().getString("playback_fixture") == "true") InteractionFixture(instrumentation) else null
        Configurator.getInstance().waitForIdleTimeout = 200
        device.setOrientationLeft()
        val context = instrumentation.targetContext
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/BV156xx6wEff/"), context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var native: PlayerView? = null
            fun showControls() { scenario.onActivity { native!!.controllerShowTimeoutMs = 0; native!!.showController() } }
            fun assertLandscape() { assertTrue(waitFor { device.displayWidth > device.displayHeight }); scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, it.requestedOrientation) } }
            try {
                assertTrue("Video not playing", waitFor(70000) { Graph.player.exo.isPlaying })
                scenario.onActivity {
                    native = it.window.decorView.findViewWithTag("bilidesk_player")
                    Graph.player.speed(1.25f)
                    val s = Graph.store.settings.value
                    Graph.store.update(s.copy(holdSpeedEnabled = true, holdSpeed = 2f, danmaku = true, colored = true))
                }
                assertLandscape()
                val details = device.wait(Until.findObject(By.desc("视频详情区域")), 15000)
                assertNotNull("Tablet details pane missing", details)
                var videoRight = 0
                scenario.onActivity { val xy = IntArray(2); native!!.getLocationOnScreen(xy); videoRight = xy[0] + native!!.width }
                assertTrue("Details must be beside video", details.visibleBounds.left >= videoRight - 4)
                device.takeScreenshot(File(context.getExternalFilesDir(null), "tablet-landscape.png"))
                val original = native
                device.setOrientationNatural()
                assertTrue(waitFor { device.displayHeight > device.displayWidth })
                assertTrue(waitFor { native!!.width > native!!.height })
                scenario.onActivity { assertSame(original, it.window.decorView.findViewWithTag<View>("bilidesk_player")); assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, it.requestedOrientation) }
                device.setOrientationLeft(); assertLandscape()
                showControls()
                click(context.getString(androidx.media3.ui.R.string.exo_controls_fullscreen_enter_description))
                assertNotNull(device.wait(Until.findObject(By.desc("退出全屏")), 12000))
                assertTrue("Fullscreen did not fill window", waitFor {
                    var fills = false
                    scenario.onActivity { fills = native!!.width >= it.window.decorView.width - 4 && native!!.height >= it.window.decorView.height - 4 }
                    fills
                })
                assertLandscape()
                click("播放设置")
                val holdMenu = device.wait(Until.findObject(By.text("长按倍速")), 10000)
                assertNotNull(holdMenu); holdMenu.click()
                click("长按倍速 3×")
                assertTrue(waitFor { Graph.store.settings.value.holdSpeed == 3f })
                click("关闭播放器设置")
                scenario.onActivity { native!!.hideController() }
                SystemClock.sleep(400)
                val xy = IntArray(2); var x = 0f; var y = 0f
                scenario.onActivity { native!!.getLocationOnScreen(xy); x = xy[0] + native!!.width * .5f; y = xy[1] + native!!.height * .5f }
                fun holdAndRelease(endAction: Int) {
                    val down = SystemClock.uptimeMillis()
                    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                    try { assertTrue("Configured hold speed not applied", waitFor(4000) { Graph.player.exo.playbackParameters.speed == 3f }) }
                    finally { instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), endAction, x, y, 0)) }
                    assertTrue("Previous speed not restored", waitFor { Graph.player.exo.playbackParameters.speed == 1.25f })
                    assertEquals(1.25f, Graph.store.settings.value.playbackSpeed, .001f)
                }
                holdAndRelease(MotionEvent.ACTION_UP)
                SystemClock.sleep(400)
                holdAndRelease(MotionEvent.ACTION_CANCEL)
                showControls(); click("播放设置")
                device.wait(Until.findObject(By.text("长按倍速")), 8000).click()
                device.wait(Until.findObject(By.text("启用长按倍速")), 8000).click()
                assertTrue(waitFor { !Graph.store.settings.value.holdSpeedEnabled })
                click("关闭播放器设置")
                scenario.onActivity { native!!.hideController() }
                val down = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                try { SystemClock.sleep(900); assertTrue(waitFor { Graph.player.exo.playbackParameters.speed == 1.25f }) }
                finally { instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)) }
                showControls(); click("弹幕设置")
                val colored = device.wait(Until.findObject(By.text("彩色弹幕")), 10000)
                assertNotNull(colored); colored.click()
                assertTrue("Danmaku toggle did not apply live", waitFor { !Graph.store.settings.value.colored })
                val scroll = device.findObject(By.scrollable(true))
                assertNotNull(scroll)
                repeat(3) { if (device.findObject(By.desc("弹幕透明度")) == null) scroll.scroll(Direction.DOWN, .6f) }
                val opacity = device.wait(Until.findObject(By.desc("弹幕透明度")), 5000)
                assertNotNull(opacity)
                val bounds = opacity.visibleBounds
                device.click(bounds.left + bounds.width() / 4, bounds.centerY())
                assertTrue("Live opacity did not change", waitFor { Graph.store.settings.value.opacity < .7f })
                fun adjust(description: String, fraction: Float) {
                    repeat(5) { if (device.findObject(By.desc(description)) == null) device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .35f) }
                    val slider = device.wait(Until.findObject(By.desc(description)), 5000)
                    assertNotNull("Missing actual slider: $description", slider)
                    val rect = slider.visibleBounds
                    device.click(rect.left + (rect.width() * fraction).toInt(), rect.centerY())
                }
                adjust("弹幕显示区域", .15f)
                assertTrue(waitFor { Graph.store.settings.value.area < .5f })
                adjust("弹幕字号", .85f)
                assertTrue(waitFor { Graph.store.settings.value.fontSize > 24f })
                scenario.onActivity {
                    val renderer = native!!.overlayFrameLayout!!.findViewWithTag<dev.bilidesk.android.player.DanmakuView>("bilidesk_danmaku")
                    assertEquals("Player renderer did not receive the live controls", Graph.store.settings.value, renderer.appliedSettings)
                }
                assertTrue("Settings interrupted playback", waitFor { Graph.player.exo.isPlaying })
                assertLandscape()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "tablet-danmaku-settings.png"))
                click("关闭播放器设置")
                click("退出全屏"); assertLandscape()
                assertNotNull(device.wait(Until.findObject(By.desc("视频详情区域")), 10000))
                showControls(); click(context.getString(androidx.media3.ui.R.string.exo_controls_fullscreen_enter_description))
                click("播放设置"); device.pressBack()
                assertNotNull(device.wait(Until.findObject(By.desc("退出全屏")), 8000))
                device.pressBack(); assertLandscape()
                assertNotNull(device.wait(Until.findObject(By.desc("视频详情区域")), 10000))
                scenario.onActivity { assertSame(original, it.window.decorView.findViewWithTag<View>("bilidesk_player")) }
            } catch (failure: Throwable) {
                device.takeScreenshot(File(context.getExternalFilesDir(null), "tablet-failure.png"))
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "tablet-failure.xml"))
                throw failure
            } finally { scenario.onActivity { Graph.player.stop(); Graph.store.update(Graph.store.settings.value.copy(playbackSpeed = 1f, holdSpeed = 2f, holdSpeedEnabled = true, colored = true, opacity = .85f, area = .5f, fontSize = 18f)) }; device.unfreezeRotation() }
        }
    }
}
