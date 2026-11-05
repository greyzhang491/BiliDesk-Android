// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.bilidesk.android.Graph
import dev.bilidesk.android.MainActivity
import dev.bilidesk.android.R
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

data class DownloadTask(val key: String, val video: Video, val episode: Episode, val state: String = "等待下载", val progress: Float = 0f, val error: String? = null, val running: Boolean = true, val automatic: Boolean = false, val paused: Boolean = false, val downloadedBytes: Long = 0, val totalBytes: Long = -1, val bytesPerSecond: Long = 0, val requestedQuality: Int = 80, val audioOnly: Boolean = false)

object DownloadChoice {
    fun key(videoKey: String, cid: Long, quality: Int, audioOnly: Boolean) = "$videoKey-$cid" + when { audioOnly -> "-audio"; quality == 80 -> ""; else -> "-video-q$quality" }
}

class Downloads(private val context: Context, private val api: BiliApi, private val store: Store) {
    private val queuePrefs = context.getSharedPreferences("download_queue", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _tasks = MutableStateFlow(restoreQueue())
    val tasks = _tasks.asStateFlow()
    private var worker: Job? = null
    private var activeJob: Job? = null
    private var activeKey: String? = null
    private var activeAutomatic = false
    internal val isIdle get() = worker?.isCompleted != false
    private val client = api.http.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(0, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val downloader = RangeDownloader(client)
    private fun restoreQueue(): List<DownloadTask> = runCatching {
        JSONArray(queuePrefs.getString("tasks", "[]")).objects().map { j ->
            val e = j.obj("episode")
            DownloadTask(j.getString("key"), Video.from(j.obj("video")), Episode(e.getLong("cid"), e.optString("title"), e.optLong("duration"), e.optString("bvid"), e.optLong("aid"), e.optLong("epid")),
                state = "已暂停，点击继续下载", progress = j.optDouble("progress").toFloat(), running = false, paused = true, downloadedBytes = j.optLong("bytes"), requestedQuality = j.optInt("quality", 80), audioOnly = j.optBoolean("audioOnly"))
        }
    }.getOrDefault(emptyList())
    private fun persistQueue() {
        val pending = _tasks.value.filter { !it.automatic && it.state !in listOf("缓存完成", "已取消") }
        queuePrefs.edit().putString("tasks", JSONArray(pending.map { t -> JSONObject().put("key", t.key).put("video", t.video.json()).put("progress", t.progress).put("bytes", t.downloadedBytes).put("quality", t.requestedQuality).put("audioOnly", t.audioOnly)
            .put("episode", JSONObject().put("cid", t.episode.cid).put("title", t.episode.title).put("duration", t.episode.duration).put("bvid", t.episode.bvid).put("aid", t.episode.aid).put("epid", t.episode.epid)) }).toString()).apply()
    }
    private fun partials(key: String): List<File> {
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return listOf("video", "audio", "mux").map { File(context.filesDir, "media/$safe.$it.part") }
    }
    init {
        // Keep only resumable manual tasks; interrupted automatic downloads must not accumulate.
        val keep = _tasks.value.flatMap { partials(it.key).flatMap { f -> listOf(f.name, f.name + ".resume", f.name + ".resume.tmp") } }.toSet()
        File(context.filesDir, "media").listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || ".part.resume" in it.name) && it.name !in keep }?.forEach { it.delete() }
        scope.launch {
            store.settings.collect { s ->
                if (!s.shortCache) _tasks.value.filter { it.automatic && it.running }.forEach { cancel(it.key) }
            }
        }
    }
    private fun automaticAllowed(): Boolean {
        val s = store.settings.value
        if (!s.shortCache) return false
        val network = context.getSystemService(ConnectivityManager::class.java)
        val caps = network.getNetworkCapabilities(network.activeNetwork) ?: return false
        return !s.autoCacheWifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !network.isActiveNetworkMetered
    }
    fun enqueue(video: Video, episode: Episode, automatic: Boolean = false, quality: Int = 80, audioOnly: Boolean = false) {
        if (video.kind == "live") return
        if (automatic && (!automaticAllowed() || episode.duration !in 1..180)) return
        val key = DownloadChoice.key(video.key, episode.cid, quality, audioOnly)
        store.caches.value.firstOrNull { it.key == key }?.let {
            if (!automatic && it.automatic) store.addCache(it.copy(automatic = false))
            return
        }
        if (_tasks.value.any { it.key == key && it.running }) {
            if (!automatic) {
                update(key) { it.copy(automatic = false) }
                if (activeKey == key) activeAutomatic = false
            }
            return
        }
        if (!automatic && activeAutomatic) activeKey?.let { cancel(it) }
        val old = _tasks.value.firstOrNull { it.key == key }
        _tasks.value = _tasks.value.filter { it.key != key && (it.running || it.paused || it.error != null) } + DownloadTask(key, video, episode, automatic = automatic, progress = old?.progress ?: 0f, downloadedBytes = old?.downloadedBytes ?: 0, requestedQuality = quality, audioOnly = audioOnly)
        persistQueue()
        try { ContextCompat.startForegroundService(context, Intent(context, CacheDownloadService::class.java)) }
        catch (e: Exception) { update(key) { it.copy(state = "无法启动下载", running = false, error = e.message) } }
    }
    fun cancel(key: String) {
        update(key) { it.copy(state = "已取消", running = false, paused = false, error = null, bytesPerSecond = 0) }
        if (activeKey == key) activeJob?.cancel()
        else scope.launch(Dispatchers.IO) { partials(key).forEach(downloader::discard) }
    }
    fun pause(key: String) {
        update(key) { it.copy(state = "已暂停", running = false, paused = true, error = null, bytesPerSecond = 0) }
        if (activeKey == key) activeJob?.cancel()
    }
    fun pauseAll() { _tasks.value.filter { it.running }.forEach { pause(it.key) } }
    internal fun close() { pauseAll(); scope.cancel() }
    fun start(service: CacheDownloadService) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val next = _tasks.value.filter { it.running && it.state == "等待下载" }.minByOrNull { it.automatic } ?: break
                activeKey = next.key
                activeAutomatic = next.automatic
                activeJob = launch {
                    try { runTask(next, service) }
                    catch (e: Exception) {
                        if (e is CancellationException) { update(next.key) { if (it.running && it.state != "等待下载") it.copy(state = "已暂停", running = false, paused = true, bytesPerSecond = 0) else it } }
                        else update(next.key) { it.copy(state = "下载失败，已保留进度", running = false, error = e.message ?: "网络或存储异常", bytesPerSecond = 0) }
                    }
                }
                activeJob?.join(); activeKey = null; activeAutomatic = false
            }
            service.finishDownloads()
        }
    }
    private fun update(key: String, change: (DownloadTask) -> DownloadTask) {
        val old = _tasks.value.firstOrNull { it.key == key }
        _tasks.value = _tasks.value.map { if (it.key == key) change(it) else it }
        val next = _tasks.value.firstOrNull { it.key == key }
        if (old?.state != next?.state || old?.automatic != next?.automatic) persistQueue()
    }
    private suspend fun runTask(task: DownloadTask, service: CacheDownloadService) {
        if (task.automatic && !automaticAllowed()) { update(task.key) { it.copy(state = "已跳过自动缓存", running = false) }; return }
        update(task.key) { it.copy(state = "解析视频") }
        // AVC avoids the HEVC configuration parser failure in local playback as well.
        // Request an authorized compatible stream, then remux without re-encoding.
        var plan = api.play(task.video, task.episode, task.requestedQuality, forceAvc = true)
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        var payloadLimit = Long.MAX_VALUE
        if (task.automatic) {
            val quota = store.settings.value.autoCacheMaxMb * CachePolicy.MIB
            val estimate = ((plan.video.bandwidth + (plan.audio?.bandwidth ?: 0)) * task.episode.duration / 8 * 1.15).toLong().coerceAtLeast(4 * CachePolicy.MIB)
            if (estimate > quota) { update(task.key) { it.copy(state = "视频超过自动缓存上限，已跳过", running = false) }; return }
            withContext(Dispatchers.IO) { store.pruneAutomaticCache(estimate) }
            payloadLimit = quota - store.caches.value.filter { it.automatic }.sumOf { it.bytes }
            if (payloadLimit < estimate || dir.usableSpace < estimate * 2 + 512 * CachePolicy.MIB) {
                update(task.key) { it.copy(state = "存储空间不足，已跳过自动缓存", running = false) }; return
            }
        }
        val safeKey = task.key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val videoFile = File(dir, "$safeKey.video.part")
        val audioFile = File(dir, "$safeKey.audio.part")
        val mergedFile = File(dir, "$safeKey.mux.part")
        val target = File(dir, "$safeKey.${if (task.audioOnly) "m4a" else "mp4"}")
        try {
            var refreshed = false
            while (true) {
                try {
                    if (task.audioOnly) transfer(task, plan.audio ?: plan.video, videoFile, "音频", 0f, .92f, service, payloadLimit, 0)
                    else {
                        transfer(task, plan.video, videoFile, "视频", 0f, if (plan.audio != null) .75f else .92f, service, payloadLimit, 0)
                        if (plan.audio != null) transfer(task, plan.audio!!, audioFile, "音频", .75f, .17f, service, payloadLimit - videoFile.length(), videoFile.length())
                    }
                    break
                } catch (e: DownloadHttpException) {
                    if (refreshed || e.status !in setOf(403, 410)) throw e
                    refreshed = true; update(task.key) { it.copy(state = "正在刷新失效的下载地址", bytesPerSecond = 0) }
                    plan = api.play(task.video, task.episode, task.requestedQuality, forceAvc = true)
                }
            }
            update(task.key) { it.copy(state = if (task.audioOnly) "正在保存音频" else "正在合并音视频", progress = .92f, bytesPerSecond = 0) }
            withContext(Dispatchers.IO) {
                try {
                    remux(if (task.audioOnly || plan.audio == null) listOf(videoFile) else listOf(videoFile, audioFile), mergedFile, task.audioOnly) { portion ->
                        withContext(Dispatchers.Main) { update(task.key) { it.copy(progress = .92f + .075f * portion) }; service.progress("正在合并 · ${task.video.title}", (92 + 7.5 * portion).toInt()) }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) { downloader.discard(videoFile); downloader.discard(audioFile) }
                    throw e
                }
                ensureActive()
                if (task.automatic && mergedFile.length() > payloadLimit) throw DownloadLimitExceeded("超过自动缓存上限，已停止")
                if (!mergedFile.renameTo(target)) throw java.io.IOException("无法保存缓存文件")
                val keepAutomatically = task.automatic && _tasks.value.firstOrNull { it.key == task.key }?.automatic != false
                store.addCache(CacheEntry(task.key, task.video, task.episode.cid, task.episode.title, "media/${target.name}", target.length(), automatic = keepAutomatically, type = if (task.audioOnly) "audio" else "video", quality = if (task.audioOnly) 0 else plan.video.quality))
                store.pruneAutomaticCache()
            }
            update(task.key) { it.copy(state = "缓存完成", progress = 1f, running = false, paused = false, bytesPerSecond = 0, downloadedBytes = target.length(), totalBytes = target.length()) }
        } finally { withContext(NonCancellable + Dispatchers.IO) {
            mergedFile.delete()
            val current = _tasks.value.firstOrNull { it.key == task.key }
            if (task.automatic || current?.state in listOf("已取消", "缓存完成")) { downloader.discard(videoFile); downloader.discard(audioFile) }
        } }
    }
    private suspend fun transfer(task: DownloadTask, stream: Stream, destination: File, label: String, base: Float, weight: Float, service: CacheDownloadService, limit: Long, before: Long) {
        val urls = (listOf(stream.url) + stream.backups).distinct().sortedBy { val host = it.toHttpUrl().host; if (".mcdn." in host || host.endsWith("szbdyd.com")) 1 else 0 }
        val identity = "${task.key}:$label:${stream.quality}:${stream.codec}:${stream.bandwidth}:${stream.url.toHttpUrl().encodedPath}"
        downloader.download(urls, destination, identity, task.video.url, limit, checkAllowed = {
            if (task.automatic && (!automaticAllowed() || destination.parentFile!!.usableSpace < 512 * CachePolicy.MIB)) throw DownloadLimitExceeded("网络或剩余空间变化，已停止自动缓存")
        }) { p ->
            withContext(Dispatchers.Main) {
                val amount = if (p.total > 0) base + p.bytes.toFloat() / p.total * weight else base
                update(task.key) { it.copy(state = if (p.retry > 0) "网络中断，重试 ${p.retry}/4" else "正在下载$label", progress = amount.coerceIn(0f, .92f), downloadedBytes = before + p.bytes, totalBytes = if (p.total > 0) before + p.total else -1, bytesPerSecond = p.bytesPerSecond) }
                service.progress("${task.video.title} · ${if (p.retry > 0) "正在重试" else "$label ${downloadSize(p.bytes)}"}", (amount * 100).toInt())
            }
        }
    }
    private suspend fun remux(sources: List<File>, output: File, audioOnly: Boolean, progress: suspend (Float) -> Unit) {
        val extractors = mutableListOf<MediaExtractor>()
        val tracks = mutableListOf<Triple<MediaExtractor, Int, Int>>()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            for (source in sources) {
                val ex = MediaExtractor().apply { setDataSource(source.absolutePath) }; extractors.add(ex)
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i); val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if ((mime.startsWith("audio/") || !audioOnly && mime.startsWith("video/")) && tracks.none { it.first.getTrackFormat(it.second).getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/') == mime.substringBefore('/') }) tracks.add(Triple(ex, i, muxer.addTrack(format)))
                }
            }
            require(tracks.any { it.first.getTrackFormat(it.second).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(if (audioOnly) "audio/" else "video/") }) { if (audioOnly) "此来源没有可保存的音轨" else "缓存没有有效的视频轨道" }
            muxer.start(); started = true
            val maxSize = tracks.maxOf { val f = it.first.getTrackFormat(it.second); if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(4 * 1024 * 1024) else 4 * 1024 * 1024 }.coerceAtMost(32 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxSize); val info = MediaCodec.BufferInfo()
            var lastReport = 0L
            for ((index, entry) in tracks.withIndex()) {
                val (ex, track, outTrack) = entry
                val duration = ex.getTrackFormat(track).let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0 }
                ex.selectTrack(track); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    currentCoroutineContext().ensureActive(); buffer.clear()
                    val size = ex.readSampleData(buffer, 0); if (size < 0) break
                    info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outTrack, buffer, info); ex.advance()
                    val now = System.nanoTime()
                    if (now - lastReport > 500_000_000L) { lastReport = now; progress((index + if (duration > 0) (ex.sampleTime.toFloat() / duration).coerceIn(0f, 1f) else 0f) / tracks.size) }
                }
                ex.unselectTrack(track)
            }
            muxer.stop(); started = false
        } finally { if (started) runCatching { muxer.stop() }; muxer.release(); extractors.forEach { it.release() } }
    }
}

fun downloadSize(bytes: Long): String = if (bytes < 1024 * 1024) "%.0f KB".format(bytes.coerceAtLeast(0) / 1024.0) else "%.1f MB".format(bytes / 1048576.0)

class CacheDownloadService: Service() {
    private val notificationId = 4002
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "离线缓存", NotificationManager.IMPORTANCE_LOW))
        startForeground(notificationId, notification("准备下载", 0))
    }
    private fun notification(title: String, progress: Int): Notification {
        val intent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("open_downloads", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "downloads").setSmallIcon(R.drawable.ic_launcher).setContentTitle("BiliDesk · 离线缓存").setContentText(title).setProgress(100, progress, progress == 0).setOngoing(true).setContentIntent(intent).build()
    }
    fun progress(title: String, progress: Int) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(notificationId, notification(title, progress))
        }
    }
    fun finishDownloads() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { Graph.downloads.start(this); return START_NOT_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { Graph.downloads.pauseAll(); finishDownloads() }
    override fun onDestroy() { Graph.downloads.pauseAll(); super.onDestroy() }
}
