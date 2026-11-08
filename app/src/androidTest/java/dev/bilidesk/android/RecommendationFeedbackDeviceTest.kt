// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.AppViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecommendationFeedbackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) { fresh(); device.findObject(selector)?.let { return it }; SystemClock.sleep(120) }
        error("Missing: $selector")
    }
    private fun text(value: String) = node(By.text(value))
    private fun description(value: String) { var n = node(By.desc(value)); while (!n.isClickable && n.parent != null) n = n.parent; n.click() }
    private fun longPressVideo(title: String) { var n = text(title); while (!n.isLongClickable && n.parent != null) n = n.parent; assertTrue("Video cover does not expose a long press", n.isLongClickable); n.longClick(); text("不感兴趣") }
    private fun await(message: String, condition: () -> Boolean) { val end = SystemClock.elapsedRealtime() + 15000; while (SystemClock.elapsedRealtime() < end) { if (main { condition() }) return; SystemClock.sleep(100) }; fail(message) }
    private val path = "/x/web-interface/feedback/dislike"
    private fun start() { Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural(); main { Graph.store.recommendations.clear("9") } }

    @Test fun discoverLongPressAndShortMenuSendAccountFeedbackAndFilterLaterRecommendations() {
        start()
        InteractionFixture(instrumentation, loggedIn = true, recommendationData = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.root("home") }
                    await("Synthetic account not ready") { vm.user.value != null }
                    longPressVideo("交互测试样片 A"); text("内容不感兴趣").click()
                    await("Recommendation feedback was not persisted after success") { Graph.store.recommendations.rules("9").any { it.reason == RecommendationReason.CONTENT } }
                    assertEquals(1, fixture.writeCount(path)); assertEquals("1", fixture.writeForms[path]!!["reason_id"])
                    assertEquals("100001", fixture.writeForms[path]!!["id"]); assertEquals("UI-DEMO-RECOMMENDATION-0", fixture.writeForms[path]!!["track_id"])
                    assertEquals("fixture-csrf-only", fixture.writeForms[path]!!["csrf"])
                    text("交互测试样片 B"); fresh(); assertFalse(device.hasObject(By.text("交互测试样片 A")))
                    description("刷新视频列表"); SystemClock.sleep(500); text("交互测试样片 B"); fresh(); assertFalse(device.hasObject(By.text("交互测试样片 A")))
                    main { val seed = Graph.api.feed("推荐").videos.last(); vm.openShorts(seed) }
                    await("Short video not playing") { Graph.player.exo.isPlaying }
                    description("短视频选项"); text("不感兴趣").click(); text("不想看这个UP主").click()
                    await("Short feedback lost the original recommendation context") { Graph.store.recommendations.rules("9").any { it.reason == RecommendationReason.CREATOR } }
                    assertEquals(2, fixture.writeCount(path)); assertEquals("4", fixture.writeForms[path]!!["reason_id"])
                    assertEquals("170001", fixture.writeForms[path]!!["id"]); assertEquals("UI-DEMO-RECOMMENDATION-1", fixture.writeForms[path]!!["track_id"])
                    await("Rejected short stayed on screen playing") { !Graph.player.exo.isPlaying }
                    assertTrue(main { Graph.store.recommendations.rules("guest").isEmpty() })
                    assertEquals(2, RecommendationFilters(context).rules("9").size)
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-recommendation-feedback.png"))
                } finally { main { Graph.player.stop(); Graph.store.recommendations.clear("9") }; device.unfreezeRotation() }
            }
        }
    }
    @Test fun rejectionAndUnknownOutcomeDoNotClaimSuccessOrReplayFeedback() {
        start()
        InteractionFixture(instrumentation, loggedIn = true, recommendationData = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.root("home") }
                    await("Synthetic account not ready") { vm.user.value != null }
                    fixture.failNextRecommendation = true
                    longPressVideo("交互测试样片 A"); text("内容不感兴趣").click(); node(By.textContains("模拟推荐反馈失败"))
                    assertTrue(main { Graph.store.recommendations.rules("9").isEmpty() }); assertEquals(1, fixture.writeCount(path)); text("取消").click()
                    fixture.loseNextRecommendationResponse = true
                    longPressVideo("交互测试样片 A"); text("内容不感兴趣").click(); node(By.textContains("提交结果暂未确认"))
                    text("内容不感兴趣").click(); SystemClock.sleep(250); assertEquals(2, fixture.writeCount(path)); assertTrue(main { Graph.store.recommendations.rules("9").isEmpty() })
                    text("关闭").click(); text("交互测试样片 A")
                    // A manually opened short has no tracking context; use the account endpoint without inventing one.
                    main { Graph.api.dislikeRecommendation(Video(aid = 100001, mid = 80), RecommendationReason.CONTENT) }
                    assertEquals("", fixture.writeForms[path]!!["track_id"]); assertEquals(3, fixture.writeCount(path))
                } finally { main { Graph.player.stop(); Graph.store.recommendations.clear("9") }; device.unfreezeRotation() }
            }
        }
    }
}
