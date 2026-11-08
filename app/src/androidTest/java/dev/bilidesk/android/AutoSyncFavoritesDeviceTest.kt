// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
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
import java.util.concurrent.CopyOnWriteArrayList

/** Local media, synthetic accounts and in-memory WebDAV. No platform or user cloud writes. */
@RunWith(AndroidJUnit4::class)
class AutoSyncFavoritesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val fast = SyncTiming(editDelayMs = 80, progressMs = 800, pollMs = 350, backgroundPollMs = 500,
        resumePollMs = 150, resumeWindowMs = 600, minReadGapMs = 80, minUploadGapMs = 100, retryMs = 150)
    private val a = MusicTrack(Video(bvid = "BV156xx6wEff", title = "UI DEMO A"), 101)
    private val b = MusicTrack(Video(bvid = "BV17x411w7KC", title = "UI DEMO B"), 102)
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun await(message: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 18000
        while (SystemClock.elapsedRealtime() < end) { if (main { block() }) return; SystemClock.sleep(80) }
        fail(message)
    }
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.uiAutomation.clearCache() }
    private fun node(selector: BySelector): UiObject2 {
        val end = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < end) { fresh(); device.findObject(selector)?.let { return it }; SystemClock.sleep(120) }
        error("Missing UI element: $selector")
    }
    private fun text(value: String) = node(By.text(value))
    private fun clickDescription(value: String) {
        var target = node(By.desc(value)); while (!target.isClickable && target.parent != null) target = target.parent; target.click()
    }
    private fun start() { Configurator.getInstance().waitForIdleTimeout = 100; device.setOrientationNatural() }

    private class MemoryDav {
        val files = ConcurrentHashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var offline = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            check(request.url.host == "auto-sync-fixture.invalid" && path.startsWith("/dav/BiliDeskSync/")); check(request.header("Cookie") == null)
            requests += "${request.method} $path"
            var code = if (offline) 503 else 200; var bytes = ByteArray(0)
            if (!offline) when (request.method) {
                "MKCOL" -> code = 201
                "PUT" -> { val body = Buffer(); request.body!!.writeTo(body); files[path] = body.readByteArray(); code = 201 }
                "GET" -> { bytes = files[path] ?: ByteArray(0); if (!files.containsKey(path)) code = 404 }
                "PROPFIND" -> { code = 207; bytes = ("<d:multistatus xmlns:d=\"DAV:\">" + files.filterKeys { it.startsWith(path) && !it.removePrefix(path).contains('/') }.entries.joinToString("") { (name, value) ->
                    "<d:response><d:href>$name</d:href><d:propstat><d:prop><d:getcontentlength>${value.size}</d:getcontentlength><d:getetag>&quot;${value.contentHashCode()}&quot;</d:getetag></d:prop></d:propstat></d:response>"
                } + "</d:multistatus>").toByteArray() }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("LOCAL AUTO SYNC DEMO")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        fun transport(c: SyncCredentials) = WebDavSync(c.username, c.password, "https://auto-sync-fixture.invalid/dav/BiliDeskSync/".toHttpUrl(), client)
        fun put(key: String, device: String, doc: SyncDocument) { files["/dav/BiliDeskSync/vault-${SyncCrypto.vaultId(key)}/device-$device.bdks"] = SyncCrypto.encode(doc, key) }
        fun doc(key: String, device: String) = files.entries.firstOrNull { it.key.endsWith("device-$device.bdks") }?.value?.let { SyncCrypto.decode(it, key) }
    }

    @Test fun editsQueuePauseAndReconnectTransferWithoutManualSync() {
        start()
        InteractionFixture(instrumentation, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey(); val suffix = UUID.randomUUID()
                lateinit var phone: MusicSync; lateinit var tablet: MusicSync; lateinit var other: PlayerEngine
                try {
                    main {
                        other = PlayerEngine(context, Graph.api, Graph.store, "auto-other-$suffix")
                        phone = MusicSync(context, Graph.player, Graph.store, cloud::transport, "auto-phone-$suffix", fast)
                        tablet = MusicSync(context, other, Graph.store, cloud::transport, "auto-tablet-$suffix", fast)
                        Graph.musicSync = phone
                        phone.configure("demo-only", "demo-password", key, "自动演示手机", true)
                        tablet.configure("demo-only", "demo-password", key, "自动演示平板", false)
                        phone.setForeground(true); tablet.setForeground(true)
                    }
                    // From here, only ordinary edits/player/network events may drive a transfer.
                    val id = main { Graph.player.music.library.create("自动同步的歌单", listOf(a)) }
                    await("Playlist edit required manual sync") { other.music.library.state.value.playlists.any { it.id == id } }
                    main { Graph.player.music.restoreSynced(MusicQueueState(listOf(a, b), a.key, MusicMode.ONE, listOf(b.key)), 12000, true) }
                    await("Music failed to decode") { Graph.player.exo.isPlaying }
                    await("Queue change did not upload") { cloud.doc(key, phone.deviceId)?.allCheckpoints()?.get(phone.deviceId)?.queue?.tracks?.size == 2 }
                    main { Graph.player.seek(35000); Graph.player.exo.pause() }
                    await("Pause final position was not automatically uploaded") { cloud.doc(key, phone.deviceId)?.allCheckpoints()?.get(phone.deviceId)?.position in 34500L..36500L }
                    await("Other device did not receive the phone record") { tablet.state.value.devices.any { it.stamp.device == phone.deviceId && it.position in 34500L..36500L } }
                    main { assertNull("Fetched queue should wait for explicit choice", other.music.queue.value.current) }
                    cloud.offline = true
                    main { Graph.player.music.library.rename(id, "断网修改也会补传") }
                    await("Offline edit not retained") { phone.state.value.uploadPending && phone.state.value.message.contains("本机修改已保留") }
                    cloud.offline = false
                    main { phone.onNetworkRecovered(); tablet.onNetworkRecovered() }
                    await("Reconnection did not upload offline edits") { other.music.library.state.value.playlists.any { it.id == id && it.name == "断网修改也会补传" } }
                    // With unchanged ETags, repeated polling must not download journals or echo uploads.
                    main { phone.setForeground(false); tablet.setForeground(false) }; SystemClock.sleep(1000)
                    val before = cloud.requests.count { it.startsWith("PUT ") }
                    main { phone.onMusicVisible(); phone.setForeground(true) }; SystemClock.sleep(1300)
                    assertEquals("Unchanged remote documents caused echo writes", before, cloud.requests.count { it.startsWith("PUT ") })
                } finally { main { runCatching { phone.release() }; runCatching { tablet.release() }; runCatching { other.release() }; Graph.musicSync = previous; Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun continuousProgressAndStaleWorkerWaitForSafetyUploadThenPausePublishesPromptly() {
        start()
        InteractionFixture(instrumentation, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use {
                val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey()
                lateinit var sync: MusicSync
                try {
                    main {
                        sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, "event-budget-${UUID.randomUUID()}", fast.copy(progressMs = 8000))
                        Graph.musicSync = sync; sync.configure("demo-only", "demo-password", key, "省流演示", true); sync.setForeground(true)
                        Graph.player.music.playTrack(a); Graph.player.music.mode(MusicMode.ONE)
                    }
                    await("Initial listening operation did not upload") {
                        Graph.player.exo.isPlaying && cloud.doc(key, sync.deviceId)?.progress?.containsKey(a.video.key) == true &&
                            cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.queue?.currentKey == a.key
                    }
                    SystemClock.sleep(1200) // Finish setup/start/lifecycle events before timing uninterrupted playback.
                    main { sync.syncNow() } // Establish an exact completed-upload boundary for this timing test.
                    val before = cloud.requests.count { it.startsWith("PUT ") }
                    val position = main { cloud.doc(key, sync.deviceId)!!.allCheckpoints().getValue(sync.deviceId).position }
                    SystemClock.sleep(2200)
                    assertEquals("Uninterrupted playback scheduled an early upload", before, cloud.requests.count { it.startsWith("PUT ") })
                    val worker = runBlocking { TestListenableWorkerBuilder<SyncUploadWorker>(context).build().doWork() }
                    assertEquals(ListenableWorker.Result.success(), worker)
                    assertEquals("A stale worker bypassed the safety interval", before, cloud.requests.count { it.startsWith("PUT ") })
                    assertTrue(main { Graph.player.exo.isPlaying })
                    await("Continuous listening lost its safety checkpoint") {
                        cloud.requests.count { it.startsWith("PUT ") } > before &&
                            (cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position ?: 0) > position + 4000
                    }
                    val afterSafety = cloud.requests.count { it.startsWith("PUT ") }
                    val pausedAt = main { Graph.player.exo.pause(); Graph.player.exo.currentPosition }
                    await("Pause was held behind the long safety interval") {
                        cloud.requests.count { it.startsWith("PUT ") } > afterSafety &&
                            cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position?.let { kotlin.math.abs(it - pausedAt) < 500 } == true
                    }
                    SystemClock.sleep(500) // Allow the final paused position/history notification to coalesce.
                    val settled = cloud.requests.count { it.startsWith("PUT ") }
                    SystemClock.sleep(1400)
                    runBlocking { TestListenableWorkerBuilder<SyncUploadWorker>(context).build().doWork() }
                    assertEquals("Completed pause kept re-uploading ordinary progress", settled, cloud.requests.count { it.startsWith("PUT ") })
                    main { sync.disconnect() }
                } finally { main { runCatching { sync.release() }; Graph.musicSync = previous; Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun seeksLeavingPlaybackBackgroundAndStopUploadWithoutWaitingForSafetyTimer() {
        start()
        InteractionFixture(instrumentation, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey()
                lateinit var sync: MusicSync; lateinit var vm: AppViewModel
                try {
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                    main {
                        sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, "event-actions-${UUID.randomUUID()}", fast.copy(progressMs = 20 * 60000L))
                        Graph.musicSync = sync; sync.configure("demo-only", "demo-password", key, "操作演示", true); sync.setForeground(true)
                        Graph.player.music.playTracks(listOf(a, b)); Graph.player.music.mode(MusicMode.ONE); vm.openMusicPlayer()
                    }
                    await("Music did not start") { Graph.player.exo.isPlaying }
                    node(By.desc("音乐播放队列"))
                    main { Graph.player.seek(17000); Graph.player.exo.pause() }
                    await("Paused seek was not published") { cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position in 16800L..17800L }
                    main { Graph.player.seek(24000) }
                    await("A second paused seek required manual sync") { cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position in 23800L..24200L }
                    main { Graph.player.exo.play() }; await("Music did not resume") { Graph.player.exo.isPlaying }; SystemClock.sleep(1300)
                    val leavingAt = main { val p = Graph.player.exo.currentPosition; vm.root("home"); p }
                    await("Leaving the player did not flush progress") {
                        (cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position ?: 0) >= leavingAt - 300
                    }
                    main { assertTrue("Leaving the player interrupted music", Graph.player.exo.isPlaying); Graph.player.music.next() }
                    await("Next song did not publish its queue") { Graph.player.exo.isPlaying && cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.queue?.currentKey == b.key }
                    SystemClock.sleep(1300)
                    val backgroundAt = main { Graph.player.exo.currentPosition }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    await("Background operation did not flush") { (cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position ?: 0) >= backgroundAt - 300 }
                    main { assertTrue(Graph.player.exo.isPlaying) }; SystemClock.sleep(1300)
                    val stoppedAt = main { val p = Graph.player.exo.currentPosition; Graph.player.stop(); p }
                    await("Stopping lost final progress") { (cloud.doc(key, sync.deviceId)?.allCheckpoints()?.get(sync.deviceId)?.position ?: 0) >= stoppedAt - 300 }
                    main { sync.disconnect() }
                } finally { main { runCatching { sync.release() }; Graph.musicSync = previous; Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun coldStartRefreshesDevicesAndOlderRecordRemainsSelectableAfterNewestIsDismissed() {
        start()
        InteractionFixture(instrumentation, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey(); val pref = "auto-restart-${UUID.randomUUID()}"
                lateinit var sync: MusicSync; lateinit var vm: AppViewModel
                val oldQueue = MusicQueueState(listOf(a, b), a.key, MusicMode.SHUFFLE, listOf(b.key), listOf(b.key), listOf(b.key))
                try {
                    main {
                        cloud.transport(SyncCredentials("demo-only", "demo-password", key, "本机")).prepare(key, true)
                        cloud.put(key, "older-phone", SyncDocument().withCheckpoint(SyncCheckpoint(oldQueue, 14995, "另一台手机", SyncStamp(1000, 0, "older-phone"))))
                        sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, pref, fast)
                        sync.configure("demo-only", "demo-password", key, "本机", false); sync.release()
                        cloud.put(key, "newer-tablet", SyncDocument().withCheckpoint(SyncCheckpoint(MusicQueueState(listOf(b), b.key), 27000, "另一台平板", SyncStamp(2000, 0, "newer-tablet"))))
                        sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, pref, fast)
                        sync.setForeground(true) // Deliberately before asynchronous encrypted config loading finishes.
                        Graph.musicSync = sync
                    }
                    await("Cold start missed foreground catch-up") { sync.state.value.configured && sync.state.value.devices.size == 2 }
                    main { assertNull(Graph.player.music.queue.value.current); assertEquals("另一台平板", sync.state.value.pending?.deviceName); sync.dismissRemote() }
                    await("Dismissing newer device hid the older one") { sync.state.value.pending?.deviceName == "另一台手机" }
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.openMusicPlayer() }
                    node(By.desc("自动续播提示")); text("接着上次的音乐")
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-automatic-resume.png")); text("选择其他设备").click()
                    text("另一台平板"); text("另一台手机").click()
                    await("Older device did not restore its own queue and progress") { Graph.player.exo.isPlaying && Graph.player.music.queue.value.mode == MusicMode.SHUFFLE && Graph.player.exo.currentPosition in 14000L..18500L }
                    main { assertEquals(listOf(b.key), Graph.player.music.queue.value.nextUp); assertEquals(2, sync.state.value.devices.size); assertNull("Active listening must not show another resume prompt", sync.state.value.pending) }
                    val heartbeatTime = System.currentTimeMillis() + 3600000
                    main { cloud.put(key, "older-phone", SyncDocument().withCheckpoint(SyncCheckpoint(oldQueue, 14995, "另一台手机", SyncStamp(heartbeatTime, 0, "older-phone")))) }
                    await("Re-uploaded source was not fetched") { sync.state.value.devices.any { it.stamp.time == heartbeatTime } }
                    main { assertNull("Source heartbeat interrupted active listening", sync.state.value.pending); Graph.player.exo.pause() }
                    SystemClock.sleep(500)
                    main { cloud.put(key, "older-phone", SyncDocument().withCheckpoint(SyncCheckpoint(oldQueue, 15025, "另一台手机", SyncStamp(heartbeatTime + 3600000, 0, "older-phone")))) }
                    await("Final-flush source record not fetched") { sync.state.value.devices.any { it.stamp.time == heartbeatTime + 3600000 } }
                    main { assertNull("An already accepted queue/position was offered again while paused", sync.state.value.pending) }
                    clickDescription("选择设备续播记录"); text("另一台手机"); text("另一台平板")
                    val media = main { Graph.player.exo.currentMediaItem }; val oldSourceStamp = main { sync.state.value.devices.first { it.stamp.device == "older-phone" }.stamp }
                    clickDescription("重命名设备 另一台手机"); text("给设备起个名字")
                    node(By.clazz("android.widget.EditText")).text = "随身手机"; text("保存").click(); text("随身手机")
                    clickDescription("重命名本机设备"); node(By.clazz("android.widget.EditText")).text = "卧室平板"; text("保存").click()
                    await("Local device name not updated") { sync.state.value.deviceName == "卧室平板" }
                    await("Shared device names were not automatically uploaded") {
                        val names = cloud.doc(key, sync.deviceId)?.deviceNames
                        names?.get("older-phone")?.name == "随身手机" && names[sync.deviceId]?.name == "卧室平板"
                    }
                    main { assertSame("Renaming reloaded the media", media, Graph.player.exo.currentMediaItem); assertNull(sync.state.value.pending); assertEquals(oldSourceStamp, sync.state.value.devices.first { it.stamp.device == "older-phone" }.stamp) }
                    assertEquals("卧室平板", main { cloud.doc(key, sync.deviceId)?.deviceNames?.get(sync.deviceId)?.name })
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-device-records.png")); device.pressBack()
                    main { sync.disconnect(); assertEquals(2, Graph.player.music.queue.value.tracks.size) }
                } finally { main { runCatching { sync.release() }; Graph.musicSync = previous; Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }

    @Test fun connectedBackgroundWorkerUploadsDurableEditsAfterCoordinatorRestart() {
        start()
        InteractionFixture(instrumentation).use {
            val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey(); val pref = "auto-worker-${UUID.randomUUID()}"
            lateinit var sync: MusicSync; var scheduled = 0
            try {
                main { sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, pref, fast, pendingWork = { scheduled++ }); sync.configure("demo-only", "demo-password", key, "后台演示", true) }
                cloud.offline = true
                val id = main { Graph.player.music.library.create("离线保存的歌单", listOf(a)) }
                await("Background retry was not scheduled") { scheduled > 0 && sync.state.value.uploadPending }
                main { sync.release() }; cloud.offline = false
                main { sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, pref, fast.copy(editDelayMs = 10000)); Graph.musicSync = sync }
                val result = runBlocking { TestListenableWorkerBuilder<SyncUploadWorker>(context).build().doWork() }
                assertEquals(ListenableWorker.Result.success(), result)
                assertEquals("离线保存的歌单", cloud.doc(key, sync.deviceId)!!.library().playlists.first { it.id == id }.name)
                val count = cloud.requests.size
                assertEquals(ListenableWorker.Result.success(), runBlocking { TestListenableWorkerBuilder<SyncUploadWorker>(context).build().doWork() })
                assertEquals("A clean background worker performed network requests", count, cloud.requests.size)
                main { sync.disconnect() }
            } finally { main { runCatching { sync.release() }; Graph.musicSync = previous; Graph.player.stop() }; device.unfreezeRotation() }
        }
    }

    @Test fun automaticInvitationResumesOnceAndReturnsForANewListeningSession() {
        start()
        InteractionFixture(instrumentation, musicMedia = true).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = Graph.musicSync; val cloud = MemoryDav(); val key = SyncCrypto.newKey(); val suffix = UUID.randomUUID()
                lateinit var sync: MusicSync; lateinit var otherSync: MusicSync; lateinit var other: PlayerEngine; lateinit var vm: AppViewModel
                val source = MusicQueueState(listOf(b, a), b.key, MusicMode.ONE)
                val at = System.currentTimeMillis() - 120000
                try {
                    main {
                        cloud.transport(SyncCredentials("demo-only", "demo-password", key, "本机")).prepare(key, true)
                        cloud.put(key, "source-phone", SyncDocument().withCheckpoint(SyncCheckpoint(source, 27000, "我的手机", SyncStamp(at, 0, "source-phone"))))
                        sync = MusicSync(context, Graph.player, Graph.store, cloud::transport, "auto-invite-$suffix", fast)
                        sync.configure("demo-only", "demo-password", key, "本机", false); sync.setForeground(true); Graph.musicSync = sync
                    }
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.openMusicPlayer() }
                    node(By.desc("自动续播提示")); text("接着上次的音乐"); text("接着听").click()
                    await("Automatic invitation did not restore the shown song and progress") { Graph.player.exo.isPlaying && Graph.player.music.queue.value.currentKey == b.key && Graph.player.exo.currentPosition in 27000L..31500L }
                    val playing = main { Graph.player.exo.currentMediaItem }
                    main { sync.renameDevice("source-phone", "通勤手机") }
                    await("Renamed source was not uploaded") { cloud.doc(key, sync.deviceId)?.deviceNames?.get("source-phone")?.name == "通勤手机" }
                    main {
                        val pref = "auto-label-reader-$suffix"
                        context.getSharedPreferences(pref, android.content.Context.MODE_PRIVATE).edit().putString("id", "source-phone").commit()
                        other = PlayerEngine(context, Graph.api, Graph.store, "auto-label-player-$suffix")
                        otherSync = MusicSync(context, other, Graph.store, cloud::transport, pref, fast)
                        otherSync.configure("demo-only", "demo-password", key, "旧名称", false)
                        assertEquals("通勤手机", otherSync.state.value.deviceName)
                        assertSame(playing, Graph.player.exo.currentMediaItem); assertNull(sync.state.value.pending)
                        otherSync.disconnect(); otherSync.release()
                    }
                    fresh(); assertFalse("Renaming created another automatic invitation", device.hasObject(By.desc("自动续播提示")))
                    main { Graph.player.exo.pause() }; SystemClock.sleep(400)
                    val newer = System.currentTimeMillis() + 3600000
                    main { cloud.put(key, "source-phone", SyncDocument().withCheckpoint(SyncCheckpoint(MusicQueueState(listOf(a), a.key), 6000, "旧名称", SyncStamp(newer, 0, "source-phone")))); sync.onMusicVisible() }
                    await("New remote listening session was not received") { sync.state.value.pending?.queue?.currentKey == a.key }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    node(By.desc("自动续播提示")); text("通勤手机"); text("稍后再听").click()
                    await("Dismissing automatic invitation was not remembered") { sync.state.value.pending == null }
                    main { assertSame("A new invitation auto-switched the local song", playing, Graph.player.exo.currentMediaItem) }
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-automatic-invitation-dismissed.png"))
                } finally {
                    main { runCatching { sync.release() }; runCatching { otherSync.release() }; runCatching { other.release() }; Graph.musicSync = previous; Graph.player.stop() }
                    device.unfreezeRotation()
                }
            }
        }
    }

    @Test fun favoriteDeletionUsesConfirmationAndNeverReplaysAnAmbiguousWrite() {
        start()
        InteractionFixture(instrumentation, loggedIn = true, favoriteData = true).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                try {
                    main { Graph.api.createFolder("测试可删除的收藏夹") }
                    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; vm.navigate("music_library") }
                    text("模拟收藏夹").click(); clickDescription("歌曲选项 交互测试样片 A"); text("取消收藏").click(); text("取消").click()
                    assertEquals(0, fixture.writeCount("/x/v3/fav/resource/deal"))
                    scenario.onActivity { vm.root("library"); vm.navigate("folders") }
                    text("模拟收藏夹").click()
                    clickDescription("收藏视频选项 交互测试样片 A"); text("取消收藏").click(); text("取消").click()
                    assertEquals(0, fixture.writeCount("/x/v3/fav/resource/deal"))
                    fixture.failNextFavoriteWrite = true
                    clickDescription("收藏视频选项 交互测试样片 A"); text("取消收藏").click(); text("取消收藏").click()
                    node(By.textContains("模拟取消收藏失败")); text("取消").click(); text("交互测试样片 A")
                    clickDescription("收藏视频选项 交互测试样片 A"); text("取消收藏").click(); text("取消收藏").click()
                    await("Confirmed removal was not reflected") { fixture.writeCount("/x/v3/fav/resource/deal") == 2 }
                    fresh(); assertFalse(device.hasObject(By.text("交互测试样片 A"))); text("交互测试样片 B")
                    assertEquals("500", fixture.writeForms["/x/v3/fav/resource/deal"]!!["del_media_ids"])
                    assertEquals("100001", fixture.writeForms["/x/v3/fav/resource/deal"]!!["rid"])
                    assertFalse(fixture.writeForms["/x/v3/fav/resource/deal"]!!.containsKey("add_media_ids"))
                    fixture.loseNextFavoriteWriteResponse = true
                    clickDescription("收藏视频选项 交互测试样片 B"); text("取消收藏").click(); text("取消收藏").click()
                    text("这里还没有内容"); assertEquals(3, fixture.writeCount("/x/v3/fav/resource/deal"))
                    device.pressBack(); text("测试可删除的收藏夹")
                    clickDescription("收藏夹选项 测试可删除的收藏夹"); text("删除收藏夹").click(); text("取消").click()
                    assertEquals(0, fixture.writeCount("/x/v3/fav/folder/del"))
                    fixture.failNextFolderDelete = true
                    clickDescription("收藏夹选项 测试可删除的收藏夹"); text("删除收藏夹").click(); text("删除收藏夹").click()
                    node(By.textContains("模拟删除收藏夹失败")); text("取消").click(); text("测试可删除的收藏夹")
                    fixture.loseNextFolderDeleteResponse = true
                    clickDescription("收藏夹选项 测试可删除的收藏夹"); text("删除收藏夹").click(); text("删除收藏夹").click()
                    await("Folder write was not sent") { fixture.writeCount("/x/v3/fav/folder/del") == 2 }
                    node(By.text("模拟收藏夹")); SystemClock.sleep(300); fresh(); assertFalse(device.hasObject(By.text("测试可删除的收藏夹")))
                    assertEquals("501", fixture.writeForms["/x/v3/fav/folder/del"]!!["media_ids"])
                    assertEquals("fixture-csrf-only", fixture.writeForms["/x/v3/fav/folder/del"]!!["csrf"])
                    device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "024-favorites.png"))
                } finally { scenario.onActivity { Graph.player.stop() }; device.unfreezeRotation() }
            }
        }
    }
}
