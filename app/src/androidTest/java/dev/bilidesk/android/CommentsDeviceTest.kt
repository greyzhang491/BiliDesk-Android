// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.data.withReaction
import dev.bilidesk.android.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real UI and decoder, isolated API fixtures; never performs a real account write. */
@RunWith(AndroidJUnit4::class)
class CommentsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
    private fun fresh() { if (Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun await(message: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { var result = false; instrumentation.runOnMainSync { result = block() }; if (result) return; SystemClock.sleep(120) }
        fail(message)
    }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) { fresh(); device.findObject(selector)?.let { return it }; SystemClock.sleep(150) }
        error("Missing $selector")
    }
    private fun text(value: String) = node(By.text(value))
    private fun clickDescription(value: String) { var current = node(By.desc(value)); while (!current.isClickable && current.parent != null) current = current.parent; current.click() }
    private fun scrollTo(selector: BySelector, thread: Boolean = false): UiObject2 {
        repeat(22) {
            fresh(); device.findObject(selector)?.let { if (it.visibleBounds.height() > 5) return it }
            val bounds = node(By.desc(if (thread) "楼中楼回复列表" else "视频评论列表")).visibleBounds
            device.swipe(bounds.centerX(), bounds.bottom - 28, bounds.centerX(), bounds.top + 28, 25); SystemClock.sleep(300)
        }
        error("Cannot scroll to $selector")
    }
    private fun toTop(thread: Boolean = false) {
        val bounds = node(By.desc(if (thread) "楼中楼回复列表" else "视频评论列表")).visibleBounds
        repeat(8) { device.swipe(bounds.centerX(), bounds.top + 25, bounds.centerX(), bounds.bottom - 25, 15) }
        SystemClock.sleep(300)
    }
    private fun shot(name: String) { SystemClock.sleep(300); device.takeScreenshot(File(context.getExternalFilesDir(null), "$name-${if (tablet) "tablet" else "phone"}-0.2.1.png")) }
    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) { scenario.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }; SystemClock.sleep(250) }
    private fun clickTextLink(marker: String, link: String) {
        val visible = node(By.textContains(marker)).visibleBounds
        val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(instrumentation.uiAutomation.rootInActiveWindow)
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (candidate.text?.toString()?.let { it.contains(marker) && it.contains(link) } == true) { found = candidate; break }
            for (index in 0 until candidate.childCount) candidate.getChild(index)?.let { pending.add(it) }
        }
        val accessible = requireNotNull(found) { "Comment text is missing from the accessibility tree" }
        val offset = accessible.text.toString().indexOf(link) + link.length / 2
        val extras = android.os.Bundle().apply {
            putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, offset)
            putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
        }
        assertTrue("Text character coordinates unavailable", accessible.refreshWithExtraData(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, extras))
        @Suppress("DEPRECATION") val rect = accessible.extras.getParcelableArray(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)?.firstOrNull() as? android.graphics.RectF
        assertNotNull("Text link has no visible character box", rect)
        assertTrue("Link is outside the viewport", visible.contains(rect!!.centerX().toInt(), rect.centerY().toInt()))
        device.click(rect.centerX().toInt(), rect.centerY().toInt())
    }
    private fun failDump(error: Throwable): Nothing { shot("comments-failure"); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "comments-failure.xml")); throw error }

    @Test fun avatarsMetadataReactionsRepliesProfileAndScrollRestoration() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        val original = Graph.store.settings.value
        InteractionFixture(instrumentation, loggedIn = true, richComments = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel; var media: Any? = null
                    scenario.onActivity { Graph.store.update(original.copy(theme = 2, danmaku = false)); vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.exo.repeatMode = Player.REPEAT_MODE_ONE; vm.openVideo(Video(bvid = "BV156xx6wEff")) }
                    await("Player and account did not become ready") { Graph.player.exo.isPlaying && vm.user.value != null }
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    device.wait(Until.findObject(By.text("Got it")), 700)?.click(); text("评论").click()
                    text("夏日听风"); text("LV6"); text("IP属地：广西"); node(By.desc("查看夏日听风的主页")); shot("comments-light")
                    clickDescription("点赞评论 700"); node(By.desc("取消评论点赞 700")); clickDescription("点踩评论 700")
                    await("Dislike endpoint not called") { fixture.writeCount("/x/v2/reply/hate") == 1 }
                    assertEquals("1", fixture.writeForms["/x/v2/reply/hate"]!!["action"])
                    val readBack = kotlinx.coroutines.runBlocking { Graph.api.commentPage(100001).comments.first { it.id == 700L } }
                    assertTrue("Server reaction was not parsed", readBack.disliked)
                    assertTrue("Android reaction transition failed", readBack.withReaction(true, true).withReaction(false, true).disliked)
                    node(By.desc("取消评论点踩 700")); node(By.desc("点赞评论 700"))
                    clickDescription("取消评论点踩 700")
                    await("Dislike removal endpoint not called") { fixture.writeCount("/x/v2/reply/hate") == 2 }
                    node(By.desc("点踩评论 700"))
                    assertEquals(1, fixture.writeCount("/x/v2/reply/action")); assertEquals(2, fixture.writeCount("/x/v2/reply/hate"))
                    clickDescription("查看夏日听风的主页"); text("用户主页"); text("投稿视频"); text("1280 粉丝")
                    text("夏日听风的投稿 1"); shot("commenter-profile")
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    clickDescription("关闭用户主页")
                    val initialY = scrollTo(By.text("查看 38 条回复")).visibleBounds.top
                    val bounds = node(By.desc("视频评论列表")).visibleBounds
                    val distance = (60 * context.resources.displayMetrics.density).toInt()
                    device.swipe(bounds.centerX(), bounds.bottom - 30, bounds.centerX(), bounds.bottom - 30 - distance, 25); SystemClock.sleep(300)
                    val link = text("查看 38 条回复"); val beforeY = link.visibleBounds.top
                    assertTrue("Restoration test never left the top", initialY - beforeY > 20)
                    link.click(); text("回复详情"); scrollTo(By.desc("回复评论 701"), true).let { clickDescription("回复评论 701") }
                    text("回复 小行星电台")
                    node(By.clazz("android.widget.EditText")).text = "离线测试：回复指定楼中楼"
                    clickDescription("发布评论"); await("Reply was not sent") { fixture.writeCount("/x/v2/reply/add") == 1 }
                    val form = fixture.writeForms["/x/v2/reply/add"]!!
                    assertEquals("700", form["root"]); assertEquals("701", form["parent"]); assertEquals("fixture-csrf-only", form["csrf"])
                    hideKeyboard(scenario); text("回复详情"); shot("comment-thread")
                    clickDescription("返回上一级评论"); val after = scrollTo(By.text("查看 38 条回复")); assertTrue("Root list jumped after returning", kotlin.math.abs(beforeY - after.visibleBounds.top) < 12)
                    assertFalse("Redundant return text remains", device.hasObject(By.text("返回评论")))
                    scrollTo(By.desc("更多评论操作 750")).let { clickDescription("更多评论操作 750") }; text("复制评论").click()
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    toTop(); scenario.onActivity { Graph.store.update(Graph.store.settings.value.copy(theme = 1)) }; text("夏日听风"); shot("comments-dark")
                    // Compose text links are spans, rather than individual TextView nodes.
                    clickTextLink("一边听歌一边看评论", "@小行星电台"); text("用户主页"); text("小行星电台的投稿 1"); clickDescription("关闭用户主页")
                    clickTextLink("山间慢慢走：回复", "@小行星电台"); text("小行星电台的投稿 1"); clickDescription("关闭用户主页"); text("按最热")
                    scrollTo(By.text("自动加载的后续评论 756")); assertTrue(fixture.readRequests.any { it.contains("/x/v2/reply/main?") && it.contains("next=1") })
                    // The mention with no UID is resolved by an explicit public user search.
                    toTop(); scrollTo(By.textContains("@没有UID的用户")); clickTextLink("回复 @没有UID的用户", "@没有UID的用户"); text("山间慢慢走的投稿 1")
                    assertTrue(fixture.readRequests.any { it.contains("search_type=bili_user") }); clickDescription("关闭用户主页")
                    toTop(); scrollTo(By.desc("评论图片 1")).let { clickDescription("评论图片 1") }; node(By.desc("评论原图 1")); shot("comment-picture"); clickDescription("关闭评论图片")
                    toTop(); clickDescription("查看夏日听风的主页"); text("夏日听风的投稿 1").click()
                    await("Profile upload did not open") { Graph.player.state.value.detail?.video?.bvid == "BV17x411w7KC" && Graph.player.exo.isPlaying }
                } catch (error: Throwable) { println("Simulated reaction writes: up=${fixture.writeCount("/x/v2/reply/action")}, down=${fixture.writeCount("/x/v2/reply/hate")}; posting=${fixture.writeCount("/x/v2/reply/add")}"); failDump(error) } finally { scenario.onActivity { Graph.player.stop(); Graph.store.update(original) }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun shortCommentsProfilesAndThreadKeepPlaybackInBothOrientations() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, richComments = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel; var media: Any? = null
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.exo.repeatMode = Player.REPEAT_MODE_ONE; vm.openShorts(Video(bvid = "BV156xx6wEff")) }
                    await("Short video did not play") { Graph.player.exo.isPlaying && vm.user.value != null }; scenario.onActivity { media = Graph.player.exo.currentMediaItem }
                    clickDescription("打开评论"); text("夏日听风"); shot("short-comments")
                    clickDescription("查看夏日听风的主页"); text("夏日听风的投稿 1"); device.pressBack()
                    text("夏日听风"); scrollTo(By.text("查看 38 条回复")).click(); text("回复详情"); device.pressBack(); text("按最热")
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    device.setOrientationLeft(); node(By.desc("视频评论列表")); shot("short-comments-wide")
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    clickDescription("关闭播放器设置")
                } catch (error: Throwable) { failDump(error) } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun failuresKeepDraftAndRequireExplicitRetryWithoutDuplicateWrites() {
        Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural()
        InteractionFixture(instrumentation, loggedIn = true, richComments = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    lateinit var vm: AppViewModel
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.exo.repeatMode = Player.REPEAT_MODE_ONE; vm.openVideo(Video(bvid = "BV156xx6wEff")) }
                    await("Player did not play") { Graph.player.exo.isPlaying && vm.user.value != null }
                    fixture.failNextCommentRead = true; text("评论").click(); text("重试加载评论")
                    val failedReads = fixture.readRequests.count { it.contains("/x/v2/reply/main?") }; SystemClock.sleep(900)
                    assertEquals(failedReads, fixture.readRequests.count { it.contains("/x/v2/reply/main?") })
                    text("重试加载评论").click(); text("夏日听风")
                    fixture.loseNextCommentWriteResponse = true
                    node(By.clazz("android.widget.EditText")).text = "结果未知时保留的草稿"
                    clickDescription("发布评论"); node(By.desc("清空未确认的评论草稿")); assertEquals(1, fixture.writeCount("/x/v2/reply/add"))
                    assertEquals("结果未知时保留的草稿", node(By.clazz("android.widget.EditText")).text)
                    fresh(); assertFalse("Unknown write can be sent again", device.hasObject(By.desc("发布评论")))
                    hideKeyboard(scenario); clickDescription("刷新评论"); text("夏日听风"); assertEquals(1, fixture.writeCount("/x/v2/reply/add"))
                    shot("comment-error-draft"); clickDescription("清空未确认的评论草稿")
                } catch (error: Throwable) { failDump(error) } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
