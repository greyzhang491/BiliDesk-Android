// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All writes in this test are intercepted locally; no real login, coin or favorite changes. */
@RunWith(AndroidJUnit4::class)
class ShortActionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 12000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var result = false; instrumentation.runOnMainSync { result = condition() }
            if (result) return true
            SystemClock.sleep(120)
        }
        return false
    }
    private fun clickAction(description: String) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            var node = device.findObject(By.desc(description))
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true) { node.click(); return }
            SystemClock.sleep(100)
        }
        fail("Missing enabled action: $description")
    }
    @Test fun likesFavoritesShareAndCoinAreAvailableWithoutReplacingThePlayingMedia() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var vm: AppViewModel? = null
                val chooser = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm!!.openShorts(Video(bvid = "BV156xx6wEff")) }
                    assertTrue(waitFor { Graph.player.exo.isPlaying && vm!!.user.value != null })
                    device.wait(Until.findObject(By.text("Got it")), 1500)?.click()
                    clickAction("点赞视频")
                    assertNotNull(device.wait(Until.findObject(By.desc("取消点赞")), 10000))
                    assertEquals(1, fixture.writeCount("/x/web-interface/archive/like"))
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(300)
                    clickAction("取消点赞")
                    assertTrue("Unlike tap did not submit", waitFor { fixture.writeCount("/x/web-interface/archive/like") == 2 })
                    assertNotNull(device.wait(Until.findObject(By.text("点赞")), 10000))
                    assertFalse("Unlike request did not change the simulated server state", fixture.simulatedLiked)
                    assertEquals(2, fixture.writeCount("/x/web-interface/archive/like"))
                    var item: Any? = null; var position = 0L
                    scenario.onActivity { item = Graph.player.exo.currentMediaItem; position = Graph.player.exo.currentPosition }
                    clickAction("收藏视频")
                    device.wait(Until.findObject(By.desc("收藏夹 模拟收藏夹")), 10000)!!.click()
                    assertTrue(waitFor { fixture.writeCount("/x/v3/fav/resource/deal") == 1 })
                    assertTrue("Favorite overlay interrupted playback", waitFor { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > position + 600 })
                    device.findObject(By.desc("关闭播放器设置"))!!.click()
                    assertNotNull(device.wait(Until.findObject(By.text("已收藏")), 8000))
                    clickAction("分享视频")
                    assertEquals("Share chooser was not launched", 1, chooser.hits)
                    clickAction("短视频选项")
                    device.wait(Until.findObject(By.text("投币")), 10000)!!.click()
                    assertTrue(device.wait(Until.hasObject(By.text("本视频尚未投币")), 10000))
                    val coin = device.wait(Until.findObject(By.text("投1枚硬币")), 10000)
                    assertNotNull(coin); coin!!.click()
                    assertTrue(waitFor { fixture.writeCount("/x/web-interface/coin/add") == 1 })
                    assertNotNull(device.wait(Until.findObject(By.textContains("投币成功")), 10000))
                    device.findObject(By.desc("关闭播放器设置"))!!.click()
                    scenario.onActivity { assertSame(item, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "short-actions.png"))
                } catch (failure: Throwable) { device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "short-actions-failure.png")); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "short-actions-failure.xml")); throw failure }
                finally { instrumentation.removeMonitor(chooser); scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
