// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.player.MusicMode
import dev.bilidesk.android.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MusicFacesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return; SystemClock.sleep(100) }; fail(message)
    }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) { if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache(); device.findObject(selector)?.let { return it }; SystemClock.sleep(100) }
        error("Missing music face: $selector")
    }
    private fun click(value: String) { var n = node(By.desc(value)); while (!n.isClickable && n.parent != null) n = n.parent; n.click() }
    @Test fun soundMarkersAndUnavailablePictureKeepAudioPlayingAndRetryCanRecover() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, musicMedia = true).use { fixture ->
            fixture.lyricSoundMarkersOnly = true; fixture.musicPictureUnavailable = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var media: Any? = null; var decoder: Any? = null; var buffers = 0
                try {
                    scenario.onActivity { val vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.music.play(Video(bvid = "BV156xx6wEff"), 101); Graph.player.music.mode(MusicMode.ONE); vm.openMusicPlayer() }
                    await("Audio did not decode") { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 15 }
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem; decoder = Graph.player.exo.audioDecoderCounters; buffers = Graph.player.exo.audioDecoderCounters!!.renderedOutputBufferCount }
                    click("显示歌词"); node(By.text("暂时没有可用歌词"))
                    scenario.onActivity { assertTrue(Graph.player.music.status.value.lyrics.isEmpty()); assertNull(Graph.player.musicPicture.player); assertFalse(Graph.player.state.value.musicVisual) }
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "025-music-lyrics-empty.png"))
                    click("显示原视频")
                    await("Broken picture did not surface a separate picture error") { Graph.player.musicPicture.state.value.error != null }
                    scenario.onActivity { assertTrue(Graph.player.exo.isPlaying); assertSame(media, Graph.player.exo.currentMediaItem); assertSame(decoder, Graph.player.exo.audioDecoderCounters); assertNull(Graph.player.state.value.error) }
                    fixture.musicPictureUnavailable = false
                    node(By.text("重新加载画面")).click()
                    await("Picture retry failed to recover") { Graph.player.musicPicture.state.value.ready && (Graph.player.musicPicture.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 }
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "025-music-original-recovered.png"))
                    click("显示唱片")
                    await("Returning to record did not stop picture work") { Graph.player.musicPicture.player == null && !Graph.player.state.value.musicVisual }
                    click("显示原视频")
                    await("Reopening reused the failed picture URL") { Graph.player.musicPicture.state.value.ready }
                    click("显示唱片"); await("Second picture was not released") { Graph.player.musicPicture.player == null }
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertSame(decoder, Graph.player.exo.audioDecoderCounters); assertTrue(Graph.player.exo.audioDecoderCounters!!.renderedOutputBufferCount > buffers + 15); assertTrue(Graph.player.exo.isPlaying) }
                } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
