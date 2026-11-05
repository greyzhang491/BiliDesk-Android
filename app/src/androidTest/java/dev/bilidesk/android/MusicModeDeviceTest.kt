// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.player.MusicMode
import dev.bilidesk.android.player.PlaybackService
import dev.bilidesk.android.ui.AppViewModel
import dev.bilidesk.android.ui.PlayerViewHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MusicModeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 20000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return true; SystemClock.sleep(120) }
        return false
    }
    @Test fun musicQueueLyricsMiniPlayerVideoFallbackAndSessionCommands() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var vm: AppViewModel? = null; var remote: MediaController? = null
                try {
                    scenario.onActivity {
                        vm = ViewModelProvider(it)[AppViewModel::class.java]; vm!!.root("music")
                        Graph.player.music.playAll(listOf(Video(bvid = "BV156xx6wEff", title = "交互测试样片 A"), Video(bvid = "BV17x411w7KC", title = "交互测试样片 B")))
                        Graph.player.music.mode(MusicMode.ONE); vm!!.openMusicPlayer()
                    }
                    assertTrue("Music did not decode audio", waitFor { Graph.player.state.value.music && Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    device.wait(Until.findObject(By.text("Got it")), 1500)?.click()
                    assertTrue("Caption timestamps were not loaded", waitFor { Graph.player.music.status.value.lyrics.size == 3 })
                    assertNotNull(device.wait(Until.findObject(By.desc("音乐唱片，点击切换歌词或原视频")), 10000))
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "music-vinyl.png"))
                    scenario.onActivity { Graph.player.seek(1000) }
                    var media: Any? = null
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    device.wait(Until.findObject(By.desc("音乐唱片，点击切换歌词或原视频")), 10000)!!.click()
                    assertTrue(device.wait(Until.hasObject(By.desc("同步滚动歌词")), 10000))
                    device.wait(Until.findObject(By.text("第二句字幕歌词")), 10000)!!.click()
                    assertTrue("Lyric tap did not seek to its timestamp", waitFor { Graph.player.exo.currentPosition in 8000..15000 && Graph.player.exo.isPlaying })
                    scenario.onActivity { assertSame("Opening lyrics reloaded the song", media, Graph.player.exo.currentMediaItem) }
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "music-lyrics.png"))
                    scenario.onActivity { vm!!.navigate("music_search") }
                    assertNotNull(device.wait(Until.findObject(By.desc("暂停音乐")), 10000))
                    var buffers = 0
                    scenario.onActivity { buffers = Graph.player.exo.audioDecoderCounters!!.renderedOutputBufferCount }
                    assertTrue("Leaving the player interrupted music", waitFor { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > buffers + 5 })
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "测试歌曲"
                    device.findObject(By.desc("搜索音乐"))!!.click()
                    device.wait(Until.findObject(By.desc("歌曲选项 交互测试样片 B")), 10000)!!.click()
                    device.wait(Until.findObject(By.text("下一首播放")), 10000)!!.click()
                    assertTrue(waitFor { Graph.player.music.queue.value.nextUp.isNotEmpty() })
                    scenario.onActivity { assertEquals("BV156xx6wEff", Graph.player.state.value.detail!!.video.bvid) }
                    val future = MediaController.Builder(instrumentation.targetContext, SessionToken(instrumentation.targetContext, ComponentName(instrumentation.targetContext, PlaybackService::class.java))).setApplicationLooper(Looper.getMainLooper()).buildAsync()
                    remote = future.get(15, TimeUnit.SECONDS)
                    scenario.onActivity { buffers = Graph.player.exo.audioDecoderCounters!!.renderedOutputBufferCount }
                    device.pressHome()
                    assertTrue("Home transition did not complete", device.wait(Until.hasObject(By.pkg("com.android.launcher3")), 5000))
                    SystemClock.sleep(1200)
                    assertTrue("Music stopped in the background", waitFor { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > buffers + 15 })
                    val returnIntent = instrumentation.targetContext.packageManager.getLaunchIntentForPackage(instrumentation.targetContext.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    instrumentation.targetContext.startActivity(returnIntent)
                    assertNotNull(device.wait(Until.findObject(By.desc("暂停音乐")), 10000))
                    // ActivityScenario queries must stay on the instrumentation thread.
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    scenario.onActivity { assertTrue("Lock-screen next command unavailable", remote!!.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)); remote!!.seekToNext() }
                    assertTrue("Session next did not play the manually queued song", waitFor { Graph.player.state.value.detail?.video?.bvid == "BV17x411w7KC" && Graph.player.exo.isPlaying })
                    assertTrue(waitFor { !Graph.player.music.status.value.lyricsLoading && Graph.player.music.status.value.lyrics.isEmpty() })
                    scenario.onActivity { vm!!.openMusicPlayer(); media = Graph.player.exo.currentMediaItem; Graph.player.seek(5000) }
                    device.wait(Until.findObject(By.desc("音乐唱片，点击切换歌词或原视频")), 10000)!!.click()
                    assertTrue("No-caption track did not show original video", waitFor { Graph.player.state.value.musicVisual && Graph.player.exo.isPlaying && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && PlayerViewHost.currentView?.tag == "bilidesk_music_video" })
                    scenario.onActivity { assertSame("Video fallback replaced the playing media", media, Graph.player.exo.currentMediaItem) }
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "music-original-video.png"))
                    scenario.onActivity { vm!!.root("music") }
                    assertNotNull(device.wait(Until.findObject(By.text("模拟收藏夹")), 15000))
                    if (instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600) {
                        device.setOrientationLeft()
                        assertTrue(waitFor { device.displayWidth > device.displayHeight })
                        val cover = device.wait(Until.findObject(By.desc("音乐唱片，点击切换歌词或原视频")), 15000)!!
                        val folder = device.wait(Until.findObject(By.text("模拟收藏夹")), 15000)!!
                        assertTrue("Tablet cover must be to the right of the song library", cover.visibleBounds.left > folder.visibleBounds.right)
                    }
                    assertTrue(waitFor { Graph.player.exo.isPlaying })
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "music-vinyl-layout.png"))
                    assertFalse("Dynamics navigation was not removed", device.hasObject(By.text("动态")))
                    device.wait(Until.findObject(By.desc("播放歌单 模拟收藏夹")), 10000)!!.click()
                    assertTrue("Favorite playlist did not replace the queue and start its first song", waitFor { !Graph.player.music.status.value.loadingPlaylist && Graph.player.music.queue.value.tracks.size == 2 && Graph.player.state.value.detail?.video?.bvid == "BV156xx6wEff" && Graph.player.exo.isPlaying })
                    assertEquals(0, fixture.writeCount("/x/web-interface/coin/add"))
                } catch (e: Throwable) {
                    device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "music-failure.png")); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "music-failure.xml")); throw e
                } finally { scenario.onActivity { remote?.release(); Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
