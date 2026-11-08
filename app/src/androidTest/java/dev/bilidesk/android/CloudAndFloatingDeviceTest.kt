// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.content.Context
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import dev.bilidesk.android.sync.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Synthetic accounts and local media only. Real WebDAV authorization is never put into the test APK. */
@RunWith(AndroidJUnit4::class)
class CloudAndFloatingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun await(message: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { var result = false; instrumentation.runOnMainSync { result = block() }; if (result) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun node(description: String): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache()
            device.findObject(By.desc(description))?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing: $description")
    }
    private fun text(value: String): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache()
            device.findObject(By.text(value))?.let { return it }; SystemClock.sleep(100)
        }
        error("Missing: $value")
    }
    private fun scrollToText(value: String): UiObject2 {
        repeat(7) {
            device.findObject(By.text(value))?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .7f)
            SystemClock.sleep(150)
        }
        return text(value)
    }
    private fun start() { Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural(); main { Graph.store.recommendations.clear("9"); Graph.store.recommendations.clear("guest") } }
    private class MemoryDav {
        val files = ConcurrentHashMap<String, ByteArray>()
        @Volatile var offline = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            check(request.url.host == "sync-fixture.invalid" && path.startsWith("/dav/BiliDeskSync/"))
            check(request.header("Cookie") == null)
            var code = if (offline) 503 else 200; var bytes = ByteArray(0)
            if (!offline) when (request.method) {
                "MKCOL" -> code = 201
                "PUT" -> { val body = Buffer(); request.body!!.writeTo(body); files[path] = body.readByteArray(); code = 201 }
                "GET" -> { bytes = files[path] ?: ByteArray(0); if (!files.containsKey(path)) code = 404 }
                "PROPFIND" -> {
                    code = 207
                    bytes = ("<d:multistatus xmlns:d=\"DAV:\">" + files.filterKeys { it.startsWith(path) && !it.removePrefix(path).contains('/') }.entries.joinToString("") { (name, value) ->
                        "<d:response><d:href>$name</d:href><d:propstat><d:prop><d:getcontentlength>${value.size}</d:getcontentlength></d:prop></d:propstat></d:response>"
                    } + "</d:multistatus>").toByteArray()
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("UI DEMO DAV")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        fun transport(c: SyncCredentials) = WebDavSync(c.username, c.password, "https://sync-fixture.invalid/dav/BiliDeskSync/".toHttpUrl(), client)
    }

    @Test fun encryptedSyncMergesOfflineEditsKeepsPlayingAndHandsOffWholeQueue() {
        start()
        InteractionFixture(instrumentation, loggedIn = true, musicMedia = true, socialData = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = Graph.musicSync
                val memory = MemoryDav(); val key = SyncCrypto.newKey(); val suffix = UUID.randomUUID().toString()
                lateinit var phone: MusicSync; lateinit var tablet: MusicSync; lateinit var secondPlayer: PlayerEngine; lateinit var vm: AppViewModel
                val a = MusicTrack(Video(bvid = "BV156xx6wEff", title = "UI DEMO A"), 101)
                val b = MusicTrack(Video(bvid = "BV17x411w7KC", title = "UI DEMO B"), 102)
                var firstId = ""
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                    main {
                        secondPlayer = PlayerEngine(context, Graph.api, Graph.store, "sync-tablet-$suffix")
                        phone = MusicSync(context, Graph.player, Graph.store, memory::transport, "sync-phone-$suffix")
                        tablet = MusicSync(context, secondPlayer, Graph.store, memory::transport, "sync-tablet-$suffix")
                        Graph.musicSync = phone
                        firstId = Graph.player.music.library.create("手机歌单", listOf(a))
                        phone.configure("ui-demo", "ui-only-password", key, "演示手机", true)
                        secondPlayer.music.library.create("平板歌单", listOf(b))
                        tablet.configure("ui-demo", "ui-only-password", key, "演示平板", false)
                        phone.syncNow()
                    }
                    assertEquals(2, main { Graph.player.music.library.state.value.playlists.size })
                    memory.offline = true
                    main {
                        Graph.player.music.library.remove(firstId, a.key)
                        phone.syncNow()
                        secondPlayer.music.library.rename(firstId, "合并后的歌单")
                        secondPlayer.music.library.add(firstId, listOf(b))
                    }
                    assertTrue(main { phone.state.value.message.contains("本机修改已保留") })
                    memory.offline = false
                    main { tablet.syncNow(); phone.syncNow(); tablet.syncNow() }
                    val merged = main { Graph.player.music.library.state.value.playlists.first { it.id == firstId } }
                    assertEquals("合并后的歌单", merged.name); assertEquals(listOf(b.key), merged.tracks.map { it.key })
                    main { Graph.player.music.playTrack(a); vm.openMusicPlayer() }
                    await("Local music did not start") { Graph.player.exo.isPlaying && Graph.player.exo.audioDecoderCounters?.renderedOutputBufferCount?.let { it > 0 } == true }
                    val media = main { Graph.player.exo.currentMediaItem }
                    val queue = MusicQueueState(listOf(b, a), b.key, MusicMode.SHUFFLE, listOf(a.key), listOf(a.key), listOf(a.key))
                    main { secondPlayer.music.restoreSynced(queue, 19500, false); tablet.syncNow(); phone.syncNow() }
                    main { assertSame("Reading cloud queue interrupted playback", media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    main { assertNull("Active listening should not be interrupted by resume prompts", phone.state.value.pending) }
                    node("选择设备续播记录").click(); text("设备续播"); text("演示平板").click()
                    await("Handoff did not restore song and position") { Graph.player.state.value.detail?.video?.bvid == b.video.bvid && Graph.player.exo.isPlaying && Graph.player.exo.currentPosition >= 19000 }
                    main {
                        assertEquals(listOf(b.key, a.key), Graph.player.music.queue.value.tracks.map { it.key })
                        assertEquals(MusicMode.SHUFFLE, Graph.player.music.queue.value.mode)
                        assertEquals(listOf(a.key), Graph.player.music.queue.value.nextUp)
                        assertEquals(listOf(a.key), Graph.player.music.queue.value.remaining)
                    }
                    // A pause immediately after a sync must send its final position after coalescing.
                    main { Graph.player.seek(32000); Graph.player.exo.pause(); phone.sync(); phone.sync() }
                    await("Rapid pause checkpoint was dropped") {
                        memory.files.entries.firstOrNull { it.key.endsWith("device-${phone.deviceId}.bdks") }?.value?.let { bytes ->
                            SyncCrypto.decode(bytes, key).allCheckpoints()[phone.deviceId]?.let { it.queue.currentKey == b.key && it.position in 31500..33500 } == true
                        } == true
                    }
                    // The same already-loaded song must honor a remote rewind, without a reload.
                    val loadedSong = main { Graph.player.exo.currentMediaItem }
                    // Rebase the source on the target's completed pause before making a newer rewind.
                    // Enter a fresh foreground session: automatic invitations are deliberately offered
                    // once per visit, including an invitation that could have appeared during setup.
                    main { tablet.syncNow(); secondPlayer.music.restoreSynced(queue, 8000, false); tablet.syncNow(); phone.syncNow() }
                    await("Remote rewind did not become eligible") { phone.state.value.pending?.position == 8000L }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    node("自动续播提示"); text("接着听").click()
                    await("Same-song handoff ignored remote position") { Graph.player.exo.isPlaying && Graph.player.exo.currentPosition in 8000..12000 }
                    main { assertSame("Same-song handoff reloaded media", loadedSong, Graph.player.exo.currentMediaItem) }
                    assertTrue(memory.files.values.none { it.toString(Charsets.ISO_8859_1).contains("手机歌单") })
                    main { phone.disconnect() }
                    assertEquals(listOf(b.key, a.key), main { Graph.player.music.queue.value.tracks.map { it.key } })
                    assertTrue(memory.files.isNotEmpty())
                } catch (e: Throwable) {
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-sync-ui-failure.png"))
                    device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "024-sync-ui-failure.xml"))
                    throw e
                } finally {
                    main { runCatching { phone.release() }; runCatching { tablet.release() }; runCatching { secondPlayer.release() }; Graph.musicSync = previous; Graph.player.stop() }
                    device.unfreezeRotation()
                }
            }
        }
    }
    @Test fun syncSetupProtectsSecretsAndDoesNotEnableWithoutConsent() {
        start()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[AppViewModel::class.java].navigate("music_sync", label = "跨设备同步") }
            text("创建同步库"); text("连接已有同步库")
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0); assertFalse(Graph.musicSync.state.value.configured) }
            text("连接已有同步库").click(); scrollToText("填写第一台设备的同步密钥")
            // Material's text child can expose enabled=true even when its containing button is disabled.
            // Exercise the actual touch path and verify that no connection is enabled.
            scrollToText("连接并合并已有记录").click()
            scenario.onActivity { assertFalse(Graph.musicSync.state.value.configured) }
            device.pressBack()
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) }
        }
    }

    @Test fun authorFloatingVideoDragsAndReturnsToNormalModeWithoutReload() = floating(false)
    @Test fun commentProfileFloatingVideoReturnsToOriginalShortSession() = floating(true)

    private fun floating(short: Boolean) {
        start()
        InteractionFixture(instrumentation, loggedIn = true, richComments = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel; var media: Any? = null; var session: Any? = null
                try {
                    scenario.onActivity {
                        vm = ViewModelProvider(it)[AppViewModel::class.java]; Graph.player.exo.repeatMode = Player.REPEAT_MODE_ONE
                        if (short) vm.openShorts(Video(bvid = "BV156xx6wEff")) else vm.openVideo(Video(bvid = "BV156xx6wEff"))
                    }
                    await("Video did not decode") { Graph.player.exo.isPlaying && (Graph.player.exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 }
                    scenario.onActivity { media = Graph.player.exo.currentMediaItem; session = vm.shortSession }
                    if (short) { node("打开评论").click(); node("查看夏日听风的主页").click() }
                    else scenario.onActivity { vm.openCreator(CreatorProfile(91, "演示UP", "")) }
                    node("主页视频小窗")
                    await("Floating surface did not take ownership") { PlayerViewHost.currentView?.tag == "bilidesk_profile_floating" && Graph.player.exo.isPlaying }
                    await("Floating surface did not render video pixels") {
                        (PlayerViewHost.currentView?.videoSurfaceView as? android.view.TextureView)?.bitmap?.let { image ->
                            try { (1..4).flatMap { x -> (1..4).map { y -> image.getPixel(image.width * x / 5, image.height * y / 5) } }.distinct().size > 4 }
                            finally { image.recycle() }
                        } == true
                    }
                    val before = node("主页视频小窗").visibleBounds
                    val corner = node("缩放小窗 左上角").visibleBounds
                    device.swipe(corner.centerX(), corner.centerY(), corner.centerX() - 110, corner.centerY() - 65, 22)
                    await("Floating corner did not enlarge the picture") { vm.floatingVideoScale.value > 1.1f }
                    val enlarged = node("主页视频小窗").visibleBounds
                    assertTrue("Floating dimensions did not grow", enlarged.width() > before.width() + 30)
                    assertEquals(before.width().toFloat() / before.height(), enlarged.width().toFloat() / enlarged.height(), .06f)
                    val shrinkCorner = node("缩放小窗 右下角").visibleBounds
                    device.swipe(shrinkCorner.centerX(), shrinkCorner.centerY(), shrinkCorner.centerX() - 70, shrinkCorner.centerY() - 40, 20)
                    SystemClock.sleep(250)
                    assertTrue("Floating corner did not shrink the picture", node("主页视频小窗").visibleBounds.width() < enlarged.width() - 20)
                    scenario.onActivity { assertSame("Resizing reloaded the video", media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    val dragStart = node("主页视频小窗").visibleBounds
                    device.swipe(dragStart.centerX(), dragStart.centerY(), (dragStart.centerX() - 100).coerceAtLeast(dragStart.width() / 2 + 10), (dragStart.centerY() + 170).coerceAtMost(device.displayHeight - dragStart.height()), 20)
                    SystemClock.sleep(400)
                    val after = node("主页视频小窗").visibleBounds
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), if (short) "024-floating-short.png" else "024-floating-normal.png"))
                    assertTrue("Floating player did not move", kotlin.math.abs(after.top - dragStart.top) > 30)
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying) }
                    device.click(after.centerX(), after.centerY()); SystemClock.sleep(70); device.click(after.centerX(), after.centerY())
                    await("Double tap did not return to original playback mode") { vm.route.value.name == (if (short) "shorts" else "player") && vm.creator.value == null && PlayerViewHost.currentView?.tag != "bilidesk_profile_floating" }
                    scenario.onActivity { assertSame(media, Graph.player.exo.currentMediaItem); assertTrue(Graph.player.exo.isPlaying); if (short) assertSame(session, vm.shortSession) }
                    if (short) assertFalse(device.hasObject(By.desc("视频评论列表")))
                } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
