// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(kotlinx.coroutines.FlowPreview::class)
package dev.bilidesk.android.sync

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest

data class MusicSyncState(val configured: Boolean = false, val busy: Boolean = false, val account: String = "", val deviceName: String = "",
    val lastSuccess: Long = 0, val message: String = "尚未开启跨设备同步", val pending: SyncCheckpoint? = null,
    val devices: List<SyncCheckpoint> = emptyList(), val uploadPending: Boolean = false)

/** Automatic transfer and per-device recovery are separate: fetching records never changes the playing queue. */
class MusicSync(private val context: Context, private val player: PlayerEngine, private val store: Store,
    private val transport: (SyncCredentials) -> WebDavSync = { WebDavSync(it.username, it.password) }, preferenceName: String = "music_sync_device",
    private val timing: SyncTiming = SyncTiming(), private val pendingWork: () -> Unit = {}, private val cancelWork: () -> Unit = {}) {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("id", it).commit()) }
    private val secrets = SyncSecrets(context, "${preferenceName}_secrets")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val policy = AutoSyncPolicy(timing, SystemClock::elapsedRealtime)
    private val _state = MutableStateFlow(MusicSyncState())
    val state = _state.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private var credentials: SyncCredentials? = null
    private var dav: WebDavSync? = null
    private var document = SyncDocument()
    private var clock = SyncClock(deviceId)
    private var baseline = MusicLibraryState()
    private var baselineQueue = ""
    private var baselineHistory = emptyList<HistoryEntry>()
    private val acknowledged = mutableMapOf<String, SyncStamp>()
    private val acknowledgedContent = mutableMapOf<String, String>()
    private val acknowledgedPositions = mutableMapOf<String, Long>()
    private val remoteTags = mutableMapOf<String, String>()
    private var healthy = false
    private var foreground = false
    private var restoring = false
    private var generation = 0
    private var prepared = false
    private var firstPull = true
    private var uploadRequired = false
    // Dirty local progress is durable, but only an operation or a due safety checkpoint may publish it.
    // A previously queued worker must not turn ordinary progress into a fifteen-second upload loop.
    private var uploadRequested = false
    private var backgroundQueued = false
    private var preferredDevice = ""
    private var runner: Job? = null
    private val seekListener = object : androidx.media3.common.Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo,
            newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
            if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) scope.launch { captureScheduled(urgent = true) }
        }
    }

    init {
        player.exo.addListener(seekListener)
        scope.launch { try { restoreCredentials() } finally { initialized.complete(Unit) } }
        scope.launch {
            combine(player.music.library.state, player.music.queue, store.history) { _, _, _ -> Unit }.debounce(300).collect { captureScheduled() }
        }
        scope.launch {
            // Buffering while playWhenReady is true is still the same listening operation.
            player.state.map { Triple(playbackActive(), it.music, if (playbackActive()) 0L else it.position / 1000) }.distinctUntilChanged().collect { value ->
                if (!value.first) player.saveHistory()
                captureScheduled(urgent = true, forceCheckpoint = value.first && value.second)
            }
        }
        scope.launch {
            while (isActive) { delay(15000); if (healthy && (foreground || playbackActive())) captureScheduled() }
        }
    }
    private fun playbackActive() = player.state.value.loading && player.exo.playWhenReady ||
        player.exo.playWhenReady && player.exo.playbackState !in setOf(androidx.media3.common.Player.STATE_IDLE, androidx.media3.common.Player.STATE_ENDED)

    private suspend fun restoreCredentials() {
        if (healthy || restoring) return
        restoring = true
        val epoch = generation
        try {
            val config = withContext(Dispatchers.IO) { secrets.read() }
            if (config != null && load(config, epoch)) {
                if (foreground || playbackActive()) policy.requestPull(true)
                if (uploadRequired && (uploadRequested || foreground || playbackActive())) requestUpload(uploadRequested || foreground)
                wake()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _state.value = _state.value.copy(message = "暂时无法读取同步授权，已保留数据，回到应用时会自动重试")
        } finally { restoring = false }
    }
    private fun file(c: SyncCredentials) = AtomicFile(File(context.filesDir, "music-sync-${SyncCrypto.vaultId(c.recoveryKey)}-$deviceId.bdks"))
    private suspend fun load(c: SyncCredentials, epoch: Int, service: WebDavSync? = null): Boolean {
        val mirror = withContext(Dispatchers.IO) {
            val file = file(c)
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JSONObject(SyncCrypto.decrypt(file.readFully(), c.recoveryKey).toString(Charsets.UTF_8)) else null
        }
        return mutex.withLock {
            if (epoch != generation) return@withLock false
            document = mirror?.getJSONObject("document")?.let(SyncDocument::from) ?: SyncDocument()
            clock = SyncClock(deviceId, document.latest())
            baseline = mirror?.getJSONObject("baseline")?.let(MusicLibraryState::from) ?: MusicLibraryState()
            baselineQueue = mirror?.optString("queue").orEmpty()
            baselineHistory = mirror?.getJSONArray("history")?.objects()?.map { j -> HistoryEntry(Video.from(j.getJSONObject("video")), j.getLong("cid"), j.getLong("position"), j.getLong("at")) }.orEmpty()
            acknowledged.clear()
            acknowledgedContent.clear()
            acknowledgedPositions.clear()
            mirror?.optJSONArray("dismissed")?.let(SyncStamp::from)?.let { acknowledged[it.device] = it }
            mirror?.optJSONArray("acknowledged")?.objects()?.forEach { j ->
                val stamp = SyncStamp.from(j.getJSONArray("stamp")); acknowledged[stamp.device] = stamp
                j.optLong("position", -1).takeIf { it >= 0 }?.let { acknowledgedPositions[stamp.device] = it }
            }
            mirror?.optJSONArray("acknowledgedContent")?.objects()?.forEach { j -> acknowledgedContent[j.getString("device")] = j.getString("signature") }
            acknowledged.forEach { (device, stamp) -> document.allCheckpoints()[device]?.takeIf { it.stamp <= stamp }?.let {
                acknowledgedContent.putIfAbsent(device, checkpointContent(it)); acknowledgedPositions.putIfAbsent(device, it.position)
            } }
            preferredDevice = mirror?.optString("preferredDevice").orEmpty()
            uploadRequired = mirror?.optBoolean("uploadRequired", true) ?: true
            // A pre-0.2.5 pending upload remains a genuine request during an upgrade.
            uploadRequested = uploadRequired && (mirror?.optBoolean("uploadRequested", uploadRequired) ?: true)
            policy.restoreBlock((mirror?.optLong("retryAfter") ?: 0) - System.currentTimeMillis(), mirror?.optBoolean("hardBlock") ?: false)
            dav = service ?: transport(c); credentials = c; prepared = service != null; healthy = true; firstPull = true; remoteTags.clear()
            _state.value = MusicSyncState(true, account = c.username, deviceName = c.deviceName,
                lastSuccess = mirror?.optLong("lastSuccess") ?: 0, message = if (policy.blockedMillis > 0) "同步暂缓，本机修改已保留，稍后自动重试" else "自动同步已开启")
            if (capture()) uploadRequested = true
            updateDeviceRecords(); save(); true
        }
    }
    suspend fun configure(username: String, password: String, key: String, deviceName: String, create: Boolean) {
        initialized.await()
        require(username.trim().isNotEmpty() && password.isNotBlank()) { "请填写坚果云账号和第三方应用密码" }
        require(username.length <= 254 && password.length <= 512 && !username.contains(':') && !username.contains('\n') && !password.contains('\n'))
        require(deviceName.trim().length in 1..40) { "设备名称请填写1～40个字" }; SyncCrypto.key(key)
        val config = SyncCredentials(username.trim(), password.trim(), key, deviceName.trim())
        val service = transport(config)
        val epoch = mutex.withLock {
            check(!healthy) { "请先断开当前同步，再配置另一个同步库" }
            generation++
            _state.value = _state.value.copy(busy = true, message = "正在验证坚果云同步库…")
            try { service.prepare(key, create); withContext(Dispatchers.IO) { secrets.save(config) } }
            catch (e: Exception) { _state.value = _state.value.copy(busy = false, message = safeError(e)); throw e }
            generation
        }
        try {
            if (load(config, epoch, service)) {
                perform(AutoSyncAction(true, true))
                if (uploadRequired) requestUpload(uploadRequested)
                wake()
            }
        } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(busy = false, message = safeError(e)) }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!healthy) { if (value) scope.launch { initialized.await(); restoreCredentials() }; return }
        if (value) policy.requestPull(true)
        scope.launch { captureScheduled(urgent = true); wake() }
    }
    fun onMusicVisible() { if (healthy) { policy.requestPull(true); wake() } }
    fun onPlaybackViewLeaving() { if (healthy) scope.launch { captureScheduled(urgent = true) } }
    fun onNetworkRecovered() {
        if (!healthy) { scope.launch { initialized.await(); restoreCredentials() }; return }
        policy.networkRecovered(); if (uploadRequired && uploadRequested) requestUpload(true); wake()
    }
    /** Optional recovery control; normal use is driven by lifecycle, edits and the connected background task. */
    fun sync() {
        if (!healthy) return
        policy.networkRecovered()
        scope.launch { captureScheduled(urgent = true); wake() }
    }
    private fun wake() {
        signals.trySend(Unit)
        if (!healthy || runner?.isActive == true) return
        runner = scope.launch {
            while (isActive && healthy) {
                val active = foreground || playbackActive()
                policy.ensurePoll(active, foreground)
                val action = policy.take(active)
                if (action != null) perform(action)
                else withTimeoutOrNull(policy.waitMillis(active)) { signals.receive() }
            }
        }
    }
    private fun scheduleBackground() {
        if (!uploadRequired || !uploadRequested || backgroundQueued) return
        try { pendingWork(); backgroundQueued = true } catch (_: Exception) { /* Foreground scheduling remains available. */ }
    }
    private fun requestUpload(urgent: Boolean) {
        if (!uploadRequired) return
        if (urgent) uploadRequested = true
        policy.changed(uploadRequested)
        scheduleBackground()
    }
    private suspend fun captureScheduled(urgent: Boolean = false, forceCheckpoint: Boolean = false) {
        if (!healthy) return
        try {
            mutex.withLock {
                val structure = capture(forceCheckpoint = forceCheckpoint)
                if (uploadRequired) requestUpload(urgent || structure || !playbackActive())
                updateDeviceRecords()
                save()
            }
            wake()
        } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(message = safeError(e)) }
    }
    internal suspend fun syncNow() { perform(AutoSyncAction(true, true)) }
    suspend fun flushBackground(): Boolean {
        initialized.await()
        if (!healthy) restoreCredentials()
        if (!healthy) return !secrets.exists
        mutex.withLock { if (capture()) uploadRequested = true; updateDeviceRecords(); save() }
        if (!uploadRequired || !uploadRequested) { backgroundQueued = false; return true }
        if (policy.blockedMillis > 0) return false
        perform(AutoSyncAction(false, true))
        return !uploadRequested
    }
    private suspend fun perform(action: AutoSyncAction) = mutex.withLock {
        val c = credentials ?: return@withLock; val service = dav ?: return@withLock
        if (!healthy) return@withLock
        _state.value = _state.value.copy(busy = true, message = "正在自动同步…")
        var pulled = false; var pushed = false
        try {
            var structure = capture()
            if (structure) uploadRequested = true
            save()
            if (!prepared) { service.prepare(c.recoveryKey, false); prepared = true }
            if (action.pull) {
                val entries = service.list(); val tags = remoteTags.toMutableMap()
                var combined = document
                for (entry in entries) {
                    if (entry.name == "device-$deviceId.bdks" && !firstPull) continue
                    if (entry.etag != null && remoteTags[entry.name] == entry.etag) continue
                    combined = combined.merge(service.read(entry.name, c.recoveryKey))
                    if (entry.etag != null) tags[entry.name] = entry.etag else tags.remove(entry.name)
                }
                if (entries.none { it.name == "device-$deviceId.bdks" }) { uploadRequired = true; uploadRequested = true; structure = true }
                document = combined; clock.observe(document.latest())
                structure = capture(applyCloud = true) || structure
                if (structure) uploadRequested = true
                updateDeviceRecords(); save()
                remoteTags.clear(); remoteTags.putAll(tags.filterKeys { name -> entries.any { it.name == name } })
                firstPull = false; pulled = true
            }
            if (uploadRequired && (action.push || structure)) {
                uploadRequested = true; save()
                service.write(deviceId, document, c.recoveryKey)
                uploadRequired = false; uploadRequested = false; backgroundQueued = false; pushed = true
                // Capture edits made during the upload before clearing durable pending state.
                structure = capture()
                if (uploadRequired) requestUpload(structure)
                save()
            }
            policy.success(pulled, pushed)
            if (uploadRequired) requestUpload(structure)
            _state.value = _state.value.copy(busy = false, lastSuccess = if (pulled || pushed) System.currentTimeMillis() else _state.value.lastSuccess, uploadPending = uploadRequired,
                message = if (uploadRequired) "已更新设备记录，本机修改将自动上传" else "自动同步已开启")
            updateDeviceRecords(); save()
        } catch (e: Exception) {
            if (e is CancellationException) { _state.value = _state.value.copy(busy = false); throw e }
            val remote = e as? SyncRemoteException
            policy.failed(action.copy(push = action.push || uploadRequested), remote?.status ?: 0, remote?.retryAfterMs ?: 0)
            _state.value = _state.value.copy(busy = false, uploadPending = uploadRequired, message = safeError(e))
            if (uploadRequired) scheduleBackground()
            runCatching { save() }
        }
    }

    /** Returns true for structural edits. A fetched device checkpoint is never projected into the local queue. */
    private suspend fun capture(applyCloud: Boolean = false, forceCheckpoint: Boolean = false): Boolean {
        if (!healthy) return false
        player.music.snapshot()
        val c = credentials ?: return false
        var structure = false
        var captured = baseline
        player.music.library.syncUpdate { current ->
            val updated = document.captureLibrary(baseline, current, clock::next)
            if (updated != document) { structure = true; uploadRequired = true }
            document = updated
            captured = if (applyCloud) {
                val metadata = current.playlists.flatMap { it.tracks }.associateBy { it.key }
                MusicLibraryState(document.library().playlists.map { list -> list.copy(tracks = list.tracks.map { t -> metadata[t.key]?.let { t.copy(video = it.video) } ?: t }) })
            } else current
            captured
        }
        baseline = captured
        val queue = player.music.queue.value; val position = player.music.resumePosition
        val signature = queueSignature(queue, position)
        if (signature != baselineQueue || forceCheckpoint) {
            val previous = runCatching { JSONObject(baselineQueue).put("position", 0).toString() }.getOrNull()
            if (queueSignature(queue, 0) != previous || forceCheckpoint) structure = true
            if (queue.current != null || baselineQueue.isNotEmpty()) {
                val stamp = if (baselineQueue.isEmpty() && !playbackActive()) {
                    val at = store.history.value.firstOrNull { it.video.key == queue.current?.video?.key }?.at ?: 0
                    SyncStamp(at, 0, deviceId)
                } else clock.next()
                document = document.withCheckpoint(SyncCheckpoint(queue, position, document.deviceNames[deviceId]?.name ?: c.deviceName, stamp)); uploadRequired = true
            }
            baselineQueue = signature
        }
        var capturedHistory = baselineHistory
        store.syncHistory { history ->
            val previous = baselineHistory.associateBy { it.video.key }
            val progress = document.progress.toMutableMap()
            history.filter { it.video.kind == "video" && it.cid > 0 }.forEach { h ->
                val old = previous[h.video.key]
                if (old == null || old.cid != h.cid || old.positionMs != h.positionMs || old.at != h.at) {
                    if (old == null || old.cid != h.cid) structure = true
                    progress[h.video.key] = SyncProgress(h, clock.next()); uploadRequired = true
                }
            }
            document = document.copy(progress = progress.values.sortedByDescending { it.stamp }.take(200).associateBy { it.entry.video.key })
            val metadata = history.associateBy { it.video.key }
            capturedHistory = if (applyCloud) document.progress.values.sortedByDescending { it.stamp }.map { p ->
                p.entry.copy(video = metadata[p.entry.video.key]?.video?.takeIf { it.cover.isNotBlank() } ?: p.entry.video)
            } else history
            capturedHistory
        }
        baselineHistory = capturedHistory
        return structure
    }
    private fun queueSignature(queue: MusicQueueState, position: Long) = SyncCheckpoint(queue, position, "", SyncStamp(0, 0, deviceId)).json().getJSONObject("queue").toString()
    /** A timestamp/metadata-only re-upload is not a new listening session. Ignore sub-second final flushes. */
    private fun checkpointContent(checkpoint: SyncCheckpoint): String {
        val queue = checkpoint.queue
        val content = JSONObject().put("tracks", JSONArray(queue.tracks.map { it.key })).put("current", queue.currentKey)
            .put("mode", queue.mode.name).put("next", JSONArray(queue.nextUp)).put("remaining", JSONArray(queue.remaining)).toString()
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun acknowledge(checkpoint: SyncCheckpoint) {
        acknowledged[checkpoint.stamp.device] = checkpoint.stamp
        acknowledgedContent[checkpoint.stamp.device] = checkpointContent(checkpoint)
        acknowledgedPositions[checkpoint.stamp.device] = checkpoint.position
    }
    private fun updateDeviceRecords() {
        val devices = document.allCheckpoints().values.filter { it.stamp.device != deviceId }.map { record ->
            document.deviceNames[record.stamp.device]?.let { record.copy(deviceName = it.name) } ?: record
        }.sortedByDescending { it.stamp }
        val localStamp = document.allCheckpoints()[deviceId]?.stamp
        val unseen = if (playbackActive()) emptyList() else devices.filter { record ->
            (localStamp == null || record.stamp > localStamp) && (acknowledged[record.stamp.device]?.let { record.stamp > it } ?: true) &&
                !(acknowledgedContent[record.stamp.device] == checkpointContent(record) &&
                    acknowledgedPositions[record.stamp.device]?.let { kotlin.math.abs(record.position - it) <= 2000 } == true)
        }
        _state.value = _state.value.copy(devices = devices, pending = unseen.firstOrNull { it.stamp.device == preferredDevice } ?: unseen.firstOrNull(),
            deviceName = document.deviceNames[deviceId]?.name ?: credentials?.deviceName.orEmpty(), uploadPending = uploadRequired)
    }
    private suspend fun save() {
        val c = credentials ?: return
        val j = JSONObject().put("document", document.json()).put("baseline", baseline.json()).put("queue", baselineQueue)
            .put("acknowledged", JSONArray(acknowledged.toSortedMap().values.map { JSONObject().put("stamp", it.json()).put("position", acknowledgedPositions[it.device] ?: -1) }))
            .put("acknowledgedContent", JSONArray(acknowledgedContent.toSortedMap().map { (device, signature) -> JSONObject().put("device", device).put("signature", signature) }))
            .put("preferredDevice", preferredDevice).put("uploadRequired", uploadRequired).put("uploadRequested", uploadRequested).put("lastSuccess", _state.value.lastSuccess)
            .put("retryAfter", if (policy.blockedMillis > 0) System.currentTimeMillis() + policy.blockedMillis else 0).put("hardBlock", policy.isHardBlocked)
            .put("history", JSONArray(baselineHistory.map { h -> JSONObject().put("video", h.video.json()).put("cid", h.cid).put("position", h.positionMs).put("at", h.at) }))
        withContext(Dispatchers.IO) {
            val bytes = SyncCrypto.encrypt(j.toString().toByteArray(Charsets.UTF_8), c.recoveryKey); val file = file(c); val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        }
    }
    fun resumeRemote() { _state.value.pending?.let(::resumeRemote) }
    suspend fun renameDevice(device: String, value: String) {
        initialized.await()
        val name = SyncDeviceName.validName(value)
        mutex.withLock {
            val c = credentials ?: throw IllegalStateException("请先开启跨设备同步")
            require(device == deviceId || document.allCheckpoints().containsKey(device)) { "设备记录暂时不可用，请刷新后重试" }
            if ((document.deviceNames[device]?.name ?: if (device == deviceId) c.deviceName else document.allCheckpoints()[device]?.deviceName) == name) return@withLock
            if (device == deviceId) {
                val renamed = SyncCredentials(c.username, c.password, c.recoveryKey, name)
                withContext(Dispatchers.IO) { secrets.save(renamed) }; credentials = renamed
            }
            val label = SyncDeviceName(device, name, clock.next())
            document = document.copy(deviceNames = document.deviceNames + (device to label)); uploadRequired = true
            requestUpload(true); updateDeviceRecords(); save()
        }
        wake()
    }
    fun resumeRemote(checkpoint: SyncCheckpoint) {
        if (checkpoint.stamp.device == deviceId || document.allCheckpoints()[checkpoint.stamp.device] == null) return
        scope.launch { try {
            mutex.withLock {
                capture(); clock.observe(checkpoint.stamp)
                player.music.restoreSynced(checkpoint.queue, checkpoint.position, start = true)
                acknowledge(checkpoint); preferredDevice = checkpoint.stamp.device
                baselineQueue = "handoff"; capture(forceCheckpoint = true); uploadRequired = true; requestUpload(true); updateDeviceRecords(); save()
            }
            wake()
        } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(message = safeError(e)) } }
    }
    fun dismissRemote() { _state.value.pending?.let(::dismissRemote) }
    fun dismissRemote(checkpoint: SyncCheckpoint) { scope.launch { try { mutex.withLock { acknowledge(checkpoint); updateDeviceRecords(); save() } } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(message = safeError(e)) } } }
    suspend fun recoveryKey(): String? = mutex.withLock { credentials?.recoveryKey }
    suspend fun disconnect() {
        initialized.await(); runner?.cancelAndJoin()
        mutex.withLock {
            generation++; capture(); save(); withContext(Dispatchers.IO) { secrets.clear() }
            healthy = false; credentials = null; dav = null; prepared = false; backgroundQueued = false; remoteTags.clear()
            cancelWork(); _state.value = MusicSyncState(message = "已断开，本机歌单和各设备记录均已保留")
        }
    }
    fun release() { player.exo.removeListener(seekListener); runner?.cancel(); scope.cancel() }
    private fun safeError(e: Exception): String = when {
        e is CancellationException -> "同步已取消，本机修改已保留"
        e is IllegalArgumentException -> e.message ?: "同步数据不正确，本机内容已保留"
        e is SyncRemoteException -> e.message ?: "同步服务暂时不可用，本机修改已保留"
        e is java.io.IOException && e.message?.startsWith("还没有") == true -> e.message!!
        else -> "暂时无法同步，本机修改已保留，网络恢复后会自动重试"
    }
}
