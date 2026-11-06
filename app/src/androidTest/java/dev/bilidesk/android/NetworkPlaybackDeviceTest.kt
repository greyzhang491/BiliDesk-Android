// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, timeout: Long = 25000, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; instrumentation.runOnMainSync { ready = test() }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("$message; state=${Graph.player.state.value.error}; pos=${Graph.player.state.value.position}")
    }
    @Test fun indexedVideoResumesBothTracksAndSeeksAcrossDistantSegments() {
        val settings = Graph.store.settings.value
        InteractionFixture(instrumentation, networkMedia = true).use { fixture ->
            Graph.store.record(Video(bvid = "BV156xx6wEff"), 101, 0)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    var started = 0L
                    scenario.onActivity {
                        Graph.store.update(settings.copy(quality = 80, danmaku = false))
                        started = SystemClock.elapsedRealtime()
                        ViewModelProvider(it)[AppViewModel::class.java].openVideo(Video(bvid = "BV156xx6wEff"))
                    }
                    await("Indexed A/V never rendered") { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > 600 }
                    val startup = SystemClock.elapsedRealtime() - started
                    assertTrue("Startup unexpectedly waited for CDN probing: $startup", startup < 12000)
                    val net = fixture.network!!
                    assertTrue("Video backup was not exercised", net.requests.any { it.host.startsWith("backup") && it.track == "high" && it.start > 0 })
                    assertTrue("Audio backup was not exercised", net.requests.any { it.host.startsWith("backup") && it.track == "audio" && it.start > 0 })
                    for (target in listOf(65000L, 12000L, 82000L)) {
                        val before = net.requests.size
                        scenario.onActivity { Graph.player.seek(target) }
                        await("Seek to $target failed", 12000) { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in target - 900..target + 5000 }
                        assertTrue("Seek did not request an indexed segment", net.requests.drop(before).any { it.track == "high" && it.start > 100000 })
                    }
                    scenario.onActivity {
                        assertTrue((Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                        assertTrue((Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                        assertNull(Graph.player.state.value.error)
                    }
                    net.offline = true
                    scenario.onActivity { Graph.player.seek(50000) }
                    await("Disconnected media did not expose a recoverable state", 35000) { Graph.player.state.value.error != null }
                    scenario.onActivity { Graph.player.exo.pause() }
                    net.offline = false
                    scenario.onActivity { Graph.player.onNetworkRecovered() }
                    await("Reconnect did not restore the paused seek target", 12000) { Graph.player.exo.playbackState == Player.STATE_READY && Graph.player.state.value.error == null }
                    scenario.onActivity {
                        assertFalse("Reconnect unexpectedly resumed a paused video", Graph.player.exo.playWhenReady)
                        assertTrue(Graph.player.exo.currentPosition in 49000..52000)
                        Graph.player.exo.play()
                    }
                    await("Recovered video did not resume") { Graph.player.exo.isPlaying }
                    val type = if (instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
                    val metrics = org.json.JSONObject().put("controlledStartupMs", startup).put("independentAudioVideoFailover", true).put("seeksMs", org.json.JSONArray(listOf(65000,12000,82000))).put("pausedReconnectAtMs", 50000).put("physicalNetworkBenchmark", false)
                    java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "network-metrics-$type-0.2.2.json").writeText(metrics.toString())
                } finally { scenario.onActivity { Graph.store.update(settings); Graph.player.stop() } }
            }
        }
    }
    @Test fun adaptiveVideoDownshiftsWhenTransferSlows() {
        val settings = Graph.store.settings.value
        InteractionFixture(instrumentation, networkMedia = true).use { fixture ->
            fixture.network!!.apply { bytesPerSecond = 25000; breakPrimary = false }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity {
                        Graph.store.update(settings.copy(quality = 0, danmaku = false))
                        ViewModelProvider(it)[AppViewModel::class.java].openVideo(Video(bvid = "BV17x411w7KC"))
                    }
                    await("Adaptive DASH did not start", 45000) { Graph.player.exo.isPlaying }
                    await("Adaptive DASH never selected the lower representation", 60000) { Graph.player.exo.isPlaying && Graph.player.exo.videoFormat?.width == 320 }
                    assertTrue(fixture.network.requests.any { it.track == "low" && it.start > 1000 })
                    scenario.onActivity { assertTrue(Graph.player.state.value.plan?.automatic == true); assertNull(Graph.player.state.value.error) }
                    println("Controlled 25KB/s-per-source transfer selected the 160kbps representation")
                } finally { scenario.onActivity { Graph.store.update(settings); Graph.player.stop() } }
            }
        }
    }
    @Test fun indexedMusicLoadsNoVideoAndRetainsIdentityWhenPictureOpens() {
        val settings = Graph.store.settings.value
        InteractionFixture(instrumentation, networkMedia = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { Graph.store.update(settings.copy(danmaku = false)); ViewModelProvider(it)[AppViewModel::class.java].playMusic(Video(bvid = "BV156xx6wEff")) }
                    await("Indexed music did not play") { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > 500 }
                    assertTrue("Listening requested video bytes", fixture.network!!.requests.all { it.track == "audio" })
                    var identity: Any? = null
                    scenario.onActivity { identity = Graph.player.exo.currentMediaItem; Graph.player.setMusicVideo(true) }
                    await("Opening indexed picture did not render") { Graph.player.exo.isPlaying && Graph.player.exo.videoFormat != null }
                    scenario.onActivity { assertSame(identity, Graph.player.exo.currentMediaItem); Graph.player.setMusicVideo(false) }
                    await("Closing picture interrupted music") { Graph.player.exo.isPlaying }
                    scenario.onActivity { assertSame(identity, Graph.player.exo.currentMediaItem) }
                } finally { scenario.onActivity { Graph.store.update(settings); Graph.player.stop() } }
            }
        }
    }
    @Test fun partialSegmentFailoverIsByteExactAndKeepsTheRequestedRange() {
        InteractionFixture(instrumentation, networkMedia = true).use { fixture ->
            val upstream = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Graph.api.http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build())
            val primary = "https://primary.fixture.invalid/high"
            val source = dev.bilidesk.android.player.ResilientMediaDataSource(upstream, mapOf(primary to listOf("https://backup.fixture.invalid/high")))
            val out = java.io.ByteArrayOutputStream()
            try {
                assertEquals(150000L, source.open(androidx.media3.datasource.DataSpec.Builder().setUri(primary).setPosition(2000).setLength(150000).build()))
                val buffer = ByteArray(4096)
                while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
            } finally { source.close() }
            val original = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "network-media/high.mp4").readBytes()
            assertArrayEquals(original.copyOfRange(2000, 152000), out.toByteArray())
            assertTrue(fixture.network!!.requests.any { it.host.startsWith("backup") && it.start == 34768L && it.length == 117232L })
        }
    }

}
