// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.Intent
import android.app.ActivityManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Device/network regression: real public Bilibili media, not mocked playback state. */
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return true
            SystemClock.sleep(250)
        }
        return false
    }
    private fun errorSummary(error: PlaybackException): String {
        val parts = mutableListOf("code=${error.errorCode}")
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return@repeat
            // Keep only types: neither cookies nor signed media URLs enter the test report.
            parts.add(current.javaClass.name)
            if (current is androidx.media3.exoplayer.source.MergingMediaSource.IllegalMergeException) parts.add("mergeReason=${current.reason}")
            cause = current.cause
        }
        return parts.joinToString(" -> ")
    }
    @Test fun videoActuallyAdvancesWithAudioVideoAndSeek() {
        val context = instrumentation.targetContext
        val bvid = InstrumentationRegistry.getArguments().getString("bvid") ?: "BV17x411w7KC"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/video/$bvid"), context, MainActivity::class.java)
        val error = AtomicReference("")
        val listener = object: Player.Listener {
            override fun onPlayerError(playbackError: PlaybackException) { error.set(errorSummary(playbackError)) }
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { Graph.player.exo.addListener(listener) }
            try {
                assertTrue("Playback never advanced. ${error.get()}", waitFor(60000) { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > 1200 })
                @Suppress("DEPRECATION")
                assertTrue("Media service did not enter foreground", waitFor(10000) { context.getSystemService(ActivityManager::class.java).getRunningServices(20).any { it.service.className.endsWith(".PlaybackService") && it.foreground } })
                scenario.onActivity {
                    assertTrue("Missing video track", Graph.player.exo.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_VIDEO))
                    assertTrue("Missing audio track", Graph.player.exo.currentTracks.isTypeSupported(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                    Graph.player.seek(30000)
                }
                assertTrue("Seek did not reach 30 seconds. ${error.get()}", waitFor(20000) { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 29000..45000 })
                scenario.onActivity {
                    assertTrue("No rendered video frames", (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                    assertTrue("No rendered audio buffers", (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
                }
            } finally { scenario.onActivity { Graph.player.exo.removeListener(listener); Graph.player.stop() } }
        }
    }
}
