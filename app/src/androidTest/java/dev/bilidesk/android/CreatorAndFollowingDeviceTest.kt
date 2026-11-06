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
import dev.bilidesk.android.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CreatorAndFollowingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; instrumentation.runOnMainSync { ready = test() }
            if (ready) return
            SystemClock.sleep(120)
        }
        fail(message)
    }
    private fun node(selector: BySelector): UiObject2 = device.wait(Until.findObject(selector), 12000) ?: error("Missing $selector")
    private fun click(selector: BySelector) { var item = node(selector); while (!item.isClickable && item.parent != null) item = item.parent; item.click() }
    private fun reveal(selector: BySelector, region: String): UiObject2 {
        repeat(15) {
            device.findObject(selector)?.let { if (it.visibleBounds.height() > 5) return it }
            val bounds = node(By.desc(region)).visibleBounds
            device.swipe(bounds.centerX(), bounds.bottom - 25, bounds.centerX(), bounds.top + 25, 22)
            SystemClock.sleep(200)
        }
        error("Not visible: $selector")
    }
    private fun shot(name: String) {
        val type = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
        device.takeScreenshot(File(context.getExternalFilesDir(null), "$name-$type-0.2.2.png"))
    }
    @Test fun videoAuthorShowsAvatarFansAndSearchableProfileWithoutRestartingPlayback() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        val settings = Graph.store.settings.value
        InteractionFixture(instrumentation, loggedIn = true, creatorData = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel
                    scenario.onActivity { Graph.store.update(settings.copy(danmaku = false)); vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.openVideo(Video(bvid = "BV156xx6wEff")) }
                    await("Video never started") { Graph.player.exo.isPlaying && vm.user.value != null }
                    reveal(By.text("1280 粉丝"), "视频详情区域")
                    assertTrue(Graph.player.state.value.detail!!.video.authorAvatar.startsWith("file:"))
                    shot("author-card")
                    var media: Any? = null; var position = 0L
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem; position = Graph.player.exo.currentPosition }
                    click(By.desc("查看夏日听风的主页"))
                    node(By.text("用户主页")); node(By.text("搜索这位 UP 主的投稿"))
                    SystemClock.sleep(700)
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.currentPosition > position) }
                    shot("creator-profile")
                    click(By.desc("关闭用户主页"))
                    scenario.onActivity { assertEquals("player", vm.route.value.name); assertSame(media, Graph.player.exo.currentMediaItem) }
                } finally { scenario.onActivity { Graph.store.update(settings); Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
    @Test fun shortCreatorSearchAndLocationKeepPlaybackAndCreatorScopedQueue() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        val settings = Graph.store.settings.value
        InteractionFixture(instrumentation, loggedIn = true, creatorData = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel
                    scenario.onActivity { Graph.store.update(settings.copy(danmaku = false)); vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.openShorts(Video(bvid = "BV156xx6wEff")) }
                    await("Short never started") { Graph.player.exo.isPlaying && vm.user.value != null }
                    click(By.desc("打开作者主页")); node(By.text("用户主页"))
                    var before = 0L; scenario.onActivity { before = Graph.player.exo.currentPosition }
                    node(By.clazz("android.widget.EditText")).text = "1"
                    click(By.desc("搜索这位UP主的投稿"))
                    reveal(By.text("夏日听风的投稿 1"), "下拉刷新视频列表")
                    scenario.onActivity { assertTrue(Graph.player.exo.currentPosition > before) }
                    assertTrue(fixture.readRequests.any { it.contains("/x/space/wbi/arc/search") && it.contains("mid=90") && it.contains("keyword=1") })
                    click(By.text("夏日听风的投稿 1"))
                    await("Creator video switched out of short mode") { vm.route.value.name == "shorts" && Graph.player.state.value.detail?.video?.bvid == "BV17x411w7KC" && Graph.player.exo.isPlaying }
                    scenario.onActivity { assertEquals(listOf("BV17x411w7KC"), vm.shortSession.pager.state.value.videos.map { it.bvid }) }
                    click(By.desc("打开作者主页")); click(By.text("定位当前视频"))
                    node(By.text("当前播放")); shot("creator-current-location")
                    click(By.desc("关闭用户主页"))
                    scenario.onActivity { assertEquals("shorts", vm.route.value.name); assertTrue(Graph.player.exo.isPlaying) }
                    // The previous scoped queue has one item; choosing index 1 in a new
                    // two-item queue must not reuse its previous composition's feed state.
                    click(By.desc("打开作者主页"))
                    reveal(By.text("夏日听风的投稿 2"), "下拉刷新视频列表")
                    click(By.text("夏日听风的投稿 2"))
                    await("Choosing the second upload played a different item") { vm.route.value.name == "shorts" && Graph.player.state.value.detail?.video?.bvid == "BV156xx6wEff" && Graph.player.exo.isPlaying }
                    scenario.onActivity { assertEquals(1, vm.shortSession.index) }
                } finally { scenario.onActivity { Graph.store.update(settings); Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
    @Test fun myFollowingEntryOpensProfilesAndUpdatesAfterUnfollowing() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, creatorData = true, musicMedia = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.root("library") }
                try {
                    await("Fixture account not ready") { vm.user.value != null }
                    click(By.text("我的关注")); node(By.text("我的关注 · 2")); node(By.text("夏日听风")); node(By.text("小行星电台"))
                    shot("my-following")
                    click(By.text("夏日听风")); node(By.text("用户主页")); node(By.text("搜索这位 UP 主的投稿"))
                    click(By.text("已关注")); node(By.text("关注"))
                    assertEquals(1, fixture.writeCount("/x/relation/modify"))
                    click(By.desc("关闭用户主页")); node(By.text("我的关注 · 1"))
                    assertFalse(device.hasObject(By.text("夏日听风")))
                    assertTrue(device.hasObject(By.text("小行星电台")))
                    device.pressBack(); node(By.text("我的关注"))
                    scenario.onActivity { vm.playMusic(Video(bvid = "BV156xx6wEff")) }
                    await("Background song did not start") { Graph.player.state.value.music && Graph.player.exo.isPlaying }
                    scenario.onActivity { vm.root("library") }
                    click(By.text("我的关注")); click(By.text("小行星电台"))
                    reveal(By.text("小行星电台的投稿 1"), "下拉刷新视频列表")
                    click(By.text("小行星电台的投稿 1"))
                    await("Following upload incorrectly inherited background music mode") { vm.route.value.name == "player" && !Graph.player.state.value.music && Graph.player.exo.isPlaying }
                } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
