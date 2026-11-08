// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

import android.content.Context
import dev.bilidesk.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

data class MusicPlaylist(val id: String, val name: String, val tracks: List<MusicTrack> = emptyList()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("tracks", JSONArray(tracks.map(MusicTrack::json)))
}
data class MusicLibraryState(val playlists: List<MusicPlaylist> = emptyList()) {
    fun create(id: String, name: String): MusicLibraryState {
        require(id.isNotBlank() && playlists.none { it.id == id }) { "歌单已存在" }
        require(playlists.size < 200) { "本机最多保存200个歌单" }
        return copy(playlists = playlists + MusicPlaylist(id, validName(name)))
    }
    fun rename(id: String, name: String): MusicLibraryState { val value = validName(name); requirePlaylist(id); return copy(playlists = playlists.map { if (it.id == id) it.copy(name = value) else it }) }
    fun delete(id: String) = copy(playlists = playlists.filter { it.id != id })
    fun add(id: String, tracks: List<MusicTrack>): MusicLibraryState {
        val playlist = requirePlaylist(id)
        val merged = (playlist.tracks + tracks.filter { it.video.kind == "video" }).distinctBy { it.key }
        require(merged.size <= 5000) { "单个歌单最多保存5000首，请拆分歌单" }
        return copy(playlists = playlists.map { if (it.id == id) it.copy(tracks = merged) else it })
    }
    fun remove(id: String, key: String): MusicLibraryState { requirePlaylist(id); return copy(playlists = playlists.map { if (it.id == id) it.copy(tracks = it.tracks.filterNot { track -> track.key == key }) else it }) }
    private fun requirePlaylist(id: String) = requireNotNull(playlists.firstOrNull { it.id == id }) { "歌单已被删除，请重新选择" }
    fun json() = JSONObject().put("version", 1).put("playlists", JSONArray(playlists.map(MusicPlaylist::json)))
    companion object {
        fun validName(raw: String): String = raw.trim().also { require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 40) { "歌单名称请填写1～40个字" } }
        fun from(j: JSONObject) = MusicLibraryState(j.arr("playlists").objects().mapNotNull { row -> runCatching {
            val id = row.getString("id"); require(id.isNotBlank())
            MusicPlaylist(id, validName(row.getString("name")), row.arr("tracks").objects().mapNotNull { runCatching { MusicTrack.from(it) }.getOrNull() }.distinctBy { it.key }.take(5000))
        }.getOrNull() }.distinctBy { it.id }.take(200))
    }
}

/** User-created playlists are durable data, independent of queue snapshots and disposable caches. */
class MusicLibrary(context: Context, preferenceName: String) {
    private val prefs = context.getSharedPreferences("${preferenceName}_library", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(runCatching { MusicLibraryState.from(JSONObject(prefs.getString("library", "{}").orEmpty())) }.getOrDefault(MusicLibraryState()))
    val state = _state.asStateFlow()
    internal suspend fun syncUpdate(block: (MusicLibraryState) -> MusicLibraryState) = change(block)
    private suspend fun change(block: (MusicLibraryState) -> MusicLibraryState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val next = block(_state.value)
            if (next == _state.value) return@withContext
            if (!prefs.edit().putString("library", next.json().toString()).commit()) throw IOException("歌单保存失败，请检查设备存储空间")
            _state.value = next
        }
    }
    suspend fun create(name: String, tracks: List<MusicTrack> = emptyList()): String { val id = UUID.randomUUID().toString(); change { it.create(id, name).add(id, tracks) }; return id }
    suspend fun rename(id: String, name: String) = change { it.rename(id, name) }
    suspend fun delete(id: String) = change { it.delete(id) }
    suspend fun add(id: String, tracks: List<MusicTrack>) = change { it.add(id, tracks) }
    suspend fun remove(id: String, key: String) = change { it.remove(id, key) }
}

object MusicCollections {
    fun tracks(detail: VideoDetail): List<MusicTrack> {
        val parts = detail.pages.map { MusicTrack(detail.video, it.cid, it.title) }
        val collection = detail.collection?.entries.orEmpty().map { MusicTrack(it.video, it.cid, it.title) }
        return (parts + collection).distinctBy { it.key }
    }
}
