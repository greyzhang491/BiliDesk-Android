// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Every account read/write is handled by the offline fixture, never a real account. */
@RunWith(AndroidJUnit4::class)
class SocialMusicDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return; SystemClock.sleep(120) }
        fail(message)
    }
    private fun freshTree() { if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) { freshTree(); device.findObject(selector)?.let { return it }; SystemClock.sleep(150) }
        error("Missing UI element: $selector")
    }
    private fun text(value: String) = node(By.text(value))
    private fun clickDescription(value: String) {
        var node = node(By.desc(value))
        while (!node.isClickable && node.parent != null) node = node.parent
        node.click()
    }
    private fun scrollDetailsTo(value: String) {
        repeat(8) {
            freshTree()
            if (device.hasObject(By.text(value))) return
            device.swipe(device.displayWidth / 2, (device.displayHeight * .86f).toInt(), device.displayWidth / 2, (device.displayHeight * .54f).toInt(), 25)
            SystemClock.sleep(500)
        }
        error("Details did not reveal $value")
    }
    private fun assertTransportSymmetry() {
        freshTree()
        val mode = device.wait(Until.findObject(By.desc("播放模式 单曲循环")), 10000)!!.visibleBounds
        val star = device.findObject(By.desc("收藏歌曲"))!!.visibleBounds
        val queue = device.findObject(By.desc("音乐播放队列"))!!.visibleBounds
        val progress = device.findObject(By.desc("音乐进度"))!!.visibleBounds
        assertTrue("Favorite star is off center", kotlin.math.abs(star.centerX() - progress.centerX()) <= 3)
        assertTrue("Bottom controls are not equally spaced", kotlin.math.abs((star.centerX() - mode.centerX()) - (queue.centerX() - star.centerX())) <= 3)
        assertTrue("Bottom icons do not share a baseline", listOf(mode.centerY(), star.centerY(), queue.centerY()).let { it.max() - it.min() } <= 2)
    }
    private fun shot(name: String) { device.takeScreenshot(File(context.getExternalFilesDir(null), "$name-0.1.8.png")) }
    private fun dump(failure: Throwable): Nothing { shot("social-music-failure"); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "social-music-failure.xml")); throw failure }

    @Test fun followingCommentsThreadsOrdersAndFavoriteCreationUseRealUi() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, socialData = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.openVideo(Video(bvid = "BV156xx6wEff")) }
                    await("Normal player did not start") { Graph.player.exo.isPlaying && vm.user.value != null }
                    device.wait(Until.findObject(By.text("Got it")), 1000)?.click()
                    scrollDetailsTo("已关注")
                    println("Follow checkpoint: initial relationship visible")
                    text("已关注").click()
                    text("关注")
                    println("Follow checkpoint: unfollow visible, writes=${fixture.writeCount("/x/relation/modify")}")
                    text("关注").click()
                    text("已关注")
                    println("Follow checkpoint: follow visible, writes=${fixture.writeCount("/x/relation/modify")}")
                    assertEquals(2, fixture.writeCount("/x/relation/modify"))
                    assertEquals("1", fixture.writeForms["/x/relation/modify"]!!["act"])
                    var media: Any? = null; scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    text("评论").click(); text("浏览评论时视频应持续播放")
                    clickDescription("点赞评论 700"); clickDescription("取消评论点赞 700")
                    await("Unlike was not sent") { fixture.writeCount("/x/v2/reply/action") == 2 }
                    assertEquals("0", fixture.writeForms["/x/v2/reply/action"]!!["action"])
                    text("查看 1 条回复").click(); text("楼中楼也不中断播放")
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    clickDescription("返回上一级评论"); text("按最热").click(); text("最新").click(); text("最新的一条评论")
                    text("按最新").click(); text("最早").click(); text("最早的一条评论")
                    assertTrue(fixture.readRequests.any { it.contains("/x/v2/reply?") && it.contains("pn=3") && it.contains("sort=0") })
                    shot("comments-oldest")
                    text("简介").click(); freshTree()
                    val detailsScroll = UiScrollable(UiSelector().scrollable(true))
                    if (detailsScroll.exists()) detailsScroll.scrollToBeginning(10)
                    clickDescription("收藏"); text("新建收藏夹").click()
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "测试新收藏夹"
                    text("创建").click(); text("测试新收藏夹")
                    assertEquals(1, fixture.writeCount("/x/v3/fav/folder/add"))
                    assertEquals("1", fixture.writeForms["/x/v3/fav/folder/add"]!!["privacy"])
                    assertEquals("fixture-csrf-only", fixture.writeForms["/x/v3/fav/folder/add"]!!["csrf"])
                    shot("favorite-create")
                } catch (e: Throwable) { println("Simulated follow writes=${fixture.writeCount("/x/relation/modify")}, act=${fixture.writeForms["/x/relation/modify"]?.get("act")}"); dump(e) } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun batchPartsSavedPlaylistsSwitchAndPersistWithoutReplacingQueueOnSave() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true, socialData = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.music.play(Video(bvid = "BV156xx6wEff"), 101); Graph.player.music.mode(MusicMode.ONE); vm.root("music") }
                    await("Music did not start") { Graph.player.exo.isPlaying && Graph.player.state.value.music }
                    assertNotNull(device.wait(Until.findObject(By.desc("音乐唱片，点击切换歌词")), 10000))
                    assertFalse("Music landing should display the player", device.hasObject(By.text("B站收藏夹")))
                    val original = Graph.player.music.queue.value
                    clickDescription("当前歌曲选项"); text("合集 / 分P选曲").click(); text("第二首分P").click()
                    text("已选 2/3"); text("加入歌单").click(); text("新建歌单").click()
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "我的精选"
                    text("保存").click()
                    await("Batch selection was not durably saved") { Graph.player.music.library.state.value.playlists.singleOrNull()?.tracks?.size == 2 }
                    assertEquals(original, Graph.player.music.queue.value)
                    scenario.onActivity { assertTrue(Graph.player.exo.isPlaying) }
                    clickDescription("我的歌单"); text("我的精选")
                    text("新建歌单").click(); device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "夜晚歌单"; text("保存").click(); text("夜晚歌单")
                    clickDescription("歌单选项 夜晚歌单"); text("重命名").click(); device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "夜晚收藏"; text("保存").click(); text("夜晚收藏")
                    val restored = MusicLibrary(context, "fixture_music").state.value
                    assertEquals(2, restored.playlists.size); assertEquals(listOf(101L, 102L), restored.playlists.first().tracks.map { it.cid })
                    assertEquals("夜晚收藏", restored.playlists.last().name)
                    shot("saved-playlists")
                    clickDescription("播放歌单 我的精选")
                    await("Saved playlist did not become the queue") { Graph.player.music.queue.value.tracks.map { it.cid } == listOf(101L, 102L) && Graph.player.exo.isPlaying }
                    scenario.onActivity { Graph.player.music.next() }
                    await("Playlist next did not select its exact part") { Graph.player.currentEpisode()?.cid == 102L && Graph.player.exo.isPlaying }
                    clickDescription("我的歌单"); clickDescription("歌单选项 我的精选"); text("删除歌单").click(); text("删除").click()
                    await("Playlist deletion failed") { Graph.player.music.library.state.value.playlists.size == 1 }
                    assertEquals(2, Graph.player.music.queue.value.tracks.size)
                    scenario.onActivity { assertTrue(Graph.player.exo.isPlaying) }
                } catch (e: Throwable) { dump(e) } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun audioSourceDoesNotLoadVideoUntilRequestedAndAppearanceIsSaved() {
        Configurator.getInstance().waitForIdleTimeout = 100
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        if (tablet) device.setOrientationLeft() else device.setOrientationNatural()
        val previous = Graph.store.settings.value
        InteractionFixture(instrumentation, musicMedia = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    val loads = CopyOnWriteArrayList<String>()
                    scenario.onActivity {
                        Graph.store.update(previous.copy(music = MusicPreferences(), cdnMode = 0))
                        Graph.player.exo.addAnalyticsListener(object: AnalyticsListener { override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) { loads.add(loadEventInfo.uri.toString()) } })
                        val vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.music.play(Video(bvid = "BV17x411w7KC")); Graph.player.music.mode(MusicMode.ONE); vm.root("music")
                    }
                    await("Independent audio stream did not decode") { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 15 }
                    assertTrue(loads.any { it.contains("fixture-audio") }); assertFalse(loads.any { it.contains("fixture-video") })
                    assertFalse("Music performed a video CDN probe", fixture.readRequests.any { it.contains("unwanted-video-probe") })
                    assertTransportSymmetry()
                    var media: Any? = null; scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    var audioDecoder: Any? = null; scenario.onActivity { audioDecoder = Graph.player.exo.audioDecoderCounters }
                    clickDescription("音乐唱片，点击切换歌词")
                    node(By.desc("同步滚动歌词")); text("暂时没有可用歌词")
                    scenario.onActivity { assertFalse(Graph.player.state.value.musicVisual); assertNull(Graph.player.musicPicture.player) }
                    clickDescription("显示原视频")
                    await("Explicit picture did not decode video") { Graph.player.state.value.musicVisual && Graph.player.musicPicture.state.value.ready && (Graph.player.musicPicture.player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 }
                    assertFalse("Picture switching reloaded the primary audio source", loads.any { it.contains("fixture-video") })
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertSame("Picture switching recreated the audio decoder", audioDecoder, Graph.player.exo.audioDecoderCounters); assertTrue(Graph.player.exo.isPlaying) }
                    val pictureBounds = node(By.desc("音乐原视频画面")).visibleBounds
                    val selectorBounds = node(By.desc("显示原视频")).visibleBounds
                    assertTrue("Original picture overlaps the face selector", pictureBounds.bottom <= selectorBounds.top + 2)
                    assertTrue("Original picture covers the header", pictureBounds.top >= node(By.desc("音乐播放器设置")).visibleBounds.bottom - 2)
                    shot("music-original-video")
                    clickDescription("显示歌词"); node(By.desc("同步滚动歌词"))
                    await("Returning to lyrics retained the picture decoder",) { Graph.player.musicPicture.player == null && Graph.player.exo.isPlaying }
                    clickDescription("显示原视频")
                    await("Picture could not reopen") { Graph.player.musicPicture.state.value.ready }
                    val beforeBackground = loads.size
                    device.pressHome()
                    assertTrue(device.wait(Until.hasObject(By.pkg("com.android.launcher3")), 5000))
                    await("Background music retained the video source") { !Graph.player.state.value.musicVisual && Graph.player.musicPicture.player == null && Graph.player.exo.isPlaying }
                    var buffers = 0
                    scenario.onActivity { buffers = Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0 }
                    await("Background audio stopped after removing the picture") { (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > buffers + 15 }
                    assertFalse("Background listening reloaded video", loads.drop(beforeBackground).any { it.contains("fixture-video") })
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    context.startActivity(launch)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    node(By.desc("音乐唱片，点击切换歌词"))
                    clickDescription("音乐播放器设置"); text("沉浸封面").click()
                    await("Style was not applied") { Graph.store.settings.value.music.style == MusicStyle.IMMERSIVE }
                    device.pressBack(); assertTransportSymmetry(); shot("music-immersive")
                    clickDescription("音乐播放器设置"); text("简约").click(); device.pressBack(); assertTransportSymmetry(); shot("music-minimal")
                    assertEquals(MusicStyle.MINIMAL, Store(context).settings.value.music.style)
                    assertFalse("Changing style requested video", fixture.readRequests.any { it.contains("unwanted-video-probe") })
                    scenario.onActivity { assertTrue(Graph.player.exo.isPlaying) }
                } catch (e: Throwable) { dump(e) } finally { scenario.onActivity { Graph.player.stop(); Graph.store.update(previous) }; device.unfreezeRotation() }
            }
        }
    }
}
