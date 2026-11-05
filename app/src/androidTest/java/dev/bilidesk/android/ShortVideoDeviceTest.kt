// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import android.view.MotionEvent
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.ui.AppViewModel
import dev.bilidesk.android.ui.PlayerViewHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShortVideoDeviceTest {
    private var fixture: InteractionFixture? = null
    @After fun releaseFixture() { fixture?.close(); fixture = null }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(timeout: Long = 12000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var result = false; instrumentation.runOnMainSync { result = condition() }
            if (result) return true
            SystemClock.sleep(120)
        }
        return false
    }
    private fun pointer(down: Long, action: Int, x: Float, y: Float) = instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0))
    @Test fun actualShortPlaybackSupportsLocksSeekingSwipesAndContinuousComments() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        val context = instrumentation.targetContext
        val original = Graph.store.settings.value
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val touchEvents = mutableListOf<String>()
        fixture = if (InstrumentationRegistry.getArguments().getString("playback_fixture") == "true") InteractionFixture(instrumentation) else null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: AppViewModel? = null; var native: PlayerView? = null
            var diagnostics: Player.Listener? = null
            fun visiblePlayer(minimum: Float = .8f): PlayerView? {
                val view = PlayerViewHost.currentView ?: return null
                val rect = android.graphics.Rect()
                return view.takeIf { it.player === Graph.player.exo && it.isShown && it.getGlobalVisibleRect(rect) && rect.width() >= device.displayWidth * minimum && rect.height() >= device.displayHeight * minimum }
            }
            fun playing(bvid: String) = waitFor(if (fixture != null) 25000 else 70000) {
                val view = visiblePlayer()
                val visible = android.graphics.Rect()
                Graph.player.state.value.detail?.video?.bvid == bvid && Graph.player.exo.isPlaying && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 &&
                    view?.getGlobalVisibleRect(visible) == true && visible.width() >= device.displayWidth * .9 && visible.height() >= device.displayHeight * .9
            }
            fun hideControls() { scenario.onActivity { native = visiblePlayer(); native!!.hideController() } }
            try {
                scenario.onActivity {
                    PlayerViewHost.touchObserver = { touchEvents.add(it) }
                    Graph.player.stop()
                    val engine = Graph.player
                    diagnostics = object: Player.Listener {
                        override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                            if (!value) touchEvents.add("PAUSE reason=$reason state=${engine.exo.playbackState} items=${engine.exo.mediaItemCount} plan=${engine.state.value.plan != null} caller=" + Thread.currentThread().stackTrace.take(32).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName })
                        }
                        override fun onPlaybackStateChanged(value: Int) { touchEvents.add("STATE=$value ready=${engine.exo.playWhenReady} items=${engine.exo.mediaItemCount} plan=${engine.state.value.plan != null}") }
                    }.also { engine.exo.addListener(it) }
                    Graph.store.update(original.copy(playbackSpeed = 1.25f, holdSpeed = 3f, holdSpeedEnabled = true, shortsAutoNext = false))
                    vm = ViewModelProvider(it)[AppViewModel::class.java]
                    vm!!.openShorts(Video(bvid = "BV156xx6wEff"), listOf(Video(bvid = "BV17x411w7KC")))
                }
                assertTrue("First short video did not decode", playing("BV156xx6wEff"))
                device.wait(Until.findObject(By.text("Got it")), 2000)?.click()
                assertTrue("Short mode truncated a long video", Graph.player.state.value.duration > 60000)
                scenario.onActivity { Graph.player.seek(10000) }
                assertTrue(waitFor { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 10000..16000 })
                hideControls()
                val sameSurface = native
                var before = 0L
                scenario.onActivity { before = Graph.player.exo.currentPosition }
                device.wait(Until.findObject(By.desc("打开评论")), 10000)!!.click()
                assertNotNull(device.wait(Until.findObject(By.text("浏览评论时视频应持续播放")), 10000))
                assertTrue("Comments covered the whole video instead of retaining a visible player", waitFor { native!!.height < device.displayHeight * .6 })
                assertTrue("Opening comments interrupted playback", waitFor { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition > before + 1200 })
                scenario.onActivity { assertSame("Comments replaced the playback surface", sameSurface, visiblePlayer(.1f)); assertTrue((Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0) }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "short-comments-playing.png"))
                device.wait(Until.findObject(By.desc("关闭播放器设置")), 8000)!!.click()
                assertTrue("Closing comments did not restore the video viewport", waitFor { native!!.height >= device.displayHeight * .9 })
                hideControls()
                var x = 0f; var y = 0f; var w = 0f; var h = 0f
                scenario.onActivity { val xy = IntArray(2); native!!.getLocationOnScreen(xy); w = native!!.width.toFloat(); h = native!!.height.toFloat(); x = xy[0] + w * .4f; y = xy[1] + h * .32f }
                fun lockOrUnlock(expectedAfterRelease: Float) {
                    val down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y)
                    try {
                        SystemClock.sleep(650)
                        assertTrue("Held acceleration missing", waitFor(2500) { Graph.player.exo.playbackParameters.speed == 3f })
                        pointer(down, MotionEvent.ACTION_MOVE, x, y + h * .18f); SystemClock.sleep(250)
                    } finally { pointer(down, MotionEvent.ACTION_UP, x, y + h * .18f) }
                    val correct = waitFor { Graph.player.exo.playbackParameters.speed == expectedAfterRelease }
                    var actual = 0f; scenario.onActivity { actual = Graph.player.exo.playbackParameters.speed }
                    assertTrue("Speed lock/unlock did not survive release: expected=$expectedAfterRelease actual=$actual", correct)
                    assertEquals("Held down drag switched video", "BV156xx6wEff", Graph.player.state.value.detail?.video?.bvid)
                }
                lockOrUnlock(3f); lockOrUnlock(1.25f)
                assertEquals(1.25f, Graph.store.settings.value.playbackSpeed, .001f)
                scenario.onActivity { Graph.player.seek(10000) }
                assertTrue(waitFor { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 9500..15000 })
                val down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y)
                try { assertTrue(waitFor(2500) { Graph.player.exo.playbackParameters.speed == 3f }); pointer(down, MotionEvent.ACTION_MOVE, x + w * .2f, y); SystemClock.sleep(200) }
                finally { pointer(down, MotionEvent.ACTION_UP, x + w * .2f, y) }
                assertTrue("Short held seek failed", waitFor(25000) { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 32000..45000 })
                scenario.onActivity { assertEquals(1.25f, Graph.player.exo.playbackParameters.speed, .001f) }
                videoSwipe(instrumentation, (w * .4f).toInt(), (h * .85f).toInt(), (w * .4f).toInt(), (h * .15f).toInt())
                assertTrue("Ordinary upward swipe did not switch to second video", playing("BV17x411w7KC"))
                hideControls()
                SystemClock.sleep(400)
                videoSwipe(instrumentation, (w * .4f).toInt(), (h * .15f).toInt(), (w * .4f).toInt(), (h * .85f).toInt())
                assertTrue("Downward swipe did not return to previous video", playing("BV156xx6wEff"))
                scenario.onActivity { assertEquals(1.25f, Graph.player.exo.playbackParameters.speed, .001f) }
                // Closing the comments panel and paging must not leave an old surface attached.
                scenario.onActivity { native = visiblePlayer(); native!!.hideController() }
                device.click((w * .4f).toInt(), (h * .32f).toInt())
                assertTrue("Tap did not pause short playback", waitFor { !Graph.player.exo.playWhenReady })
                device.click((w * .4f).toInt(), (h * .32f).toInt())
                assertTrue("Tap did not resume short playback", waitFor { Graph.player.exo.isPlaying })
                scenario.onActivity { Graph.store.update(Graph.store.settings.value.copy(shortsAutoNext = true)); Graph.player.seek((Graph.player.exo.duration - 200).coerceAtLeast(0)) }
                assertTrue("Playback end did not advance to next video", playing("BV17x411w7KC"))
                var rotationSurface: PlayerView? = null; var rotationMedia: Any? = null
                scenario.onActivity { rotationSurface = visiblePlayer(); rotationMedia = Graph.player.exo.currentMediaItem }
                device.wait(Until.findObject(By.desc("切换横竖屏")), 10000)!!.click()
                assertTrue("Short mode did not enter landscape", waitFor { device.displayWidth > device.displayHeight })
                assertTrue("Landscape video surface was not ready for touch", playing("BV17x411w7KC"))
                assertTrue("Rotation replaced the short player or left short mode", waitFor { vm!!.route.value.name == "shorts" && Graph.player.exo.isPlaying && rotationSurface!!.width > rotationSurface!!.height })
                scenario.onActivity { assertSame(rotationSurface, visiblePlayer()); assertSame(rotationMedia, Graph.player.exo.currentMediaItem) }
                scenario.onActivity { rotationSurface!!.hideController() }
                assertTrue("Landscape must remove story actions and captions", device.wait(Until.gone(By.desc("短视频选项")), 5000))
                device.click(device.displayWidth * 2 / 5, device.displayHeight / 3)
                assertTrue("Landscape tap must reveal regular controls without pausing", waitFor { rotationSurface!!.isControllerFullyVisible && Graph.player.exo.isPlaying })
                assertFalse("Story actions must stay absent when landscape controls are visible", device.hasObject(By.desc("点赞视频")))
                assertFalse(device.hasObject(By.desc("分享视频")))
                assertFalse(device.hasObject(By.desc("切换横竖屏")))
                assertTrue("Landscape controls must auto-hide", waitFor(9000) { !rotationSurface!!.isControllerFullyVisible })
                device.takeScreenshot(File(context.getExternalFilesDir(null), "short-landscape-clean.png"))
                scenario.onActivity { it.window.attributes = it.window.attributes.apply { screenBrightness = .5f } }
                val screenW = device.displayWidth; val screenH = device.displayHeight
                videoSwipe(instrumentation, screenW / 10, screenH * 2 / 5, screenW / 10, screenH / 8)
                scenario.onActivity { assertTrue("Short landscape left edge did not raise brightness: ${it.window.attributes.screenBrightness}", it.window.attributes.screenBrightness > .75f) }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                videoSwipe(instrumentation, screenW * 9 / 10, screenH * 2 / 5, screenW * 9 / 10, screenH / 10)
                assertTrue("Short landscape right edge did not raise volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) > volume)
                device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "short-before-center.xml"))
                videoSwipe(instrumentation, screenW * 2 / 5, screenH * 15 / 100, screenW * 2 / 5, screenH * 85 / 100)
                assertTrue("Landscape center swipe did not return to previous short", playing("BV156xx6wEff"))
                videoSwipe(instrumentation, screenW * 2 / 5, screenH * 85 / 100, screenW * 2 / 5, screenH * 15 / 100)
                assertTrue("Landscape center swipe did not advance short", playing("BV17x411w7KC"))
                scenario.onActivity { rotationSurface = visiblePlayer() }
                scenario.onActivity { rotationSurface!!.showController() }
                device.wait(Until.findObject(By.desc("播放设置")), 10000)!!.click()
                device.wait(Until.findObject(By.text("视频互动与连播")), 10000)!!.click()
                device.wait(Until.findObject(By.text("评论")), 10000)!!.click()
                assertTrue("Landscape comments must leave a visible video pane", waitFor { rotationSurface!!.width < device.displayWidth * .85 && Graph.player.exo.isPlaying })
                device.wait(Until.findObject(By.desc("关闭播放器设置")), 8000)!!.click()
                scenario.onActivity { rotationSurface!!.showController() }
                device.wait(Until.findObject(By.desc(context.getString(androidx.media3.ui.R.string.exo_controls_fullscreen_exit_description))), 10000)!!.click()
                assertTrue("Short mode did not return to portrait", waitFor { device.displayWidth < device.displayHeight && Graph.player.exo.isPlaying })
                var media: Any? = null; var position = 0L
                scenario.onActivity { media = Graph.player.exo.currentMediaItem; position = Graph.player.exo.currentPosition; vm!!.openPlayer() }
                assertTrue(device.wait(Until.hasObject(By.desc("视频详情区域")), 10000))
                scenario.onActivity { assertSame("Switching to ordinary mode reloaded media", media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.currentPosition >= position) }
            } catch (failure: Throwable) {
                scenario.onActivity {
                    val rect = android.graphics.Rect(); val playerView = PlayerViewHost.currentView
                    File(context.getExternalFilesDir(null), "short-touch-trace.txt").writeText(touchEvents.joinToString("\n") + "\nindex=${vm?.shortSession?.index} bvid=${Graph.player.state.value.detail?.video?.bvid} playing=${Graph.player.exo.isPlaying} loading=${Graph.player.state.value.loading} error=${Graph.player.state.value.error} position=${Graph.player.exo.currentPosition} speed=${Graph.player.exo.playbackParameters.speed} decoded=${Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount} view=${playerView?.width}x${playerView?.height} visible=${playerView?.getGlobalVisibleRect(rect)} rect=$rect")
                }
                device.takeScreenshot(File(context.getExternalFilesDir(null), "short-failure.png")); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "short-failure.xml")); throw failure
            } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0); scenario.onActivity { diagnostics?.let { Graph.player.exo.removeListener(it) }; PlayerViewHost.touchObserver = null; Graph.player.stop(); Graph.store.update(original) }; device.unfreezeRotation() }
        }
    }
}
