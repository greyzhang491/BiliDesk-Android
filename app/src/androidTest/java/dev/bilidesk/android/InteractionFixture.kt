// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.app.Instrumentation
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.PlaybackService
import dev.bilidesk.android.player.PlayerEngine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import dev.bilidesk.android.download.Downloads
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okio.source
import android.os.SystemClock

/** Explicit opt-in offline transport. No mock interceptor or media is part of the app APK.
 * Push the Media3 public test sample to the app's external-files directory, then use
 * am instrument ... -e playback_fixture true ... . This does not test Bilibili networking.
 */
class InteractionFixture(private val instrumentation: Instrumentation, private val loggedIn: Boolean = false, private val downloadMedia: Boolean = false, private val musicMedia: Boolean = false, private val socialData: Boolean = false, richComments: Boolean = false, networkMedia: Boolean = false, private val creatorData: Boolean = false, private val favoriteData: Boolean = false, private val recommendationData: Boolean = false): AutoCloseable {
    private val context = instrumentation.targetContext
    private val previousApi = Graph.api
    private val previousPlayer = Graph.player
    private val previousDownloads = Graph.downloads
    val downloadOffsets = CopyOnWriteArrayList<Long>()
    @Volatile var slowDownloads = true
    @Volatile var lyricSoundMarkersOnly = false
    @Volatile var musicPictureUnavailable = false
    private val cookies = SecureCookies(context, "interaction_fixture_session")
    private val sample = File(context.getExternalFilesDir(null), "interaction-sample.mp4")
    private val duration: Long
    private val musicSample: MusicTestMedia?
    val readRequests = CopyOnWriteArrayList<String>()
    val writeForms = ConcurrentHashMap<String, Map<String, String>>()
    private var following = true
    private val followingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>().apply { add(90); add(91) }
    private val addedFolders = CopyOnWriteArrayList<JSONObject>()
    private val favoriteItems = ConcurrentHashMap<Long, MutableSet<Long>>().apply { put(500, ConcurrentHashMap.newKeySet<Long>().apply { add(100001); add(170001) }) }
    private val deletedFolders = ConcurrentHashMap.newKeySet<Long>()
    @Volatile var failNextFavoriteWrite = false
    @Volatile var loseNextFavoriteWriteResponse = false
    @Volatile var failNextFolderDelete = false
    @Volatile var loseNextFolderDeleteResponse = false
    @Volatile var failNextRecommendation = false
    @Volatile var loseNextRecommendationResponse = false
    private val commentLikes = ConcurrentHashMap<Long, Boolean>()
    private val commentDislikes = ConcurrentHashMap<Long, Boolean>()
    val network = if (networkMedia) NetworkTestMedia(context) else null
    private val rich = if (richComments || creatorData) CommentTestData(context) { id -> if (commentLikes[id] == true) 1 else if (commentDislikes[id] == true) 2 else 0 } else null
    @Volatile var failNextCommentRead = false
    @Volatile var loseNextCommentWriteResponse = false
    val requestedQualities = CopyOnWriteArrayList<Int>()
    private var liked = false
    val simulatedLiked get() = liked
    private var favorite = false
    private var coins = 0
    private val writes = ConcurrentHashMap<String, AtomicInteger>()
    fun writeCount(path: String) = writes[path]?.get() ?: 0
    init {
        check(sample.isFile) { "Push interaction-sample.mp4 before running fixture tests" }
        val metadata = MediaMetadataRetriever()
        val originalDuration: Long
        try { metadata.setDataSource(sample.path); originalDuration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
        finally { metadata.release() }
        check(originalDuration > 60000) { "Interaction sample must be longer than one minute" }
        musicSample = if (musicMedia) MusicTestMedia(sample, context.getExternalFilesDir(null)!!) else null
        duration = musicSample?.durationMs ?: originalDuration
        context.getSharedPreferences("fixture_music", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("fixture_music_position", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("fixture_music_library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        cookies.clear()
        if (loggedIn) cookies.putLogin(mapOf("SESSDATA" to "fixture-session-only", "bili_jct" to "fixture-csrf-only", "DedeUserID" to "9"))
        instrumentation.runOnMainSync { previousPlayer.stop(); context.stopService(Intent(context, PlaybackService::class.java)) }
        instrumentation.waitForIdleSync()
        val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            if (request.method == "GET") readRequests.add(url.toString())
            if (network != null && url.host.endsWith(".fixture.invalid")) return@addInterceptor network.respond(request)
            if (downloadMedia && url.host == "fixture.invalid") {
                val payload = if (musicSample != null) (if (url.encodedPath.contains("audio")) musicSample.audio else musicSample.video) else sample
                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.header("Range").orEmpty())!!
                val start = range.groupValues[1].toLong(); val end = minOf(range.groupValues[2].toLong(), payload.length() - 1)
                downloadOffsets.add(start)
                val input = payload.inputStream(); check(input.skip(start) == start)
                val source = input.source()
                val body = object: ResponseBody() {
                    private val buffered = object: Source {
                        var remaining = end - start + 1
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (remaining <= 0) return -1
                            if (slowDownloads) SystemClock.sleep(8)
                            val count = source.read(sink, minOf(byteCount, remaining))
                            if (count > 0) remaining -= count
                            return count
                        }
                        override fun timeout() = Timeout.NONE
                        override fun close() = source.close()
                    }.buffer()
                    override fun contentType() = "video/mp4".toMediaType()
                    override fun contentLength() = end - start + 1
                    override fun source(): BufferedSource = buffered
                }
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("fixture-media")
                    .header("Content-Range", "bytes $start-$end/${payload.length()}").header("ETag", "\"fixture-media-v1\"").body(body).build()
            }
            val second = url.queryParameter("bvid") == "BV17x411w7KC"
            val path = url.encodedPath
            if (path == "/fixture-lyrics.json") {
                val caption = if (lyricSoundMarkersOnly) JSONObject().put("body", JSONArray().put(JSONObject().put("from", 0).put("to", 45).put("content", "[Music]")))
                    else JSONObject().put("body", JSONArray().put(JSONObject().put("from", 0).put("to", 8).put("content", "♪ 第一句字幕歌词 ♫")).put(JSONObject().put("from", 8).put("to", 16).put("content", "第二句字幕歌词")).put(JSONObject().put("from", 16).put("to", 45).put("content", "第三句字幕歌词")))
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture-caption").body(caption.toString().toResponseBody("application/json".toMediaType())).build()
            }
            if (request.method != "GET") {
                check(loggedIn && path in setOf("/x/web-interface/archive/like", "/x/v3/fav/resource/deal", "/x/web-interface/coin/add", "/x/v2/history/toview/add", "/x/v2/history/report", "/x/relation/modify", "/x/v2/reply/action", "/x/v2/reply/hate", "/x/v2/reply/add", "/x/v3/fav/folder/add", "/x/v3/fav/folder/del", "/x/web-interface/feedback/dislike")) { "Fixture forbids this write" }
                writes.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val form = request.body as? FormBody
                fun field(name: String) = (0 until (form?.size ?: 0)).firstOrNull { form!!.name(it) == name }?.let { form!!.value(it) }
                writeForms[path] = (0 until (form?.size ?: 0)).associate { form!!.name(it) to form.value(it) }
                if (path == "/x/web-interface/feedback/dislike" && failNextRecommendation) {
                    failNextRecommendation = false
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture-rejection")
                        .body("""{"code":-400,"message":"模拟推荐反馈失败"}""".toResponseBody("application/json".toMediaType())).build()
                }
                if (path == "/x/v3/fav/resource/deal" && failNextFavoriteWrite || path == "/x/v3/fav/folder/del" && failNextFolderDelete) {
                    val message = if (path.endsWith("/del")) "模拟删除收藏夹失败" else "模拟取消收藏失败"
                    failNextFavoriteWrite = false; failNextFolderDelete = false
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture-rejection")
                        .body(JSONObject().put("code", -400).put("message", message).toString().toResponseBody("application/json".toMediaType())).build()
                }
                when (path) {
                    "/x/relation/modify" -> { following = field("act") == "1"; if (creatorData) { val mid = field("fid")!!.toLong(); if (following) followingIds.add(mid) else followingIds.remove(mid) } }
                    "/x/v2/reply/action" -> { val id = field("rpid")!!.toLong(); commentLikes[id] = field("action") == "1"; if (commentLikes[id] == true) commentDislikes[id] = false }
                    "/x/v2/reply/hate" -> { val id = field("rpid")!!.toLong(); commentDislikes[id] = field("action") == "1"; if (commentDislikes[id] == true) commentLikes[id] = false }
                    "/x/v3/fav/folder/add" -> addedFolders.add(JSONObject().put("id", 501 + addedFolders.size).put("title", field("title")).put("fav_state", 0))
                    "/x/v3/fav/folder/del" -> { val id = field("media_ids")!!.toLong(); deletedFolders.add(id); favoriteItems.remove(id) }
                    "/x/web-interface/archive/like" -> liked = field("like") == "1"
                    "/x/v3/fav/resource/deal" -> {
                        favorite = field("add_media_ids") != null
                        if (favoriteData) {
                            val id = (field("add_media_ids") ?: field("del_media_ids"))!!.toLong()
                            val items = favoriteItems.getOrPut(id) { ConcurrentHashMap.newKeySet<Long>() }
                            if (favorite) items.add(field("rid")!!.toLong()) else items.remove(field("rid")!!.toLong())
                        }
                    }
                    "/x/web-interface/coin/add" -> coins += field("multiply")?.toIntOrNull() ?: 0
                }
                if (path == "/x/v2/reply/add" && loseNextCommentWriteResponse) { loseNextCommentWriteResponse = false; throw java.io.IOException("Simulated lost reply response") }
                if (path == "/x/v3/fav/resource/deal" && loseNextFavoriteWriteResponse) { loseNextFavoriteWriteResponse = false; throw java.io.IOException("Simulated lost favorite response") }
                if (path == "/x/v3/fav/folder/del" && loseNextFolderDeleteResponse) { loseNextFolderDeleteResponse = false; throw java.io.IOException("Simulated lost folder response") }
                if (path == "/x/web-interface/feedback/dislike" && loseNextRecommendationResponse) { loseNextRecommendationResponse = false; throw java.io.IOException("Simulated lost recommendation response") }
            }
            if (path == "/x/v2/reply/main" && failNextCommentRead) { failNextCommentRead = false; return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture-error").body("""{"code":-403,"message":"模拟评论读取失败"}""".toResponseBody("application/json".toMediaType())).build() }
            val body = when (path) {
                "/x/web-interface/wbi/index/top/feed/rcmd", "/x/web-interface/popular" -> JSONObject().put("item", JSONArray(listOf(video(false), video(true)).mapIndexed { i, j ->
                    if (recommendationData) j.put("goto", "av").put("track_id", "UI-DEMO-RECOMMENDATION-$i") else j
                }))
                "/x/web-interface/nav" -> JSONObject().put("isLogin", loggedIn).put("mid", 9).put("uname", "模拟交互账号").put("wbi_img", JSONObject().put("img_url", "https://example.invalid/${"a".repeat(32)}.png").put("sub_url", "https://example.invalid/${"b".repeat(32)}.png"))
                "/x/frontend/finger/spi" -> JSONObject().put("b_3", "fixture-only-buvid")
                "/x/web-interface/view" -> {
                    val pages = JSONArray().put(JSONObject().put("cid", if (second) 102 else 101).put("part", "测试样片").put("duration", duration / 1000))
                    if (socialData && !second) pages.put(JSONObject().put("cid", 103).put("part", "第二首分P").put("duration", duration / 1000))
                    video(second).put("desc", "仅用于交互回归的独立样片，不是B站内容响应").put("pages", pages).apply {
                        if (socialData && !second) {
                            val episodes = JSONArray()
                                .put(JSONObject().put("aid", 100001).put("bvid", "BV156xx6wEff").put("cid", 101).put("title", "测试样片").put("arc", video(false)))
                                .put(JSONObject().put("aid", 170001).put("bvid", "BV17x411w7KC").put("cid", 102).put("title", "合集第二曲").put("arc", video(true)))
                            val sections = JSONArray().put(JSONObject().put("title", "测试选曲").put("episodes", episodes))
                            put("ugc_season", JSONObject().put("id", 30).put("title", "模拟歌曲合集").put("ep_count", 2).put("sections", sections))
                        }
                    }
                }
                "/x/player/wbi/playurl" -> {
                    requestedQualities.add(url.queryParameter("qn")?.toIntOrNull() ?: 0)
                    if (network != null) network.plan() else if (musicSample == null) JSONObject().put("timelength", duration).put("dash", JSONObject().put("video", JSONArray().put(JSONObject().put("baseUrl", if (downloadMedia) "https://fixture.invalid/sample.mp4" else Uri.fromFile(sample).toString()).put("id", 32).put("codecs", "avc1").put("mimeType", "video/mp4").put("bandwidth", 100000))).put("quality", 32))
                    else JSONObject().put("timelength", duration).put("dash", JSONObject()
                        .put("video", JSONArray(listOf(32, 64).map { q -> JSONObject().put("baseUrl", if (downloadMedia) "https://fixture.invalid/video-$q.mp4" else if (musicPictureUnavailable) Uri.fromFile(File(context.getExternalFilesDir(null), "missing-music-picture.mp4")).toString() else Uri.fromFile(musicSample.video).toString()).put("id", q).put("backupUrl", JSONArray().put("https://fixture.invalid/unwanted-video-probe")).put("codecs", "avc1").put("mimeType", "video/mp4").put("bandwidth", 100000) }))
                        .put("audio", JSONArray().put(JSONObject().put("baseUrl", if (downloadMedia) "https://fixture.invalid/audio.m4a" else Uri.fromFile(musicSample.audio).toString()).put("id", 30280).put("codecs", "mp4a.40.2").put("mimeType", "audio/mp4").put("bandwidth", 128000))))
                }
                "/x/player/wbi/v2" -> JSONObject().put("subtitle", JSONObject().put("subtitles", if (musicMedia && !second) JSONArray().put(JSONObject().put("lan", "zh-CN").put("subtitle_url", "https://aisubtitle.hdslb.com/fixture-lyrics.json")) else JSONArray()))
                "/x/web-interface/archive/related" -> JSONArray().put(video(false)).put(video(true))
                "/x/web-interface/archive/has/like" -> if (liked) 1 else 0
                "/x/relation" -> JSONObject().put("attribute", if (if (creatorData) (url.queryParameter("fid")?.toLongOrNull() ?: 0L) in followingIds else following) 2 else 0)
                "/x/relation/followings" -> JSONObject().put("total", followingIds.size).put("list", JSONArray(followingIds.sorted().map { mid -> JSONObject().put("mid", mid).put("uname", rich!!.name(mid)).put("face", rich.avatar(mid)).put("sign", "UI DEMO · 独立测试关注资料") }))
                "/x/v3/fav/folder/add" -> addedFolders.last()
                "/x/v3/fav/folder/created/list-all" -> {
                    val all = listOf(JSONObject().put("id", 500).put("title", "模拟收藏夹")) + addedFolders
                    val list = all.filter { it.optLong("id") !in deletedFolders }.map { original -> JSONObject(original.toString()).apply {
                        val items = favoriteItems[optLong("id")].orEmpty()
                        put("media_count", if (favoriteData) items.size else 2)
                        put("fav_state", if (if (favoriteData) (url.queryParameter("rid")?.toLongOrNull() ?: 0L) in items else favorite) 1 else 0)
                    } }
                    JSONObject().put("list", JSONArray(list))
                }
                "/x/v3/fav/resource/list" -> JSONObject().put("has_more", false).put("medias", JSONArray(listOf(video(false), video(true)).filter {
                    !favoriteData || it.optLong("aid") in favoriteItems[url.queryParameter("media_id")?.toLongOrNull() ?: 0L].orEmpty()
                }))
                "/x/web-interface/wbi/search/type" -> if (rich != null && url.queryParameter("search_type") == "bili_user") rich.searchUsers() else JSONObject().put("numPages", 1).put("result", JSONArray().put(video(false)).put(video(true)))
                "/x/web-interface/card" -> rich?.profile(url.queryParameter("mid")!!.toLong()) ?: JSONObject()
                "/x/space/wbi/arc/search" -> (rich?.uploads(url.queryParameter("mid")!!.toLong()) ?: JSONObject()).apply {
                    if (creatorData) {
                        val videos = getJSONObject("list").getJSONArray("vlist").objects().filter { it.optString("title").contains(url.queryParameter("keyword").orEmpty()) }
                        getJSONObject("list").put("vlist", JSONArray(videos)); getJSONObject("page").put("count", videos.size)
                    }
                }
                "/x/web-interface/archive/coins" -> JSONObject().put("multiply", coins)
                "/x/v2/reply/main" -> rich?.main(url.queryParameter("next")) ?: JSONObject().put("cursor", JSONObject().put("next", 1).put("is_end", true).put("all_count", 45)).put("replies", JSONArray().put(comment(700, "浏览评论时视频应持续播放", root = true)))
                "/x/v2/reply/reply" -> rich?.replies(url.queryParameter("pn")) ?: JSONObject().put("page", JSONObject().put("count", 1)).put("replies", JSONArray().put(comment(701, "楼中楼也不中断播放")))
                "/x/v2/reply" -> JSONObject().put("page", JSONObject().put("count", 45)).put("replies", if (url.queryParameter("pn") == "3") JSONArray().put(comment(745, "最早的一条评论")) else if (url.queryParameter("pn") == "1") JSONArray().put(comment(744, "最新的一条评论")) else JSONArray())
                else -> JSONObject().put("item", JSONArray()).put("list", JSONArray())
            }
            val responseBody = if (path == "/x/v2/dm/web/seg.so") danmaku().toResponseBody("application/octet-stream".toMediaType())
                else JSONObject().put("code", 0).put("data", body).toString().toResponseBody("application/json".toMediaType())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(responseBody).build()
        }.build()
        instrumentation.runOnMainSync { Graph.api = BiliApi(cookies, Graph.store, client); Graph.player = PlayerEngine(context, Graph.api, Graph.store, "fixture_music"); if (downloadMedia) Graph.downloads = Downloads(context, Graph.api, Graph.store) }
    }
    private fun video(second: Boolean) = JSONObject().put("bvid", if (second) "BV17x411w7KC" else "BV156xx6wEff").put("aid", if (second) 170001 else 100001)
        .put("pic", musicSample?.let { Uri.fromFile(if (second) it.coverB else it.coverA).toString() }.orEmpty())
        .put("title", if (second) "交互测试样片 B" else "交互测试样片 A").put("duration", duration / 1000)
        .put("pubdate", 1700000000).put("owner", JSONObject().put("name", if (creatorData) rich!!.name(90) else "独立设备回归样片").put("mid", if (creatorData) 90 else 80).put("face", if (creatorData) rich!!.avatar(90) else "")).put("stat", JSONObject().put("view", 12).put("danmaku", 40))
    private fun comment(id: Long, text: String, root: Boolean = false) = JSONObject().put("rpid", id).put("ctime", 1700000000 + id).put("like", if (commentLikes[id] == true) 4 else 3).put("action", if (commentLikes[id] == true) 1 else 0)
        .put("member", JSONObject().put("uname", "评论回归样例").put("mid", 90)).put("content", JSONObject().put("message", text)).put("rcount", if (root) 1 else 0)
        .put("replies", if (root) JSONArray().put(JSONObject().put("rpid", 701).put("member", JSONObject().put("uname", "回复样例")).put("content", JSONObject().put("message", "预览回复"))) else JSONArray())
    private fun danmaku(): ByteArray {
        fun varint(n: Long): ByteArray { var v = n; val out = ByteArrayOutputStream(); do { val bits = (v and 127).toInt(); v = v ushr 7; out.write(bits or if (v > 0) 128 else 0) } while (v > 0); return out.toByteArray() }
        fun integer(field: Int, n: Long) = varint((field * 8).toLong()) + varint(n)
        val result = ByteArrayOutputStream()
        for (i in 0..100) {
            val text = "测试弹幕 $i".toByteArray()
            val element = integer(2, i * 500L) + integer(3, 1) + integer(5, 0xffffff) + varint(58) + varint(text.size.toLong()) + text
            result.write(varint(10)); result.write(varint(element.size.toLong())); result.write(element)
        }
        return result.toByteArray()
    }
    override fun close() {
        instrumentation.runOnMainSync { Graph.player.stop(); context.stopService(Intent(context, PlaybackService::class.java)); if (downloadMedia) { Graph.downloads.close(); context.stopService(Intent(context, dev.bilidesk.android.download.CacheDownloadService::class.java)) } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { Graph.player.release(); Graph.player = previousPlayer; Graph.api = previousApi; if (downloadMedia) Graph.downloads = previousDownloads }
        cookies.clear()
    }
}
