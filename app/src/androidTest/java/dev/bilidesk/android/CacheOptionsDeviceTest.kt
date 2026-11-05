// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheOptionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 45000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return true; SystemClock.sleep(120) }
        return false
    }
    @Test fun chosenQualityAndAudioOnlyProduceSeparatePlayableCaches() {
        Configurator.getInstance().waitForIdleTimeout = 100
        InteractionFixture(instrumentation, downloadMedia = true, musicMedia = true).use { fixture ->
            fixture.slowDownloads = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val detail = runBlocking { Graph.api.detail(Video(bvid = "BV156xx6wEff")) }; val ep = detail.pages.first()
                val keys = mutableListOf<String>()
                val settings = Graph.store.settings.value
                fun dialog(audio: Boolean) { scenario.onActivity { activity -> activity.setContent { BiliTheme(settings) { Surface { var visible by remember(audio) { mutableStateOf(true) }; if (visible) DownloadOptionsDialog(detail.video, ep, { visible = false }, audioDefault = audio) } } } } }
                fun cached(type: String, q: Int = 0) = Graph.store.caches.value.firstOrNull { it.video.key == detail.video.key && it.type == type && (q == 0 || it.quality == q) }
                fun tracks(entry: CacheEntry): List<String> { val ex = MediaExtractor(); return try { ex.setDataSource(Graph.store.cacheFile(entry).path); (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() } } finally { ex.release() } }
                try {
                    dialog(false)
                    device.wait(Until.findObject(By.text("视频（带声音）")), 10000)!!.click()
                    device.wait(Until.findObject(By.desc("缓存画质 720P")), 10000)!!.click()
                    device.findObject(By.text("开始缓存"))!!.click()
                    assertTrue("Selected video cache did not finish", waitFor { cached("video", 64) != null })
                    val video = cached("video", 64)!!; keys.add(video.key)
                    assertEquals(64, fixture.requestedQualities.last()); assertTrue(tracks(video).contains("video/avc")); assertTrue(tracks(video).any { it.startsWith("audio/") })
                    dialog(true)
                    device.wait(Until.findObject(By.text("开始缓存")), 10000)!!.click()
                    assertTrue("Audio-only cache did not finish", waitFor { cached("audio") != null })
                    val audio = cached("audio")!!; keys.add(audio.key)
                    assertNotEquals(video.key, audio.key); assertEquals(listOf("audio/mp4a-latm"), tracks(audio))
                    assertTrue(Graph.store.cacheFile(video).isFile)
                    scenario.onActivity { Graph.player.playCache(audio) }
                    assertTrue("Cached audio did not play offline", waitFor { Graph.player.state.value.local && Graph.player.state.value.music && Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    scenario.onActivity { activity ->
                        Graph.player.stop()
                        val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                        vm.openCache(video)
                        activity.setContent { BiliTheme(settings) { BiliApp(vm, false, {}) } }
                    }
                    assertTrue("Cached video was confused with audio variant", waitFor { Graph.player.state.value.local && !Graph.player.state.value.music && Graph.player.exo.isPlaying && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                } catch (e: Throwable) { device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "cache-options-failure.png")); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "cache-options-failure.xml")); throw e }
                finally { scenario.onActivity { Graph.player.stop(); Graph.downloads.tasks.value.forEach { Graph.downloads.cancel(it.key) } }; keys.forEach { key -> Graph.store.caches.value.firstOrNull { it.key == key }?.let(Graph.store::deleteCache) } }
            }
        }
    }
}
