// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bilidesk.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CacheDownloadDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(timeout: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return true; SystemClock.sleep(200) }
        return false
    }
    @Test fun automaticDownloadRemuxesPlaysOfflineAndCanBeKeptManually() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).putExtra("open_player", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val previous = Graph.store.settings.value
            var downloadedKey: String? = null
            var brokenKey: String? = null
            try {
                val (video, episode) = runBlocking {
                    var candidate: Pair<Video, Episode>? = null
                    for (page in 1..3) {
                        val video = Graph.api.feed("热门", page).videos.firstOrNull { v ->
                            v.duration in 1..90 && Graph.store.caches.value.none { it.video.key == v.key }
                        } ?: continue
                        val detail = Graph.api.detail(video)
                        val ep = detail.pages.firstOrNull { it.duration in 1..90 } ?: continue
                        candidate = detail.video to ep; break
                    }
                    checkNotNull(candidate) { "No short public video available for bounded download regression" }
                }
                val key = "${video.key}-${episode.cid}"
                scenario.onActivity {
                    Graph.store.update(previous.copy(shortCache = false))
                    Graph.downloads.enqueue(video, episode, automatic = true)
                }
                assertTrue("Disabled auto cache queued a download", Graph.downloads.tasks.value.none { it.key == key })
                downloadedKey = key
                scenario.onActivity {
                    Graph.store.update(previous.copy(shortCache = true, autoCacheWifiOnly = false))
                    Graph.downloads.enqueue(video, episode, automatic = true)
                }
                assertTrue("Download did not finish", waitFor(120000) { Graph.downloads.tasks.value.any { it.key == key && !it.running } })
                val task = Graph.downloads.tasks.value.first { it.key == key }
                assertNull("Download failed: ${task.state}", task.error)
                assertEquals("缓存完成", task.state)
                val entry = Graph.store.caches.value.first { it.key == key }
                assertTrue(entry.automatic); assertTrue(entry.bytes > 1024)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(Graph.store.cacheFile(entry).absolutePath)
                    val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                    assertTrue("Merged MP4 must use compatible AVC video", mimes.contains("video/avc"))
                    assertTrue("Merged MP4 lacks audio", mimes.any { it.startsWith("audio/") })
                } finally { extractor.release() }
                scenario.onActivity { activity ->
                    Graph.player.playCache(entry)
                    activity.startActivity(Intent(activity, MainActivity::class.java).putExtra("open_player", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                assertTrue("Local MP4 did not output decoded audio and video", waitFor(30000) {
                    var played = false
                    instrumentation.runOnMainSync { played = Graph.player.state.value.local && Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > 1200 && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 }
                    played
                })
                scenario.onActivity {
                    Graph.player.stop()
                    Graph.downloads.enqueue(video, episode)
                }
                assertFalse("Manual keep must protect automatic file", Graph.store.caches.value.first { it.key == key }.automatic)
                Graph.store.clearAutomaticCache()
                assertTrue("Auto cleanup removed manually kept video", Graph.store.cacheFile(entry).exists())
                // An isolated corrupt MP4 exercises the same local-source error retry path as old HEVC caches.
                val brokenFile = File(instrumentation.targetContext.filesDir, "media/${key}_test_broken.mp4").apply { writeBytes(ByteArray(32)) }
                val broken = entry.copy(key = "${key}-test-broken", path = "media/${brokenFile.name}", bytes = brokenFile.length(), automatic = false)
                brokenKey = broken.key
                Graph.store.addCache(broken)
                scenario.onActivity { Graph.player.playCache(broken) }
                assertTrue("Corrupt local fixture did not report an error", waitFor(15000) { Graph.player.state.value.local && Graph.player.state.value.error != null })
                scenario.onActivity { Graph.player.retry() }
                val recovered = waitFor(45000) {
                    var played = false
                    instrumentation.runOnMainSync { played = !Graph.player.state.value.local && Graph.player.exo.isPlaying }
                    played
                }
                assertTrue("Local error retry did not recover: local=${Graph.player.state.value.local}, codec=${Graph.player.state.value.plan?.video?.codec}, error=${Graph.player.state.value.error}", recovered)
                assertTrue("Online retry deleted a manual cache", brokenFile.exists())
            } finally {
                scenario.onActivity { Graph.player.stop(); downloadedKey?.let { Graph.downloads.cancel(it) }; Graph.store.update(previous) }
                listOfNotNull(downloadedKey, brokenKey).forEach { key -> Graph.store.caches.value.firstOrNull { it.key == key }?.let { Graph.store.deleteCache(it) } }
            }
        }
    }
}
