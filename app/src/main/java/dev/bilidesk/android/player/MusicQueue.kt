// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

import dev.bilidesk.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

enum class MusicMode(val label: String) { ORDER("顺序播放"), LOOP("列表循环"), ONE("单曲循环"), SHUFFLE("随机播放") }
data class MusicTrack(val video: Video, val cid: Long = 0, val title: String = "", val key: String = "${video.key}:$cid") {
    val displayTitle get() = title.ifBlank { video.title }
    fun json() = JSONObject().put("video", video.json()).put("cid", cid).put("title", title).put("key", key)
    companion object { fun from(j: JSONObject): MusicTrack { val v = Video.from(j.obj("video")); val cid = j.optLong("cid"); return MusicTrack(v, cid, j.optString("title"), j.optString("key").ifBlank { "${v.key}:$cid" }) } }
}
data class MusicQueueState(val tracks: List<MusicTrack> = emptyList(), val currentKey: String = "", val mode: MusicMode = MusicMode.ORDER, val nextUp: List<String> = emptyList(), val remaining: List<String> = emptyList(), val history: List<String> = emptyList()) {
    val current get() = tracks.firstOrNull { it.key == currentKey }
    fun add(track: MusicTrack, next: Boolean = false): MusicQueueState {
        val list = if (tracks.any { it.key == track.key }) tracks else tracks + track
        return copy(tracks = list, currentKey = currentKey.ifBlank { track.key }, nextUp = if (next) (nextUp + track.key).distinct() else nextUp)
    }
    fun select(key: String): MusicQueueState = if (tracks.none { it.key == key }) this else copy(currentKey = key, nextUp = nextUp.filter { it != key }, remaining = remaining.filter { it != key }, history = if (currentKey.isNotBlank() && currentKey != key) (history + currentKey).takeLast(300) else history)
    fun advance(manual: Boolean, random: Random = Random.Default): MusicQueueState? {
        if (tracks.isEmpty()) return null
        val priority = nextUp.firstOrNull { queued -> tracks.any { it.key == queued } }
        if (priority != null) return select(priority).copy(nextUp = nextUp.dropWhile { it != priority }.drop(1))
        if (mode == MusicMode.ONE && !manual) return this
        if (mode == MusicMode.SHUFFLE) {
            val available = remaining.filter { key -> key != currentKey && tracks.any { it.key == key } }.ifEmpty { tracks.map { it.key }.filter { it != currentKey }.shuffled(random) }
            val key = available.firstOrNull() ?: currentKey
            return select(key).copy(remaining = available.drop(1))
        }
        val index = tracks.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        if (!manual && mode == MusicMode.ORDER && index == tracks.lastIndex) return null
        return select(tracks[(index + 1) % tracks.size].key)
    }
    fun previous(): MusicQueueState {
        val last = history.lastOrNull { key -> tracks.any { it.key == key } }
        if (last != null) return copy(currentKey = last, history = history.dropLast(1), remaining = remaining.filter { it != last })
        if (tracks.isEmpty()) return this
        val index = tracks.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        return copy(currentKey = tracks[(index - 1 + tracks.size) % tracks.size].key)
    }
    fun remove(key: String): MusicQueueState {
        val index = tracks.indexOfFirst { it.key == key }
        val list = tracks.filter { it.key != key }
        return copy(tracks = list, currentKey = if (key == currentKey) list.getOrNull(index.coerceAtMost(list.lastIndex))?.key.orEmpty() else currentKey, nextUp = nextUp - key, remaining = remaining - key, history = history.filter { it != key })
    }
    fun move(from: Int, to: Int): MusicQueueState {
        if (from !in tracks.indices || to !in tracks.indices) return this
        return copy(tracks = tracks.toMutableList().apply { add(to, removeAt(from)) })
    }
    fun json(position: Long = 0) = JSONObject().put("tracks", JSONArray(tracks.map(MusicTrack::json))).put("current", currentKey).put("mode", mode.name).put("next", JSONArray(nextUp)).put("remaining", JSONArray(remaining)).put("history", JSONArray(history)).put("position", position.coerceAtLeast(0))
    companion object {
        fun from(j: JSONObject): MusicQueueState {
            val tracks = j.arr("tracks").objects().mapNotNull { runCatching { MusicTrack.from(it) }.getOrNull() }.distinctBy { it.key }
            fun keys(name: String) = j.arr(name).let { a -> (0 until a.length()).map { a.optString(it) }.filter { key -> tracks.any { it.key == key } } }
            return MusicQueueState(tracks, j.optString("current").takeIf { key -> tracks.any { it.key == key } } ?: tracks.firstOrNull()?.key.orEmpty(), runCatching { MusicMode.valueOf(j.optString("mode")) }.getOrDefault(MusicMode.ORDER), keys("next"), keys("remaining"), keys("history"))
        }
    }
}

data class LyricCue(val startMs: Long, val endMs: Long, val text: String)
object Lyrics {
    fun displayText(text: String) = text.replace(Regex("^[\\s♪♫♬♩🎵🎶]+|[\\s♪♫♬♩🎵🎶]+$"), "").trim()
    fun parse(j: JSONObject): List<LyricCue> = j.arr("body").objects().mapNotNull {
        val start = it.optDouble("from", -1.0); val end = it.optDouble("to", -1.0); val text = displayText(it.optString("content"))
        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || text.isBlank()) null else LyricCue((start * 1000).toLong(), (end * 1000).toLong(), text.take(4000))
    }.sortedBy { it.startMs }
    fun current(cues: List<LyricCue>, position: Long): Int {
        var lo = 0; var hi = cues.lastIndex; var found = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (cues[mid].startMs <= position) { found = mid; lo = mid + 1 } else hi = mid - 1 }
        return found.takeIf { it >= 0 && position < cues[it].endMs } ?: -1
    }
}
