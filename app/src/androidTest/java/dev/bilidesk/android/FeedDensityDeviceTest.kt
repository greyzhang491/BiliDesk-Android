// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
class FeedDensityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    @Test fun headerScrollsAwayAndDensityAndExplicitColumnsReallyReflowAndPersist() {
        Configurator.getInstance().waitForIdleTimeout = 100
        val original = Graph.store.settings.value
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    Graph.store.update(original.copy(feedDensity = 1, feedColumns = 0))
                    activity.setContent { val s by Graph.store.settings.collectAsStateWithLifecycle(); BiliTheme(s) { Surface(Modifier.safeDrawingPadding()) {
                        RefreshableVideoFeed(onVideo = {}, header = { Text("随列表收起的标题") }, load = { FeedPage((0..35).map { Video(aid = 1000L + it, title = "密度视频$it") }, more = false) })
                    } } }
                }
                assertNotNull(device.wait(Until.findObject(By.text("随列表收起的标题")), 10000))
                assertNotNull(device.wait(Until.findObject(By.text("密度视频0")), 10000))
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 30)
                assertTrue("Header remained fixed while scrolling", device.wait(Until.gone(By.text("随列表收起的标题")), 6000))
                repeat(3) { if (!device.hasObject(By.desc("调整视频排列"))) device.swipe(device.displayWidth / 2, device.displayHeight / 4, device.displayWidth / 2, device.displayHeight * 4 / 5, 30) }
                device.wait(Until.findObject(By.desc("调整视频排列")), 10000)!!.click()
                device.wait(Until.findObject(By.desc("卡片密度 紧凑")), 8000)!!.click()
                device.wait(Until.findObject(By.desc("每行数量 自动")), 8000)!!.click()
                device.findObject(By.text("完成"))!!.click()
                assertTrue("Density choice was overwritten by the column choice", device.wait(Until.hasObject(By.textStartsWith("紧凑 ·")), 6000))
                val a = device.wait(Until.findObject(By.text("密度视频0")), 10000)!!.visibleBounds
                val b = device.findObject(By.text("密度视频1"))!!.visibleBounds
                val c = device.findObject(By.text("密度视频2"))!!.visibleBounds
                assertTrue("Compact layout did not place at least 3 cards in a row", kotlin.math.abs(a.top - b.top) < 4 && kotlin.math.abs(a.top - c.top) < 4)
                device.findObject(By.desc("调整视频排列"))!!.click()
                device.wait(Until.findObject(By.desc("每行数量 1")), 8000)!!.click()
                device.findObject(By.text("完成"))!!.click()
                assertTrue(device.wait(Until.hasObject(By.textContains("每行 1 个")), 6000))
                val one = device.wait(Until.findObject(By.text("密度视频0")), 10000)!!.visibleBounds
                val two = device.findObject(By.text("密度视频1"))!!.visibleBounds
                val card = device.findObject(By.text("密度视频0"))!!.parent.visibleBounds
                assertTrue("Explicit single column did not reflow cards", two.top > one.bottom && card.width() > device.displayWidth * .8)
                val restored = Store(instrumentation.targetContext).settings.value
                assertEquals(2, restored.feedDensity); assertEquals(1, restored.feedColumns)
                device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "feed-single-column.png"))
            } catch (failure: Throwable) { device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "feed-density-failure.png")); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "feed-density-failure.xml")); throw failure }
            finally { scenario.onActivity { Graph.store.update(original) } }
        }
    }

    @Test fun paginationFailureStopsAutomaticRetryUntilUserRetries() {
        Configurator.getInstance().waitForIdleTimeout = 100
        val calls = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { BiliTheme(Settings()) { Surface(Modifier.safeDrawingPadding()) {
                RefreshableVideoFeed(onVideo = {}, load = { page ->
                    val n = calls.incrementAndGet()
                    if (page == 2 && n == 2) throw java.io.IOException("分页测试失败")
                    FeedPage((0..23).map { Video(aid = page * 100L + it, title = "自动分页$page-$it") }, more = page == 1)
                })
            } } } }
            assertNotNull(device.wait(Until.findObject(By.text("自动分页1-0")), 10000))
            repeat(15) { if (calls.get() < 2) device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 25) }
            repeat(4) { device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 25) }
            SystemClock.sleep(1200)
            assertEquals("A failed page was repeatedly requested automatically", 2, calls.get())
            val retry = device.wait(Until.findObject(By.textContains("分页测试失败")), 10000)
            assertNotNull(retry); retry!!.click()
            repeat(3) { if (!device.hasObject(By.textStartsWith("自动分页2-"))) device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 25) }
            assertTrue("Retry did not display the next page", device.wait(Until.hasObject(By.textStartsWith("自动分页2-")), 10000))
            assertEquals(3, calls.get())
        }
    }
}
