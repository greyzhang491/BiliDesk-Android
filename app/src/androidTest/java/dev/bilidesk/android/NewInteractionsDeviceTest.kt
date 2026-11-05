// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NewInteractionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 20000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return true; SystemClock.sleep(120) }
        return false
    }
    @Test fun heldDownDragSelectsSpeedAndShortLayoutHasNoPermanentTutorial() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        val original = Graph.store.settings.value
        InteractionFixture(instrumentation).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity {
                        Graph.store.update(original.copy(playbackSpeed = 1.25f, holdSpeed = 3f, holdSpeedEnabled = true, shortsAutoNext = false))
                        ViewModelProvider(it)[AppViewModel::class.java].openShorts(Video(bvid = "BV156xx6wEff"))
                    }
                    assertTrue(waitFor { Graph.player.exo.isPlaying && PlayerViewHost.currentView?.isShown == true })
                    device.wait(Until.findObject(By.text("Got it")), 1500)?.click()
                    scenario.onActivity { PlayerViewHost.currentView!!.hideController() }
                    val title = device.wait(Until.findObject(By.text("交互测试样片 A")), 10000)!!
                    assertTrue("Title should be close to the bottom", title.visibleBounds.centerY() > device.displayHeight * .85f)
                    val like = device.wait(Until.findObject(By.desc("点赞视频")), 10000)!!
                    assertTrue("Story actions should be in the lower half", like.visibleBounds.centerY() > device.displayHeight * .5f)
                    assertFalse(device.hasObject(By.textContains("上下滑换片")))
                    assertFalse(device.hasObject(By.textContains("长按后下拉锁定")))
                    val x = device.displayWidth * .4f; val y = device.displayHeight * .3f
                    val density = instrumentation.targetContext.resources.displayMetrics.density
                    fun pointer(down: Long, action: Int, dy: Float) { val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + dy * density, 0); try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
                    fun select(extra: Float, expected: Float) {
                        val down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, 0f)
                        try {
                            SystemClock.sleep(650)
                            assertTrue("Hold did not accelerate", waitFor(2500) { Graph.player.exo.playbackParameters.speed == 3f || Graph.player.exo.playbackParameters.speed == 4f })
                            pointer(down, MotionEvent.ACTION_MOVE, 80f)
                            assertTrue("Speed chooser did not open", device.wait(Until.hasObject(By.text("松手确定")), 4000))
                            assertFalse("Story actions overlap the speed chooser", device.hasObject(By.desc("点赞视频")))
                            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "short-speed-chooser-0.1.6.png"))
                            if (extra != 0f) pointer(down, MotionEvent.ACTION_MOVE, 80f + extra)
                        } finally { pointer(down, MotionEvent.ACTION_UP, 80f + extra) }
                        assertTrue("Selected multiplier was not retained", waitFor { Graph.player.exo.playbackParameters.speed == expected })
                        assertTrue(device.wait(Until.gone(By.text("松手确定")), 4000))
                    }
                    select(40f, 4f)
                    select(0f, 1.25f)
                    assertEquals(1.25f, Graph.store.settings.value.playbackSpeed, .001f)
                    assertEquals("BV156xx6wEff", Graph.player.state.value.detail?.video?.bvid)
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "short-layout-0.1.6.png"))
                } catch (e: Throwable) { device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "short-chooser-failure.png")); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "short-chooser-failure.xml")); throw e }
                finally { scenario.onActivity { Graph.player.stop(); Graph.store.update(original) }; device.unfreezeRotation() }
            }
        }
    }
    @Test fun refreshingRepeatedFirstPagePutsNewRecommendationsInFirstVisibleRow() {
        Configurator.getInstance().waitForIdleTimeout = 100
        val tablet = instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600
        if (tablet) device.setOrientationLeft() else device.setOrientationNatural()
        val calls = AtomicInteger()
        val settings = Graph.store.settings.value
        val old = (1..24).map { Video(aid = it.toLong(), title = "原推荐 $it") }
        val fresh = (101..108).map { Video(aid = it.toLong(), title = "新推荐 $it") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity -> activity.setContent { BiliTheme(settings) { Surface(Modifier.safeDrawingPadding()) { RefreshableVideoFeed(onVideo = {}, preferNewOnRefresh = true) { page ->
                    FeedPage(if (page == 1 && calls.incrementAndGet() == 1) old else old.take(16) + fresh, more = false)
                } } } } }
                assertNotNull(device.wait(Until.findObject(By.text("原推荐 1")), 10000))
                device.findObject(By.desc("刷新视频列表"))!!.click()
                val newest = device.wait(Until.findObject(By.text("新推荐 101")), 10000)!!
                assertTrue("New recommendations are still below old cards", newest.visibleBounds.top < device.displayHeight / 2)
                assertTrue(calls.get() >= 2)
                device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "refresh-new-first-0.1.6.png"))
            } finally { device.unfreezeRotation() }
        }
    }
}
