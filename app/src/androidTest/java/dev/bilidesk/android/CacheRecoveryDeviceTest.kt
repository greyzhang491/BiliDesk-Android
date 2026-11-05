// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.download.Downloads
import dev.bilidesk.android.ui.AppViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 15000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var result = false; instrumentation.runOnMainSync { result = condition() }
            if (result) return true
            SystemClock.sleep(100)
        }
        return false
    }
    @Test fun pauseRestoreResumeRemuxAndOfflinePlaybackKeepExactProgress() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        val context = instrumentation.targetContext
        val original = Graph.store.settings.value
        InteractionFixture(instrumentation, downloadMedia = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var key: String? = null; var canceled: String? = null
                try {
                    val detail = runBlocking { Graph.api.detail(Video(bvid = "BV156xx6wEff")) }
                    val video = detail.video; val episode = detail.pages.first(); key = "${video.key}-${episode.cid}"
                    scenario.onActivity {
                        Graph.store.change { it.copy(shortCache = false) }
                        Graph.downloads.enqueue(video, episode)
                        ViewModelProvider(it)[AppViewModel::class.java].navigate("downloads")
                    }
                    assertTrue("Download bytes never advanced", waitFor { Graph.downloads.tasks.value.any { it.key == key && it.downloadedBytes > 256 * 1024 && it.running } })
                    device.wait(Until.findObject(By.text("暂停")), 8000)!!.click()
                    assertTrue("Pause did not stop the download promptly", waitFor(4000) { Graph.downloads.isIdle && Graph.downloads.tasks.value.any { it.key == key && it.paused } })
                    val partial = File(context.filesDir, "media/$key.video.part")
                    val retainedBytes = partial.length(); assertTrue(retainedBytes > 0)
                    SystemClock.sleep(300); assertEquals(retainedBytes, partial.length())
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "download-paused.png"))
                    // Recreate the task manager and Activity: queue and file survive a process-style restart.
                    scenario.onActivity { Graph.downloads.close(); Graph.downloads = Downloads(context, Graph.api, Graph.store) }
                    assertTrue(Graph.downloads.tasks.value.any { it.key == key && it.paused })
                    assertEquals(retainedBytes, partial.length())
                    val beforeResume = fixture.downloadOffsets.size
                    fixture.slowDownloads = false
                    scenario.recreate()
                    device.wait(Until.findObject(By.text("继续")), 10000)!!.click()
                    val completed = waitFor(240000) { Graph.downloads.tasks.value.any { it.key == key && !it.running } }
                    assertTrue("Resumed download did not complete: ${Graph.downloads.tasks.value.firstOrNull { it.key == key }}", completed)
                    val task = Graph.downloads.tasks.value.first { it.key == key }
                    assertNull(task.error); assertEquals("缓存完成", task.state)
                    assertEquals("Resume started from zero", retainedBytes, fixture.downloadOffsets[beforeResume])
                    val entry = Graph.store.caches.value.first { it.key == key }
                    assertFalse(entry.automatic); assertTrue(entry.bytes > 1024 * 1024)
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(Graph.store.cacheFile(entry).path)
                        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                        assertTrue(tracks.contains("video/avc")); assertTrue(tracks.any { it.startsWith("audio/") })
                    } finally { extractor.release() }
                    scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].openCache(entry) }
                    assertTrue("Completed cache did not decode offline audio and video", waitFor(30000) { Graph.player.state.value.local && Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > 1200 && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    scenario.onActivity {
                        Graph.player.stop()
                        val second = video.copy(bvid = "BV17x411w7KC")
                        fixture.slowDownloads = true
                        canceled = "${second.key}-${episode.cid}"
                        Graph.downloads.enqueue(second, episode)
                    }
                    assertTrue(waitFor { Graph.downloads.tasks.value.any { it.key == canceled && it.downloadedBytes > 256 * 1024 } })
                    scenario.onActivity { Graph.downloads.cancel(canceled!!) }
                    assertTrue("Cancel did not remove partial files", waitFor(4000) { Graph.downloads.isIdle && !File(context.filesDir, "media/$canceled.video.part").exists() })
                    assertTrue("Cancel removed another completed manual download", Graph.store.cacheFile(entry).isFile)
                } catch (failure: Throwable) {
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "download-recovery-failure.png")); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "download-recovery-failure.xml")); throw failure
                } finally {
                    scenario.onActivity { Graph.player.stop(); listOfNotNull(key, canceled).forEach(Graph.downloads::cancel); Graph.store.update(original) }
                    listOfNotNull(key, canceled).forEach { current -> Graph.store.caches.value.firstOrNull { it.key == current }?.let(Graph.store::deleteCache) }
                    device.unfreezeRotation()
                }
            }
        }
    }
}
