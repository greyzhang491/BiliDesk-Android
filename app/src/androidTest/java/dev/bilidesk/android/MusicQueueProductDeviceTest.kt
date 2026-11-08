// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual UI, synthetic songs and local media; no user account writes. */
@RunWith(AndroidJUnit4::class)
class MusicQueueProductDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache()
            device.findObject(selector)?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing queue UI: $selector")
    }
    private fun click(description: String) { var n = node(By.desc(description)); while (!n.isClickable && n.parent != null) n = n.parent; n.click() }
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 16000
        while (SystemClock.elapsedRealtime() < end) { if (main { predicate() }) return; SystemClock.sleep(100) }; fail(message)
    }
    @Test fun queueFavoritesNextLocateAndDragWorkWithoutReplacingPlayingMedia() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true, socialData = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel; var playlist = ""; var media: Any? = null
                val a = MusicTrack(Video(bvid = "BV156xx6wEff", title = "山海之间", author = "夏日听风"), 101, "山海之间")
                val b = MusicTrack(Video(bvid = "BV17x411w7KC", title = "沿途的光", author = "晚风电台"), 102, "沿途的光")
                val songs = listOf(a, b) + (3..36).map { n -> MusicTrack(b.video, 1000L + n, "夜色来信 · ${n.toString().padStart(2, '0')}") }
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                    main { playlist = Graph.player.music.library.create("日常收藏", listOf(a)); Graph.player.music.playTracks(songs); Graph.player.music.mode(MusicMode.ONE); vm.openMusicPlayer() }
                    await("Music did not decode") { Graph.player.exo.isPlaying }
                    media = main { Graph.player.exo.currentMediaItem }
                    click("音乐播放队列"); node(By.desc("播放队列面板"))
                    click("收藏到歌单 沿途的光"); node(By.text("收藏到歌单"))
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "025-music-queue-save.png")); node(By.text("日常收藏")).click()
                    await("Unplayed song could not be saved") { Graph.player.music.library.state.value.playlists.first { it.id == playlist }.tracks.any { it.key == b.key } }
                    main { assertEquals(a.key, Graph.player.music.queue.value.currentKey); assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    click("歌曲选项 沿途的光"); node(By.text("下一首播放")).click()
                    await("Queue next did not prioritize the selected song") { Graph.player.music.queue.value.nextUp.firstOrNull() == b.key }
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "025-music-queue.png"))
                    val scroller = UiScrollable(UiSelector().description("歌曲队列列表").scrollable(true))
                    scroller.scrollToEnd(8); click("定位当前歌曲"); node(By.desc("歌曲选项 山海之间"))
                    val handle = node(By.desc("拖动排序 山海之间")).visibleBounds
                    val viewport = node(By.desc("歌曲队列列表")).visibleBounds
                    val from = Point(handle.centerX(), handle.centerY()); val to = Point(from.x, viewport.bottom - 20)
                    val down = SystemClock.uptimeMillis()
                    fun event(action: Int, x: Int, y: Int) {
                        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
                    }
                    event(MotionEvent.ACTION_DOWN, from.x, from.y)
                    try {
                        SystemClock.sleep(650)
                        for (step in 1..30) { event(MotionEvent.ACTION_MOVE, from.x, from.y + (to.y - from.y) * step / 30); SystemClock.sleep(16) }
                        SystemClock.sleep(1200) // Hold at the edge to exercise automatic scrolling.
                        main { assertEquals("Drag persisted before drop", songs.map { it.key }, Graph.player.music.queue.value.tracks.map { it.key }) }
                    } finally { event(MotionEvent.ACTION_UP, to.x, to.y) }
                    await("Drag/drop did not reorder across the scrolling edge") { Graph.player.music.queue.value.tracks.indexOfFirst { it.key == a.key } > 2 }
                    main {
                        val q = Graph.player.music.queue.value
                        assertEquals(a.key, q.currentKey); assertEquals(b.key, q.nextUp.first())
                        assertSame("Reorder reloaded the playing media", media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying)
                        val saved = MusicQueueState.from(JSONObject(context.getSharedPreferences("fixture_music", 0).getString("queue", "{}")!!))
                        assertEquals(q.tracks.map { it.key }, saved.tracks.map { it.key })
                    }
                    click("定位当前歌曲"); node(By.desc("歌曲选项 山海之间"))
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "025-music-queue-after-drag.png"))
                    device.pressBack(); device.setOrientationLeft(); node(By.desc("播放队列面板"))
                    device.takeScreenshot(File(context.getExternalFilesDir(null), "025-music-queue-wide.png"))
                } finally { main { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
