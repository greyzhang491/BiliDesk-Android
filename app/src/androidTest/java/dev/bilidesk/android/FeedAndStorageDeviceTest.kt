// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FeedAndStorageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    @Test fun realPullGestureReplacesRecommendationsAndKeepsContentAfterFailure() {
        Configurator.getInstance().waitForIdleTimeout = 200
        device.setOrientationNatural()
        val calls = AtomicInteger()
        val pages = CopyOnWriteArrayList<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    BiliTheme(Settings()) { Surface {
                        RefreshableVideoFeed(onVideo = {}, load = { page ->
                            val call = calls.incrementAndGet(); pages += page
                            delay(600)
                            if (call == 3) throw java.io.IOException("测试刷新失败")
                            FeedPage((0..23).map { Video(aid = call * 100L + it, title = "推荐批次$call-$it", author = "刷新验证") }, more = page == 1)
                        })
                    } }
                }
            }
            fun waitTitle(title: String) = assertNotNull(device.wait(Until.findObject(By.text(title)), 12000))
            fun pull() { device.swipe(device.displayWidth / 2, (device.displayHeight * .22f).toInt(), device.displayWidth / 2, (device.displayHeight * .78f).toInt(), 65) }
            try {
                waitTitle("推荐批次1-0")
                pull(); waitTitle("推荐批次2-0")
                assertFalse("Refresh appended old content", device.hasObject(By.text("推荐批次1-0")))
                pull()
                assertNotNull(device.wait(Until.findObject(By.text("刷新失败，已保留原内容 · 重试")), 12000))
                waitTitle("推荐批次2-0")
                assertEquals(3, calls.get())
                device.findObject(By.text("刷新失败，已保留原内容 · 重试")).click()
                waitTitle("推荐批次4-0")
                repeat(16) { if (!pages.contains(2)) device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 25) }
                val end = SystemClock.elapsedRealtime() + 10000
                while (calls.get() < 5 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
                assertEquals("Scrolling must load page 2 without a button", 5, calls.get())
                assertEquals(listOf(1, 1, 1, 1, 2), pages.toList())
            } catch (failure: Throwable) {
                val dir = instrumentation.targetContext.getExternalFilesDir(null)
                device.takeScreenshot(File(dir, "refresh-failure.png")); device.dumpWindowHierarchy(File(dir, "refresh-failure.xml"))
                throw failure
            } finally { device.unfreezeRotation() }
        }
    }
    @Test fun cacheUpgradeAndRealFilesPreserveManualAndPlayingDownloads() {
        val base = instrumentation.targetContext
        val dir = File(base.cacheDir, "cache-policy-device-test").apply { mkdirs() }
        val prefix = "cache-policy-test-"
        val context = object: ContextWrapper(base) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("shortCache", true).commit()
        val store = Store(context)
        assertFalse("Upgrade must opt out of automatic downloads", store.settings.value.shortCache)
        fun add(key: String, automatic: Boolean, age: Long): CacheEntry {
            val file = File(dir, "media/$key.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096)) }
            val entry = CacheEntry(key, Video(aid = key.hashCode().toLong(), title = key), 1, key, "media/${file.name}", file.length(), automatic, System.currentTimeMillis() - age)
            store.addCache(entry); return entry
        }
        try {
            val manual = add("manual", false, 40 * 86400000L)
            val playing = add("playing", true, 0)
            store.pinCache(playing.key)
            store.addCache(playing.copy(accessedAt = System.currentTimeMillis() - 40 * 86400000L))
            val expired = add("expired", true, 40 * 86400000L)
            store.pruneAutomaticCache()
            assertTrue(store.cacheFile(manual).exists())
            assertTrue(store.cacheFile(playing).exists())
            assertFalse(store.cacheFile(expired).exists())
            val recent = add("recent", true, 0)
            store.clearAutomaticCache()
            assertFalse(store.cacheFile(recent).exists())
            assertTrue(store.cacheFile(manual).exists())
            assertTrue(store.cacheFile(playing).exists())
            store.pinCache(null); store.pruneAutomaticCache()
            assertFalse(store.cacheFile(playing).exists())
            val restored = Store(context)
            assertEquals(listOf("manual"), restored.caches.value.map { it.key })
        } finally {
            store.caches.value.forEach { store.deleteCache(it) }
            SystemClock.sleep(200)
            prefs.edit().clear().commit()
            // All paths here were created exclusively inside this test's isolated directory.
            dir.deleteRecursively()
        }
    }
}
