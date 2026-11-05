// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.BiliApi
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.player.MusicMode
import dev.bilidesk.android.ui.AppViewModel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Local illustrations and fake lists keep visual QA independent of platform content. */
@RunWith(AndroidJUnit4::class)
class VisualRefreshDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val titles = listOf("把周末交给山野与海风", "一个人的厨房，也要好好吃饭", "搭起自己的小小工作台", "记录下班后的城市天色", "好听的旋律，值得再循环一次", "今天也在认真画画")
    private fun await(condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            var ok = false; instrumentation.runOnMainSync { ok = condition() }
            if (ok) return true
            SystemClock.sleep(120)
        }
        return false
    }
    private fun clearUi() { if (Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun text(value: String): UiObject2 { clearUi(); return requireNotNull(device.wait(Until.findObject(By.text(value)), 12000)) { "Missing visible text: $value" } }
    private fun button(value: String): UiObject2 { clearUi(); return requireNotNull(device.wait(Until.findObject(By.desc(value)), 10000)) { "Missing visible action: $value" } }
    private fun shot(name: String) { SystemClock.sleep(250); device.takeScreenshot(File(context.getExternalFilesDir(null), "$name-0.2.0.png")) }

    private fun covers(): List<File> {
        val pairs = listOf(0xFF8BCAD4.toInt() to 0xFF3D798D.toInt(), 0xFFF5D7A2.toInt() to 0xFFB47553.toInt(), 0xFFADD9C6.toInt() to 0xFF477E77.toInt(), 0xFFFFC998.toInt() to 0xFFAA6C91.toInt(), 0xFFB3B5EB.toInt() to 0xFF6566B3.toInt(), 0xFFF5B6CC.toInt() to 0xFF9E7BB9.toInt())
        return pairs.mapIndexed { index, (top, bottom) ->
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 640f, 360f, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 640f, 360f, paint); paint.shader = null
            paint.color = 0x24FFFFFF; canvas.drawCircle(540f, 38f, 180f, paint)
            paint.color = 0x32FFFFFF; canvas.drawCircle(60f, 360f, 200f, paint)
            paint.color = 0xFFF8F6F1.toInt()
            when (index) {
                0 -> { canvas.drawCircle(490f, 95f, 42f, paint); paint.color = 0xFF33717A.toInt(); canvas.drawPath(Path().apply { moveTo(0f, 340f); lineTo(190f, 120f); lineTo(330f, 290f); lineTo(470f, 180f); lineTo(640f, 340f); close() }, paint) }
                1 -> { canvas.drawOval(RectF(220f, 220f, 470f, 270f), paint); canvas.drawRoundRect(RectF(270f, 95f, 420f, 230f), 24f, 24f, paint); paint.style = Paint.Style.STROKE; paint.strokeWidth = 13f; canvas.drawOval(RectF(385f, 125f, 465f, 200f), paint); paint.style = Paint.Style.FILL }
                2 -> { canvas.drawRoundRect(RectF(180f, 65f, 485f, 245f), 14f, 14f, paint); paint.color = 0xFF4B717B.toInt(); canvas.drawRoundRect(RectF(195f, 80f, 470f, 228f), 8f, 8f, paint); paint.color = 0xFFF8F6F1.toInt(); canvas.drawRect(315f, 245f, 350f, 280f, paint); canvas.drawRoundRect(RectF(270f, 274f, 395f, 288f), 6f, 6f, paint) }
                3 -> { repeat(7) { i -> paint.color = if (i % 2 == 0) 0xFF505C80.toInt() else 0xFF78678C.toInt(); val left = 50f + i * 85; val height = 100f + (i * 47 % 110); canvas.drawRoundRect(RectF(left, 320f - height, left + 67f, 340f), 5f, 5f, paint); paint.color = 0xFFFFD7A9.toInt(); repeat(4) { row -> canvas.drawRect(left + 13, 335f - height + row * 25, left + 26, 345f - height + row * 25, paint) } } }
                4 -> { paint.color = 0xFF24263F.toInt(); canvas.drawCircle(340f, 175f, 125f, paint); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = 0x44FFFFFF; for (r in listOf(104f, 88f, 72f)) canvas.drawCircle(340f, 175f, r, paint); paint.style = Paint.Style.FILL; paint.color = 0xFFF3BCCB.toInt(); canvas.drawCircle(340f, 175f, 48f, paint); paint.color = 0xFF24263F.toInt(); canvas.drawCircle(340f, 175f, 7f, paint) }
                else -> { canvas.save(); canvas.rotate(-9f, 330f, 180f); canvas.drawRoundRect(RectF(220f, 55f, 445f, 305f), 8f, 8f, paint); paint.color = 0xFFDA7A99.toInt(); canvas.drawCircle(330f, 140f, 50f, paint); paint.color = 0xFF7E91B9.toInt(); canvas.drawRoundRect(RectF(260f, 213f, 405f, 232f), 8f, 8f, paint); canvas.restore() }
            }
            paint.color = 0xDDFFFFFF.toInt(); paint.textSize = 18f; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText("UI DEMO / ${index + 1}", 22f, 32f, paint)
            File(context.getExternalFilesDir(null), "visual-cover-$index.png").also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
    }

    @Test fun refreshedFeedProfileThemesAndNavigationRemainUsableWithContinuousMusic() {
        Configurator.getInstance().waitForIdleTimeout = 100
        device.setOrientationNatural()
        val original = Graph.store.settings.value
        val requests = CopyOnWriteArrayList<String>()
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true).use {
            val covers = covers()
            val api = Graph.api
            fun videos(page: Int) = JSONArray((0 until if (page == 1) 12 else 4).map { i -> JSONObject()
                .put("aid", 910000 + page * 100 + i).put("title", titles[i % titles.size])
                .put("pic", Uri.fromFile(covers[i % covers.size]).toString()).put("duration", 248 + i * 37)
                .put("owner", JSONObject().put("name", listOf("日常记录所", "认真生活实验室", "周末计划")[i % 3]))
                .put("stat", JSONObject().put("view", 12000 + i * 17123)) })
            val client = api.http.newBuilder().apply { interceptors().add(0, Interceptor { chain ->
                val request = chain.request(); val path = request.url.encodedPath
                val payload = when (path) {
                    "/x/web-interface/wbi/index/top/feed/rcmd", "/x/web-interface/popular", "/x/web-interface/region/feed/rcmd", "/x/web-interface/ranking/v2" -> {
                        requests.add(path)
                        val page = request.url.queryParameter("pn")?.toIntOrNull() ?: request.url.queryParameter("brush")?.toIntOrNull() ?: 1
                        JSONObject().put("item", videos(page))
                    }
                    "/x/web-interface/wbi/search/type" -> JSONObject().put("numPages", 1).put("result", videos(1))
                    else -> null
                }
                if (payload == null) chain.proceed(request) else Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("visual-fixture")
                    .body(JSONObject().put("code", 0).put("data", payload).toString().toResponseBody("application/json".toMediaType())).build()
            }) }.build()
            instrumentation.runOnMainSync { Graph.api = BiliApi(api.cookies, Graph.store, client); Graph.store.change { it.copy(theme = 2, feedDensity = 1, feedColumns = 0) } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var vm: AppViewModel? = null
                var window: android.view.Window? = null
                try {
                    scenario.onActivity { window = it.window; vm = ViewModelProvider(it)[AppViewModel::class.java]; vm!!.root("home") }
                    text(titles.first()); button("搜索")
                    val settings = button("设置").visibleBounds
                    assertTrue("Toolbar occupies too much of the phone viewport", settings.bottom < device.displayHeight / 4)
                    shot("feed-light")
                    text("热门").click()
                    assertTrue(await { requests.contains("/x/web-interface/popular") })
                    text("推荐").click(); text(titles.first())
                    button("搜索").click()
                    assertTrue(await { vm!!.route.value.name == "search" })
                    device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "界面演示"
                    button("开始搜索").click(); text(titles.first()); shot("search-light")
                    device.pressBack(); button("打开个人中心").click()
                    assertTrue(await { vm!!.route.value.name == "library" })
                    text("模拟交互账号"); text("收藏"); text("历史"); text("缓存"); shot("profile-light")
                    button("设置").click(); text("主题").click(); text("深色").click()
                    assertTrue(await { Graph.store.settings.value.theme == 1 })
                    assertTrue("System icons did not follow dark theme", await { WindowCompat.getInsetsController(window!!, window!!.decorView).isAppearanceLightStatusBars.not() })
                    assertTrue("Navigation icons did not follow dark theme", await { WindowCompat.getInsetsController(window!!, window!!.decorView).isAppearanceLightNavigationBars.not() })
                    shot("settings-dark")
                    device.pressBack(); text("模拟交互账号"); shot("profile-dark")
                    text("发现").click(); text(titles.first()); shot("feed-dark")
                    scenario.onActivity { Graph.player.music.playAll(listOf(Video(bvid = "BV156xx6wEff", title = "交互测试样片 A"))); Graph.player.music.mode(MusicMode.ONE) }
                    assertTrue(await { Graph.player.exo.isPlaying && (Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 })
                    button("暂停音乐").click()
                    assertTrue(await { !Graph.player.exo.playWhenReady })
                    button("播放音乐").click(); assertTrue(await { Graph.player.exo.isPlaying })
                    shot("mini-player-dark")
                    text("音乐").click()
                    assertTrue(await { vm!!.route.value.name == "music" })
                    val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
                    if (!tablet) {
                        button("收起音乐播放器")
                        assertTrue(device.wait(Until.gone(By.text("发现")), 10000))
                        shot("music-immersive")
                        device.pressBack(); text("发现")
                        assertTrue("Leaving music changed dark status icons", await { WindowCompat.getInsetsController(window!!, window!!.decorView).isAppearanceLightStatusBars.not() })
                    } else {
                        device.setOrientationLeft(); assertTrue(await { device.displayWidth > device.displayHeight })
                        text("发现"); shot("music-tablet-wide")
                        text("发现").click(); text(titles.first()); shot("feed-tablet-wide")
                    }
                    assertTrue("UI navigation interrupted music", await { Graph.player.exo.isPlaying })
                } catch (failure: Throwable) {
                    shot("visual-failure"); device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "visual-failure.xml")); throw failure
                } finally { scenario.onActivity { Graph.player.stop(); Graph.store.update(original) }; device.unfreezeRotation() }
            }
        }
    }
}
