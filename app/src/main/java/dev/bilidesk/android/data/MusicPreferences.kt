// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import org.json.JSONObject

enum class MusicStyle(val label: String) { RECORD("经典唱片"), IMMERSIVE("沉浸封面"), MINIMAL("简约") }
enum class MusicAudioQuality(val label: String) { SAVER("省流"), BALANCED("均衡"), HIGH("高音质") }
data class MusicPreferences(
    val style: MusicStyle = MusicStyle.RECORD,
    val coverColor: Boolean = true,
    val accent: String = "#F5B7CA",
    val darkness: Float = .65f,
    val artworkSize: Float = .85f,
    val lyricSize: Float = 22f,
    val lyricsLeft: Boolean = false,
    val animation: Boolean = true,
    val audioQuality: MusicAudioQuality = MusicAudioQuality.SAVER
) {
    fun json() = JSONObject().put("style", style.name).put("coverColor", coverColor).put("accent", accent).put("darkness", darkness).put("artworkSize", artworkSize).put("lyricSize", lyricSize).put("lyricsLeft", lyricsLeft).put("animation", animation).put("audioQuality", audioQuality.name)
    companion object {
        fun validColor(value: String) = Regex("#[0-9a-fA-F]{6}").matches(value)
        fun from(j: JSONObject): MusicPreferences {
            fun finite(name: String, fallback: Float, low: Float, high: Float) = j.optDouble(name, fallback.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(low, high) ?: fallback
            return MusicPreferences(runCatching { MusicStyle.valueOf(j.optString("style")) }.getOrDefault(MusicStyle.RECORD), j.optBoolean("coverColor", true), j.optString("accent").takeIf(::validColor) ?: "#F5B7CA",
                finite("darkness", .65f, .25f, .9f), finite("artworkSize", .85f, .6f, 1f), finite("lyricSize", 22f, 16f, 34f), j.optBoolean("lyricsLeft"), j.optBoolean("animation", true), runCatching { MusicAudioQuality.valueOf(j.optString("audioQuality")) }.getOrDefault(MusicAudioQuality.SAVER))
        }
    }
}
object MusicAudioPolicy {
    fun select(streams: List<Stream>, quality: MusicAudioQuality): Stream? {
        val candidates = streams.filter { it.mime.startsWith("audio/") || it.codec.startsWith("mp4a") }.let { all -> all.filter { it.codec.startsWith("mp4a") }.ifEmpty { all } }.sortedBy { it.bandwidth.takeIf { n -> n > 0 } ?: when (it.quality) { 30216 -> 64000L; 30232 -> 132000L; else -> 192000L } }
        return when (quality) { MusicAudioQuality.SAVER -> candidates.firstOrNull(); MusicAudioQuality.BALANCED -> candidates.lastOrNull { it.bandwidth in 1..140000 } ?: candidates.firstOrNull(); MusicAudioQuality.HIGH -> candidates.lastOrNull() }
    }
    fun megabytesPerHour(bandwidth: Long) = if (bandwidth > 0) bandwidth * 3600.0 / 8 / 1_000_000 else null
}
