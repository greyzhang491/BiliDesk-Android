// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CoinPanelDeviceTest {
    @Test fun rejectionStaysVisibleAndRequiresReadOnlyRefreshBeforeExplicitRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        Configurator.getInstance().waitForIdleTimeout = 200
        val submitted = AtomicInteger()
        val reads = AtomicInteger()
        val current = AtomicInteger()
        val rejected = AtomicBoolean(true)
        val officialOpened = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    BiliTheme(Settings()) { Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            CoinPanel(Video(bvid = "BV156xx6wEff", aid = 12345), onLogin = {},
                                loadCount = { reads.incrementAndGet(); delay(500); current.get() },
                                submit = { count ->
                                    submitted.incrementAndGet(); delay(1800)
                                    if (rejected.get()) throw ActionErrors.from(JSONObject("""{"code":-401,"message":"非法访问"}"""))
                                    current.addAndGet(count); Unit
                                }, onOfficial = { officialOpened.incrementAndGet() })
                        }
                    } }
                }
            }
            fun find(text: String): UiObject2 {
                val node = device.wait(Until.findObject(By.text(text)), 10000)
                assertNotNull("Missing: $text", node); return node
            }
            fun tap(text: String) { val bounds = find(text).visibleBounds; device.click(bounds.centerX(), bounds.centerY()) }
            try {
                find("本视频尚未投币")
                val bounds = find("投1枚硬币").visibleBounds
                device.click(bounds.centerX(), bounds.centerY()); device.click(bounds.centerX(), bounds.centerY())
                assertNotNull(device.wait(Until.findObject(By.textContains("错误码 -401")), 12000))
                assertEquals("Double tap queued another submission", 1, submitted.get())
                tap("投1枚硬币"); SystemClock.sleep(300)
                assertEquals("Rejected state allowed another write without refresh", 1, submitted.get())
                assertEquals(1, reads.get())
                device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "coin-rejected-panel.png"))
                rejected.set(false)
                tap("刷新已投枚数"); find("本视频尚未投币")
                assertEquals("Refresh performed a write", 1, submitted.get()); assertEquals(2, reads.get())
                tap("投1枚硬币")
                assertNotNull(device.wait(Until.findObject(By.textContains("投币成功")), 12000))
                assertEquals(2, submitted.get()); assertEquals(1, current.get())
                tap("投2枚硬币"); SystemClock.sleep(300)
                assertEquals("One prior coin must disable two more coins", 2, submitted.get())
                tap("打开官方页面"); assertEquals(1, officialOpened.get())
            } catch (failure: Throwable) {
                device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "coin-ui-failure.png"))
                device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "coin-ui-failure.xml"))
                throw failure
            }
        }
    }
}
