// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

fun JSONObject.obj(key: String) = optJSONObject(key) ?: JSONObject()
fun JSONObject.arr(key: String) = optJSONArray(key) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun String.clean(): String = replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
fun String.https(): String = when { startsWith("//") -> "https:$this"; startsWith("http://") -> "https://" + removePrefix("http://"); else -> this }
fun countLabel(n: Long): String = when { n >= 100000000 -> String.format(Locale.CHINA, "%.1f亿", n / 100000000.0); n >= 10000 -> String.format(Locale.CHINA, "%.1f万", n / 10000.0); else -> n.toString() }
fun timeLabel(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
fun durationValue(value: String): Long = value.split(':').fold(0L) { total, v -> total * 60 + (v.toLongOrNull() ?: 0) }

data class Video(
    val bvid: String = "", val aid: Long = 0, val title: String = "", val cover: String = "",
    val author: String = "", val mid: Long = 0, val plays: Long = 0, val danmaku: Long = 0,
    val duration: Long = 0, val kind: String = "video", val targetId: Long = 0, val subtitle: String = ""
) {
    val key get() = if (kind == "video") bvid.ifBlank { "av$aid" } else "$kind$targetId"
    val url get() = when (kind) { "live" -> "https://live.bilibili.com/$targetId"; "season" -> "https://www.bilibili.com/bangumi/play/ss$targetId"; else -> "https://www.bilibili.com/video/${bvid.ifBlank { "av$aid" }}" }
    fun json() = JSONObject().put("bvid", bvid).put("aid", aid).put("title", title).put("cover", cover).put("author", author).put("mid", mid).put("plays", plays).put("danmaku", danmaku).put("duration", duration).put("kind", kind).put("targetId", targetId).put("subtitle", subtitle)
    companion object {
        fun from(j: JSONObject) = Video(j.optString("bvid"), j.optLong("aid"), j.optString("title"), j.optString("cover"), j.optString("author"), j.optLong("mid"), j.optLong("plays"), j.optLong("danmaku"), j.optLong("duration"), j.optString("kind", "video"), j.optLong("targetId"), j.optString("subtitle"))
        fun api(j: JSONObject): Video {
            val owner = j.optJSONObject("owner") ?: j.obj("upper")
            val stat = j.obj("stat")
            val rawDuration = j.optString("duration")
            return Video(
                j.optString("bvid").ifBlank { Regex("BV[0-9A-Za-z]{10}").find(j.optString("uri"))?.value.orEmpty() },
                j.optLong("aid", j.optLong("id")), j.optString("title").clean(),
                j.optString("pic", j.optString("cover")).https(),
                owner.optString("name", j.optString("author")), owner.optLong("mid", j.optLong("mid")),
                stat.optLong("view", j.obj("cnt_info").optLong("play", j.optLong("play"))),
                stat.optLong("danmaku", j.optLong("video_review")),
                if (rawDuration.contains(':')) durationValue(rawDuration) else rawDuration.toLongOrNull() ?: 0
            )
        }
    }
}
data class Episode(val cid: Long, val title: String, val duration: Long, val bvid: String = "", val aid: Long = 0, val epid: Long = 0)
data class VideoDetail(val video: Video, val description: String, val pages: List<Episode>, val likes: Long = 0, val coins: Long = 0, val favorites: Long = 0, val collection: VideoCollection? = null)
data class FeedPage(val videos: List<Video>, val next: String = "", val more: Boolean = true)
data class User(val mid: Long, val name: String, val avatar: String, val level: Int, val vip: Boolean)
data class Folder(val id: Long, val title: String, val count: Int, val selected: Boolean = false)
data class Comment(val name: String, val avatar: String, val text: String, val likes: Long, val id: Long = 0, val mid: Long = 0, val createdAt: Long = 0, val liked: Boolean = false, val replyCount: Int = 0, val rootId: Long = 0, val parentId: Long = 0, val replies: List<Comment> = emptyList(), val pinned: Boolean = false,
    val level: Int? = null, val location: String = "", val vip: Boolean = false, val official: String = "", val disliked: Boolean = false,
    val emotes: List<CommentEmote> = emptyList(), val pictures: List<CommentPicture> = emptyList(), val mentions: Map<String, Long> = emptyMap()) {
    val key get() = if (id > 0) "$id" else "$name:$createdAt:$text"
}
data class DynamicItem(val id: String, val name: String, val avatar: String, val text: String, val published: String, val video: Video?, val pictures: List<String>)
data class MessageItem(val title: String, val text: String, val url: String = "", val talkerId: Long = 0)
data class Stream(val url: String, val backups: List<String>, val quality: Int, val codec: String, val bandwidth: Long, val mime: String)
data class PlayPlan(val video: Stream, val audio: Stream? = null, val qualities: List<Int> = emptyList(), val durationMs: Long = 0, val preview: Boolean = false, val audioOptions: List<Stream> = emptyList())
data class Danmaku(val timeMs: Long, val text: String, val mode: Int = 1, val color: Int = 0xffffff, val weight: Int = 0)
data class Segment(val cid: Long, val startMs: Long, val endMs: Long, val category: String, val action: String, val votes: Int)
data class HistoryEntry(val video: Video, val cid: Long, val positionMs: Long, val at: Long)
data class CacheEntry(val key: String, val video: Video, val cid: Long, val pageTitle: String, val path: String, val bytes: Long, val automatic: Boolean = false, val accessedAt: Long = System.currentTimeMillis(), val type: String = "video", val quality: Int = 0)

object Quality {
    val options = listOf(0, 127, 120, 116, 112, 80, 64, 32, 16)
    fun label(q: Int) = when (q) { 0 -> "自动"; 127 -> "8K"; 126 -> "杜比视界"; 125 -> "HDR"; 120 -> "4K"; 116 -> "1080P 60帧"; 112 -> "1080P 高码率"; 80 -> "1080P"; 74 -> "720P 60帧"; 64 -> "720P"; 32 -> "480P"; 16 -> "360P"; else -> "画质 $q" }
    fun select(streams: List<Stream>, requested: Int, preferHevc: Boolean): Stream {
        require(streams.isNotEmpty()) { "没有可用的视频流" }
        val target = if (requested == 0) Int.MAX_VALUE else requested
        val quality = streams.filter { it.quality <= target }.maxOfOrNull { it.quality } ?: streams.minOf { it.quality }
        return streams.filter { it.quality == quality }.maxBy { s ->
            when { s.codec.startsWith("hev") || s.codec.startsWith("hvc") -> if (preferHevc) 3 else 2; s.codec.startsWith("avc") -> if (preferHevc) 2 else 3; else -> 1 }
        }
    }
}

/** Port of BiliDesk's WBI signing flow; signatures use the 32-character mixin key. */
object Wbi {
    private val permutation = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    fun encode(s: String) = URLEncoder.encode(s.filter { it !in "!'()*" }, "UTF-8").replace("+", "%20").replace("%7E", "~")
    fun mixin(img: String, sub: String): String {
        val full = img + sub
        require(full.length >= 64) { "WBI 密钥格式有误" }
        return permutation.take(32).map { full[it] }.joinToString("")
    }
    fun sign(parameters: Map<String, String>, key: String, timestamp: Long): Map<String, String> {
        val sorted = parameters.filterKeys { it != "w_rid" && it != "wts" }.toMutableMap().apply { put("wts", timestamp.toString()) }.toSortedMap()
        val query = sorted.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val md5 = MessageDigest.getInstance("MD5").digest((query + key).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return sorted.mapValues { (_, v) -> v.filter { it !in "!'()*" } } + ("w_rid" to md5)
    }
}

object SponsorRules {
    fun select(raw: List<Segment>, cid: Long, categories: Set<String>) = raw.filter {
        it.cid == cid && it.action == "skip" && it.votes >= 0 && it.category in categories && it.startMs >= 0 && it.endMs > it.startMs
    }.sortedBy { it.startMs }
}
