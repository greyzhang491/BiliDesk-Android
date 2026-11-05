// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.player

import android.content.Context
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MusicStatus(val loadingPlaylist: Boolean = false, val loadedSongs: Int = 0, val error: String? = null, val lyricsLoading: Boolean = false, val lyrics: List<LyricCue> = emptyList(), val lyricsError: String? = null, val lyricVideoKey: String = "")

class MusicController(private val context: Context, private val api: BiliApi, private val engine: PlayerEngine, preferenceName: String = "music_player") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val positionPrefs = context.getSharedPreferences("${preferenceName}_position", Context.MODE_PRIVATE)
    private val saved = runCatching { JSONObject(prefs.getString("queue", "{}").orEmpty()) }.getOrDefault(JSONObject())
    private val _queue = MutableStateFlow(MusicQueueState.from(saved))
    val queue = _queue.asStateFlow()
    private val _status = MutableStateFlow(MusicStatus())
    val status = _status.asStateFlow()
    private val _recent = MutableStateFlow(runCatching { JSONArray(prefs.getString("recent", "[]")).objects().map(MusicTrack::from) }.getOrDefault(emptyList()))
    val recent = _recent.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playlistJob: Job? = null
    private var playlistGeneration = 0
    private var lyricJob: Job? = null
    private var savedPosition = positionPrefs.getLong("position", prefs.getLong("position", saved.optLong("position")))
    private var lyricKey = ""
    init { scope.launch { while (isActive) { delay(15000); snapshot() } } }
    private fun change(next: MusicQueueState) { _queue.value = next; persist(); engine.refreshSession() }
    private fun persist() { prefs.edit().putString("queue", _queue.value.json().toString()).apply(); positionPrefs.edit().putLong("position", savedPosition).apply() }
    fun snapshot() { if (engine.state.value.music) { savedPosition = engine.exo.currentPosition.coerceAtLeast(0); positionPrefs.edit().putLong("position", savedPosition).apply() } }
    fun play(video: Video, cid: Long = 0, title: String = "", position: Long = 0) { playTrack(MusicTrack(video, cid, title), position) }
    fun playTrack(track: MusicTrack, position: Long = 0) { snapshot(); savedPosition = position; change(_queue.value.add(track).select(track.key)); engine.openMusic(track, position) }
    fun resume() { val track = _queue.value.current ?: return; if (engine.state.value.music) engine.toggle() else engine.openMusic(track, savedPosition) }
    fun add(video: Video, next: Boolean = false) {
        val track = MusicTrack(video)
        if (_queue.value.tracks.isEmpty()) { playTrack(track); return }
        change(_queue.value.add(track, next))
    }
    fun playAll(videos: List<Video>) {
        val tracks = videos.filter { it.kind == "video" }.distinctBy { it.key }.map(::MusicTrack)
        if (tracks.isEmpty()) return
        snapshot(); savedPosition = 0
        change(MusicQueueState(tracks, tracks.first().key, _queue.value.mode))
        engine.openMusic(tracks.first())
    }
    fun playFolder(id: Long) {
        val token = ++playlistGeneration
        playlistJob?.cancel()
        playlistJob = scope.launch {
            _status.value = _status.value.copy(loadingPlaylist = true, loadedSongs = 0, error = null)
            try {
                val songs = linkedMapOf<String, Video>()
                var truncated = false
                for (page in 1..100) {
                    val response = api.favorites(id, page)
                    val before = songs.size
                    response.videos.filter { it.kind == "video" }.forEach { songs[it.key] = it }
                    _status.value = _status.value.copy(loadedSongs = songs.size)
                    if (!response.more || songs.size == before) break
                    if (page == 100) truncated = true
                    delay(150)
                }
                if (songs.isEmpty()) throw java.io.IOException("这个收藏夹暂无可播放内容")
                playAll(songs.values.toList())
                if (truncated) _status.value = _status.value.copy(error = "收藏夹较大，本次载入前 ${songs.size} 首；其余歌曲可从歌单单独加入")
            } catch (e: Exception) { if (e is CancellationException) throw e; _status.value = _status.value.copy(error = e.message ?: "歌单读取失败") }
            finally { if (token == playlistGeneration) _status.value = _status.value.copy(loadingPlaylist = false) }
        }
    }
    fun cancelPlaylist() { playlistJob?.cancel() }
    fun next(manual: Boolean = true) {
        snapshot()
        val next = _queue.value.advance(manual) ?: run { savedPosition = 0; positionPrefs.edit().putLong("position", 0).apply(); return }
        savedPosition = 0; change(next)
        next.current?.let { engine.openMusic(it, forceRestart = true) }
    }
    fun previous() { snapshot(); savedPosition = 0; change(_queue.value.previous()); _queue.value.current?.let { engine.openMusic(it, forceRestart = true) } }
    fun mode(mode: MusicMode) { change(_queue.value.copy(mode = mode, remaining = emptyList())) }
    fun move(from: Int, to: Int) { change(_queue.value.move(from, to)) }
    fun remove(key: String) {
        val wasCurrent = _queue.value.currentKey == key; savedPosition = if (wasCurrent) 0 else savedPosition
        change(_queue.value.remove(key))
        if (wasCurrent && engine.state.value.music) { val track = _queue.value.current; if (track == null) engine.stop() else engine.openMusic(track) }
    }
    fun clear() { if (engine.state.value.music) engine.stop(); savedPosition = 0; change(MusicQueueState(mode = _queue.value.mode)) }
    fun prepared(detail: VideoDetail, cid: Long) {
        val q = _queue.value
        _queue.value = q.copy(tracks = q.tracks.map { if (it.key == q.currentKey) it.copy(video = detail.video) else it }); persist()
        _queue.value.current?.let { track ->
            _recent.value = (listOf(track) + _recent.value.filter { it.key != track.key }).take(100)
            prefs.edit().putString("recent", JSONArray(_recent.value.map(MusicTrack::json)).toString()).apply()
        }
        val key = "${detail.video.key}:$cid"
        if (lyricKey == key) return
        lyricKey = key; lyricJob?.cancel()
        _status.value = _status.value.copy(lyricsLoading = true, lyrics = emptyList(), lyricsError = null, lyricVideoKey = detail.video.key)
        lyricJob = scope.launch {
            val dir = File(context.filesDir, "lyrics").apply { mkdirs() }; val file = File(dir, "$cid.json")
            try {
                val cues = api.lyrics(detail.video, cid)
                if (lyricKey == key) _status.value = _status.value.copy(lyrics = cues)
                if (cues.isNotEmpty()) withContext(Dispatchers.IO) {
                    file.writeText(JSONObject().put("body", JSONArray(cues.map { JSONObject().put("from", it.startMs / 1000.0).put("to", it.endMs / 1000.0).put("content", it.text) })).toString())
                    var bytes = dir.listFiles().orEmpty().sumOf { it.length() }
                    dir.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach { if (bytes > 8 * 1024 * 1024 && it != file) { bytes -= it.length(); it.delete() } }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val cached = withContext(Dispatchers.IO) { runCatching { Lyrics.parse(JSONObject(file.readText())) }.getOrDefault(emptyList()) }
                if (lyricKey == key) _status.value = _status.value.copy(lyrics = cached, lyricsError = if (cached.isEmpty()) "暂未读取到字幕" else null)
            } finally { if (lyricKey == key) _status.value = _status.value.copy(lyricsLoading = false) }
        }
    }
    fun retryLyrics() { lyricKey = ""; val d = engine.state.value.detail ?: return; val ep = engine.currentEpisode() ?: return; prepared(d, ep.cid) }
    fun release() { snapshot(); scope.cancel() }
}
