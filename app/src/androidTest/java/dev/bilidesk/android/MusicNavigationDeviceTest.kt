// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.Build
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
class MusicNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            var ok = false; instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            SystemClock.sleep(120)
        }
        return false
    }
    private fun refreshUi() { if (Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun text(label: String): UiObject2 {
        refreshUi()
        return requireNotNull(device.wait(Until.findObject(By.text(label)), 10000)) { "Missing text: $label" }
    }
    private fun button(label: String): UiObject2 {
        refreshUi()
        return requireNotNull(device.wait(Until.findObject(By.desc(label)), 10000)) { "Missing button: $label" }
    }
    private fun musicTab() {
        text("发现")
        // The partition page also has a music category; the bottom-most label is the app tab.
        device.findObjects(By.text("音乐")).maxBy { it.visibleBounds.centerY() }.click()
    }
    private fun assertNavigation(visible: Boolean) {
        refreshUi()
        for (label in listOf("发现", "短视频", "分区", "我的")) {
            assertTrue("Main navigation visibility for $label should be $visible", device.wait(if (visible) Until.hasObject(By.text(label)) else Until.gone(By.text(label)), 10000))
        }
    }
    private fun shot(name: String) { device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "$name-0.1.9.png")) }

    @Test fun musicHomeHidesPhoneTabsAndBothBackPathsRestorePreviousTabWithoutStoppingAudio() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        val tablet = instrumentation.targetContext.resources.configuration.smallestScreenWidthDp >= 600
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var vm: AppViewModel? = null
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm!!.root("home") }
                    text("分区").click()
                    assertTrue(waitFor { vm!!.route.value.name == "explore" })
                    musicTab()
                    assertTrue(waitFor { vm!!.route.value.name == "music" })
                    device.wait(Until.findObject(By.text("Got it")), 1500)?.click()
                    if (!tablet) button("收起音乐播放器")
                    assertNavigation(tablet)
                    scenario.onActivity {
                        Graph.player.music.playAll(listOf(Video(bvid = "BV156xx6wEff", title = "交互测试样片 A")))
                        Graph.player.music.mode(MusicMode.ONE)
                    }
                    assertTrue("Music did not decode", waitFor { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    var media: Any? = null
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    shot("music-home-portrait")

                    button("搜索歌曲").click()
                    assertTrue(waitFor { vm!!.route.value.name == "music_search" })
                    button("暂停音乐")
                    assertNavigation(false)
                    shot("music-home-search")
                    device.pressBack()
                    assertTrue(waitFor { vm!!.route.value.name == "music" })
                    if (!tablet) button("收起音乐播放器")
                    assertNavigation(tablet)
                    button("我的歌单").click()
                    assertTrue(waitFor { vm!!.route.value.name == "music_library" })
                    button("暂停音乐")
                    device.pressBack()
                    assertTrue(waitFor { vm!!.route.value.name == "music" })
                    if (!tablet) button("收起音乐播放器")
                    assertNavigation(tablet)
                    scenario.onActivity { vm!!.notices.tryEmit("音乐界面通知测试") }
                    text("音乐界面通知测试")

                    device.setOrientationLeft()
                    assertTrue(waitFor { device.displayWidth > device.displayHeight })
                    assertNavigation(true)
                    if (tablet) {
                        val cover = button("音乐唱片，点击切换歌词").visibleBounds
                        val folder = text("模拟收藏夹").visibleBounds
                        assertTrue("Tablet split layout changed", cover.left > folder.right)
                    }
                    shot("music-home-landscape")
                    device.setOrientationNatural()
                    assertTrue(waitFor { device.displayWidth < device.displayHeight })
                    if (!tablet) button("收起音乐播放器")
                    assertNavigation(tablet)
                    if (tablet) text("分区").click() else button("收起音乐播放器").click()
                    assertTrue("Collapsing music did not restore the partition tab", waitFor { vm!!.route.value.name == "explore" })
                    assertNavigation(true)
                    button("暂停音乐")
                    assertTrue("Collapsing music stopped playback", waitFor { Graph.player.exo.isPlaying })
                    scenario.onActivity { assertSame("Navigation replaced the playing media", media, Graph.player.exo.currentMediaItem) }
                    shot("music-home-return")

                    if (!tablet) {
                        musicTab(); button("收起音乐播放器"); assertNavigation(false)
                        device.pressBack()
                        assertTrue("System Back did not restore the previous tab", waitFor { vm!!.route.value.name == "explore" })
                        assertNavigation(true)
                        text("我的").click()
                        assertTrue(waitFor { vm!!.route.value.name == "library" })
                        musicTab(); button("收起音乐播放器")
                        device.pressBack()
                        assertTrue("Music remembered an older tab instead of the last one", waitFor { vm!!.route.value.name == "library" })
                        assertNavigation(true)
                    }
                    assertTrue(waitFor { Graph.player.exo.isPlaying })
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem) }
                } catch (failure: Throwable) {
                    shot("music-navigation-failure"); device.dumpWindowHierarchy(File(instrumentation.targetContext.getExternalFilesDir(null), "music-navigation-failure.xml")); throw failure
                } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
