// SPDX-License-Identifier: GPL-3.0-only
// API paths and workflow adapted from ABING1111/BiliDesk, Services/ApiClient.cs and PassportService.cs.
package dev.bilidesk.android.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String, val actionFailure: ActionFailure = ActionFailure.OTHER): IOException(message)
const val BILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object: Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response, onCancellation = { _, value, _ -> value.close() }) }
    })
}

class BiliApi(val cookies: SecureCookies, private val store: Store, client: OkHttpClient? = null) {
    val http = client ?: OkHttpClient.Builder().cookieJar(cookies).connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val passport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    private val accountWrites = AccountWriteClient(http, { cookies.value("bili_jct") }, { cookies.value("SESSDATA").isNotBlank() }, { warmUp() })
    private val keyMutex = Mutex()
    private val identityMutex = Mutex()
    private var mixin: String? = null
    private var keyAt = 0L
    private val recommendationRefresh = java.util.concurrent.atomic.AtomicInteger()
    private val base = "https://api.bilibili.com"
    private fun request(url: String, referer: String = "https://www.bilibili.com/") = Request.Builder().url(url).header("User-Agent", BILI_UA).header("Referer", referer).header("Accept", "application/json, text/plain, */*")

    suspend fun warmUp(): Unit = identityMutex.withLock {
        if (cookies.value("buvid3").isNotBlank()) return@withLock
        val j = json("/x/frontend/finger/spi")
        withContext(Dispatchers.IO) { cookies.putLogin(mapOf("buvid3" to j.optString("b_3"), "buvid4" to j.optString("b_4")).filterValues { it.isNotBlank() }) }
    }
    private suspend fun signingKey(refresh: Boolean = false): String = keyMutex.withLock {
        if (refresh || mixin == null || System.currentTimeMillis() - keyAt > 3600000) {
            val j = json("/x/web-interface/nav", allowAnonymous = true).obj("wbi_img")
            fun name(s: String) = s.substringAfterLast('/').substringBefore('.')
            mixin = Wbi.mixin(name(j.getString("img_url")), name(j.getString("sub_url")))
            keyAt = System.currentTimeMillis()
        }
        mixin!!
    }
    suspend fun json(path: String, params: Map<String, String> = emptyMap(), sign: Boolean = false,
                     host: String = base, referer: String = "https://www.bilibili.com/", payload: String = "data", allowAnonymous: Boolean = false): JSONObject {
        var refresh = false
        var rateRetries = 0
        while (true) {
            val p = if (sign) Wbi.sign(params, signingKey(refresh), System.currentTimeMillis() / 1000) else params
            val url = (host + path).toHttpUrl().newBuilder().apply { p.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val root = withContext(Dispatchers.IO) { http.newCall(request(url.toString(), referer).build()).awaitResponse().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, if (response.code == 412 || response.code == 429) "当前网络请求过于频繁，请稍后重试" else "服务请求失败（HTTP ${response.code}）")
                try { JSONObject(response.body?.string().orEmpty()) } catch (_: Exception) { throw ApiException(-2, "服务返回了无法识别的内容，请稍后重试") }
            } }
            val code = root.optInt("code", -999)
            if (code == -403 && sign && !refresh) { refresh = true; continue }
            if (code == -412 && rateRetries < 2) { delay(1500L shl rateRetries++); continue }
            if (code in listOf(-401, -403, -352)) throw ActionErrors.from(root)
            if (code != 0 && !(allowAnonymous && code == -101)) throw ApiException(code, when (code) {
                -101, -111 -> "登录已失效，请重新登录"
                -412, -352 -> "当前网络请求受限，请稍后重试或在官方网页完成验证"
                -10403, 6002105 -> "当前账号没有此内容的播放权限"
                else -> root.optString("message").ifBlank { "请求失败（$code）" }
            })
            return root.optJSONObject(payload) ?: if (root.optJSONArray(payload) != null) JSONObject().put("items", root.getJSONArray(payload)) else JSONObject().put("value", root.opt(payload))
        }
    }
    suspend fun post(path: String, params: Map<String, String>, host: String = base, referer: String = "https://www.bilibili.com/"): JSONObject = accountWrites.post(path, params, host, referer)
    suspend fun user(): User? {
        val j = try { json("/x/web-interface/nav") }
        catch (error: ApiException) { if (error.code == -101) return null else throw error }
        return AccountUserResponse.parse(j)
    }
    suspend fun feed(tab: String, page: Int = 1, region: Int = 0): FeedPage {
        if (tab in listOf("分区", "推荐")) warmUp()
        val params = mapOf("pn" to "$page", "ps" to "24")
        val j = when (tab) {
            "推荐" -> try {
                val refresh = recommendationRefresh.incrementAndGet().toString()
                json("/x/web-interface/wbi/index/top/feed/rcmd", mapOf("fresh_type" to "4", "ps" to "24", "fresh_idx" to refresh, "fresh_idx_1h" to refresh, "brush" to "$page", "web_location" to "1430650", "feed_version" to "V8", "homepage_ver" to "1"), sign = true)
            } catch (e: Exception) { if (e is CancellationException) throw e; json("/x/web-interface/popular", params) }
            "排行榜" -> json("/x/web-interface/ranking/v2", mapOf("rid" to "$region", "type" to "all"))
            "分区" -> try {
                json("/x/web-interface/region/feed/rcmd", mapOf("display_id" to "$page", "request_cnt" to "20", "from_region" to "$region", "device" to "web", "plat" to "30"))
            } catch (e: ApiException) { if (e.code == 62013) return FeedPage(emptyList(), more = false) else throw e }
            else -> json("/x/web-interface/popular", params)
        }
        val list = (j.optJSONArray("item") ?: j.optJSONArray("list") ?: j.optJSONArray("archives") ?: JSONArray()).objects().filter { it.optInt("aid", it.optInt("id")) > 0 || it.optString("bvid").isNotBlank() }.map(Video::api).distinctBy { it.key }
        return FeedPage(list, more = tab != "排行榜" && list.size >= 10)
    }
    suspend fun search(keyword: String, page: Int): FeedPage {
        val j = json("/x/web-interface/wbi/search/type", mapOf("search_type" to "video", "keyword" to keyword, "page" to "$page", "page_size" to "20", "order" to "totalrank"), sign = true, referer = "https://search.bilibili.com/")
        return FeedPage(j.arr("result").objects().map(Video::api), more = page < j.optInt("numPages", 1))
    }
    suspend fun hotSearch(): List<String> = json("/x/web-interface/search/square", mapOf("limit" to "10", "platform" to "web" )).obj("trending").arr("list").objects().map { it.optString("keyword") }.filter { it.isNotBlank() }
    suspend fun resolveShortLink(link: String): String {
        val clean = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build()
        var url = link.toHttpUrl()
        repeat(5) {
            val allowed = url.host == "b23.tv" || url.host == "bilibili.com" || url.host.endsWith(".bilibili.com")
            if (!allowed || !url.isHttps) throw ApiException(-3, "链接未指向哔哩哔哩")
            if (url.host != "b23.tv") return url.toString()
            val next = clean.newCall(request(url.toString()).head().build()).awaitResponse().use { r -> r.header("Location")?.let { url.resolve(it) } }
            url = next ?: throw ApiException(-3, "短链接无法解析，请粘贴 BV 号")
        }
        throw ApiException(-3, "短链接跳转次数过多")
    }
    suspend fun detail(video: Video): VideoDetail {
        if (video.kind == "season") {
            val j = json("/pgc/view/web/season", mapOf("season_id" to "${video.targetId}"), payload = "result")
            val episodes = j.arr("episodes").objects().map { Episode(it.optLong("cid"), (it.optString("title") + " " + it.optString("long_title")).trim(), it.optLong("duration") / 1000, it.optString("bvid"), it.optLong("aid"), it.optLong("id")) }
            return VideoDetail(video.copy(title = j.optString("title", video.title), cover = j.optString("cover", video.cover).https()), j.optString("evaluate"), episodes)
        }
        val id = if (video.bvid.isNotBlank()) mapOf("bvid" to video.bvid) else mapOf("aid" to "${video.aid}")
        val j = json("/x/web-interface/view", id)
        val summary = Video.api(j)
        return VideoDetail(summary, j.optString("desc"), j.arr("pages").objects().map { Episode(it.optLong("cid"), it.optString("part"), it.optLong("duration"), j.optString("bvid"), j.optLong("aid")) }, j.obj("stat").optLong("like"), j.obj("stat").optLong("coin"), j.obj("stat").optLong("favorite"), VideoCollections.parse(j, summary))
    }
    suspend fun related(bvid: String) = json("/x/web-interface/archive/related", mapOf("bvid" to bvid)).arr("items").objects().map(Video::api)
    private fun streams(array: JSONArray) = array.objects().mapNotNull { j ->
        val url = j.optString("baseUrl", j.optString("base_url")).https()
        if (url.isBlank()) null else Stream(url, (j.optJSONArray("backupUrl") ?: j.arr("backup_url")).let { a -> (0 until a.length()).map { a.optString(it).https() }.filter { it.isNotBlank() } }, j.optInt("id"), j.optString("codecs"), j.optLong("bandwidth"), j.optString("mimeType", j.optString("mime_type", "video/mp4")))
    }
    suspend fun play(video: Video, episode: Episode, requested: Int = store.settings.value.quality, forceAvc: Boolean = false): PlayPlan {
        val p = mutableMapOf("cid" to "${episode.cid}", "qn" to "${if (requested == 0) 127 else requested}", "fnval" to "4048", "fnver" to "0", "fourk" to "1")
        val j = if (episode.epid > 0) {
            p["ep_id"] = "${episode.epid}"
            json("/pgc/player/web/playurl", p, payload = "result")
        } else {
            p["bvid"] = episode.bvid.ifBlank { video.bvid }
            json("/x/player/wbi/playurl", p, sign = true)
        }
        val dash = j.obj("dash")
        val videos = streams(dash.arr("video"))
        if (videos.isNotEmpty()) {
            val candidates = if (forceAvc) videos.filter { it.codec.startsWith("avc", ignoreCase = true) } else videos
            if (candidates.isEmpty()) throw ApiException(-3, "当前内容没有可用的 H.264 兼容视频流")
            val selected = Quality.select(candidates, requested, !forceAvc && store.settings.value.preferHevc)
            val sounds = streams(dash.arr("audio"))
            val audio = sounds.filter { it.codec.startsWith("mp4a") }.ifEmpty { sounds }.maxByOrNull { it.bandwidth }
            return PlayPlan(selected, audio, candidates.map { it.quality }.distinct().sortedDescending(), j.optLong("timelength"), j.optInt("is_preview") == 1, sounds)
        }
        val durl = j.arr("durl").objects()
        if (durl.size > 1) throw ApiException(-3, "该视频仅返回分段旧格式，请在官方网页播放")
        val url = durl.firstOrNull()?.optString("url").orEmpty().https()
        if (url.isBlank()) throw ApiException(-3, "未获取到可播放的内容，请检查账号权限或在官方网页打开")
        val stream = Stream(url, emptyList(), j.optInt("quality"), "avc1", 0, "video/mp4")
        return PlayPlan(stream, qualities = listOf(stream.quality), durationMs = j.optLong("timelength"), preview = j.optInt("is_preview") == 1)
    }
    suspend fun lyrics(video: Video, cid: Long): List<dev.bilidesk.android.player.LyricCue> {
        val p = mutableMapOf("cid" to "$cid")
        if (video.bvid.isNotBlank()) p["bvid"] = video.bvid else p["aid"] = "${video.aid}"
        val tracks = json("/x/player/wbi/v2", p, sign = true, referer = video.url).obj("subtitle").arr("subtitles").objects()
        val track = tracks.sortedBy { if (it.optString("lan").contains("zh")) 0 else 1 }.firstOrNull() ?: return emptyList()
        val url = track.optString("subtitle_url").https().toHttpUrl()
        require(url.isHttps && (url.host.endsWith(".hdslb.com") || url.host.endsWith(".bilibili.com"))) { "字幕地址不受支持" }
        val clean = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return withContext(Dispatchers.IO) { clean.newCall(request(url.toString(), video.url).build()).awaitResponse().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("字幕读取失败（HTTP ${r.code}）")
            val source = r.body?.source() ?: return@use emptyList()
            val buffer = okio.Buffer(); var read = 0L
            while (true) { val n = source.read(buffer, 8192); if (n < 0) break; read += n; if (read > 2 * 1024 * 1024) throw java.io.IOException("字幕内容过大") }
            dev.bilidesk.android.player.Lyrics.parse(JSONObject(buffer.readUtf8()))
        } }
    }
    suspend fun danmaku(cid: Long, segment: Int): List<Danmaku> {
        val url = "$base/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$segment"
        return withContext(Dispatchers.IO) { http.newCall(request(url).build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw IOException("弹幕加载失败")
            val bytes = response.body?.bytes() ?: byteArrayOf()
            if (bytes.size > 8_000_000) throw IOException("弹幕数据过大")
            DanmakuParser.parse(bytes)
        } }
    }
    suspend fun comments(aid: Long, page: Int = 1): List<Comment> {
        val j = json("/x/v2/reply", mapOf("type" to "1", "oid" to "$aid", "pn" to "$page", "sort" to "2"))
        return j.arr("replies").objects().map { SocialModels.comment(it) }
    }
    suspend fun commentPage(aid: Long, cursor: Int = 0, order: CommentOrder = CommentOrder.HOT, root: Long = 0): CommentPage {
        require(aid > 0 && root >= 0) { "评论信息不完整" }
        val common = mapOf("type" to "1", "oid" to "$aid", "ps" to "20")
        if (root > 0) {
            val page = cursor.coerceAtLeast(1)
            val j = json("/x/v2/reply/reply", common + mapOf("root" to "$root", "pn" to "$page"))
            return SocialModels.page(j, page, CommentOrder.NEWEST, replies = true)
        }
        if (order == CommentOrder.HOT) {
            val j = json("/x/v2/reply/main", common + mapOf("mode" to "3", "next" to "$cursor"))
            return SocialModels.page(j, cursor, order)
        }
        val params = common + mapOf("sort" to "0", "nohot" to "1")
        var page = cursor.coerceAtLeast(1)
        var j = json("/x/v2/reply", params + mapOf("pn" to "$page"))
        if (order == CommentOrder.OLDEST && cursor == 0) {
            val count = j.obj("page").optLong("count", -1)
            if (count < 0 && j.arr("replies").length() > 0) throw ApiException(-3, "平台暂未返回总页数，无法定位最早评论，请改用最新排序")
            page = ((count.coerceAtLeast(0) + 19) / 20).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (page > 1) j = json("/x/v2/reply", params + mapOf("pn" to "$page"))
        }
        return SocialModels.page(j, page, order)
    }
    suspend fun likeComment(aid: Long, comment: Long, like: Boolean) {
        require(aid > 0 && comment > 0) { "评论信息不完整，请刷新评论后重试" }
        post("/x/v2/reply/action", mapOf("type" to "1", "oid" to "$aid", "rpid" to "$comment", "action" to if (like) "1" else "0"), referer = "https://www.bilibili.com/video/av$aid")
    }
    suspend fun following(mid: Long): Boolean {
        require(mid > 0)
        val j = json("/x/relation", mapOf("fid" to "$mid"), referer = "https://space.bilibili.com/$mid")
        if (!j.has("attribute")) throw ApiException(-3, "关注状态暂时无法确认，请重试")
        return SocialModels.following(j.optInt("attribute"))
    }
    suspend fun createFolder(title: String, privateFolder: Boolean = true): Folder {
        val valid = SocialModels.folderTitle(title)
        val j = post("/x/v3/fav/folder/add", mapOf("title" to valid, "privacy" to if (privateFolder) "1" else "0", "intro" to "", "cover" to ""))
        val id = j.optLong("id")
        if (id <= 0) throw WriteOutcomeUnknown()
        return Folder(id, j.optString("title").ifBlank { valid }, j.optInt("media_count"))
    }
    suspend fun folders(aid: Long = 0): List<Folder> {
        val p = mutableMapOf("up_mid" to cookies.value("DedeUserID"))
        if (aid > 0) { p["rid"] = "$aid"; p["type"] = "2" }
        return json("/x/v3/fav/folder/created/list-all", p).arr("list").objects().map { Folder(it.optLong("id"), it.optString("title"), it.optInt("media_count"), it.optInt("fav_state") == 1) }
    }
    suspend fun favorites(folder: Long, page: Int): FeedPage {
        val j = json("/x/v3/fav/resource/list", mapOf("media_id" to "$folder", "pn" to "$page", "ps" to "20", "platform" to "web"))
        return FeedPage(j.arr("medias").objects().map(Video::api), more = j.optBoolean("has_more"))
    }
    suspend fun cloudHistory(page: Int): FeedPage {
        val list = json("/x/v2/history", mapOf("pn" to "$page", "ps" to "30")).arr("items").objects()
        return FeedPage(list.map(Video::api), more = list.size == 30)
    }
    suspend fun later(): FeedPage = FeedPage(json("/x/v2/history/toview").arr("list").objects().map(Video::api), more = false)
    suspend fun dynamics(offset: String = ""): Pair<List<DynamicItem>, String> {
        val j = json("/x/polymer/web-dynamic/v1/feed/all", if (offset.isBlank()) emptyMap() else mapOf("offset" to offset), sign = true, referer = "https://t.bilibili.com/")
        val list = j.arr("items").objects().map { raw ->
            val m = raw.obj("modules"); val author = m.obj("module_author"); val d = m.obj("module_dynamic"); val archive = d.obj("major").obj("archive")
            val v = if (archive.optString("bvid").isNotBlank()) Video.api(archive).copy(author = author.optString("name"), mid = author.optLong("mid"), plays = archive.obj("stat").optString("play").toLongOrNull() ?: 0) else null
            DynamicItem(raw.optString("id_str"), author.optString("name"), author.optString("face").https(), d.obj("desc").optString("text", d.obj("major").obj("opus").obj("summary").optString("text")), author.optString("pub_time"), v,
                d.obj("major").obj("draw").arr("items").objects().map { it.optString("src").https() })
        }
        return list to if (j.optBoolean("has_more")) j.optString("offset") else ""
    }
    suspend fun liveList(page: Int): FeedPage {
        val j = json("/room/v1/Area/getRoomList", mapOf("platform" to "web", "parent_area_id" to "0", "area_id" to "0", "sort_type" to "online", "page" to "$page", "page_size" to "30"), host = "https://api.live.bilibili.com", referer = "https://live.bilibili.com/")
        return FeedPage(j.arr("items").objects().map { Video(title = it.optString("title"), cover = it.optString("user_cover", it.optString("system_cover")).https(), author = it.optString("uname"), plays = it.optLong("online"), kind = "live", targetId = it.optLong("roomid", it.optLong("room_id")), subtitle = it.optString("area_name")) }, more = j.arr("items").length() >= 30)
    }
    suspend fun livePlay(room: Long): PlayPlan {
        val j = json("/xlive/web-room/v2/index/getRoomPlayInfo", mapOf("room_id" to "$room", "qn" to "0", "platform" to "web", "protocol" to "0,1", "format" to "0,1,2", "codec" to "0,1"), host = "https://api.live.bilibili.com", referer = "https://live.bilibili.com/$room")
        if (j.optInt("live_status") == 0) throw ApiException(-3, "主播还没有开播")
        val candidates = j.obj("playurl_info").obj("playurl").arr("stream").objects().flatMap { st -> st.arr("format").objects().flatMap { f -> f.arr("codec").objects().filter { it.optString("codec_name") == "avc" }.mapNotNull { c ->
            val u = c.arr("url_info").optJSONObject(0) ?: return@mapNotNull null
            val extra = u.optString("extra").let { if (it.isNotBlank() && !it.startsWith('?')) "?$it" else it }
            val full = (u.optString("host") + c.optString("base_url").substringBefore('?') + extra).https()
            Stream(full, emptyList(), c.optInt("current_qn"), "avc1", 0, if (f.optString("format_name").contains("ts") || full.contains("m3u8")) "application/x-mpegURL" else "video/x-flv")
        } } }
        val selected = candidates.firstOrNull { it.mime.contains("mpegURL") } ?: candidates.firstOrNull() ?: throw ApiException(-3, "没有可用的直播流")
        return PlayPlan(selected)
    }
    suspend fun bangumi(page: Int, mine: Boolean = false): FeedPage {
        val j = if (mine) json("/x/space/bangumi/follow/list", mapOf("vmid" to cookies.value("DedeUserID"), "pn" to "$page", "ps" to "24", "type" to "1"))
                else json("/pgc/season/index/result", mapOf("season_type" to "1", "type" to "0", "page" to "$page", "ps" to "24"))
        return FeedPage(j.arr("list").objects().map { Video(title = it.optString("title").clean(), cover = it.optString("cover").https(), author = it.obj("new_ep").optString("index_show"), kind = "season", targetId = it.optLong("season_id"), subtitle = it.obj("rating").optString("score")) }, more = j.optInt("has_next") == 1 || j.arr("list").length() == 24)
    }
    suspend fun messages(type: String, cursor: String = ""): List<MessageItem> {
        if (type == "私信") {
            val j = json("/session_svr/v1/session_svr/get_sessions", mapOf("session_type" to "1", "group_fold" to "1", "unfollow_fold" to "0", "sort_rule" to "2", "build" to "0", "mobi_app" to "web"), host = "https://api.vc.bilibili.com", referer = "https://message.bilibili.com/")
            return j.arr("session_list").objects().map { s ->
                val talker = s.optLong("talker_id")
                val content = runCatching { JSONObject(s.obj("last_msg").optString("content")).optString("content") }.getOrDefault("[非文字消息]")
                MessageItem("用户 $talker", content, "https://message.bilibili.com/#/whisper/mid$talker", talker)
            }
        }
        val endpoint = when(type) { "@我" -> "at"; "点赞" -> "like"; else -> "reply" }
        val j = json("/x/msgfeed/$endpoint", if (cursor.isBlank()) emptyMap() else mapOf("cursor" to cursor), referer = "https://message.bilibili.com/")
        val list = j.optJSONArray("items") ?: j.obj("total").arr("items")
        return list.objects().map { m ->
            val u = m.optJSONObject("user") ?: m.arr("users").optJSONObject(0) ?: JSONObject()
            val item = m.obj("item")
            MessageItem(u.optString("nickname", "哔哩哔哩通知"), item.optString("source_content", item.optString("title", item.optString("description"))).clean(), item.optString("uri"))
        }
    }
    suspend fun messageHistory(talker: Long): List<MessageItem> = json("/svr_sync/v1/svr_sync/fetch_session_msgs", mapOf("talker_id" to "$talker", "session_type" to "1", "size" to "30", "build" to "0", "mobi_app" to "web"), host = "https://api.vc.bilibili.com", referer = "https://message.bilibili.com/").arr("messages").objects().reversed().map { m -> MessageItem(if (m.optLong("sender_uid").toString() == cookies.value("DedeUserID")) "我" else "对方", runCatching { JSONObject(m.optString("content")).optString("content", "[非文字消息]") }.getOrDefault("[非文字消息]")) }
    suspend fun uploads(mid: Long, page: Int): FeedPage {
        val j = json("/x/space/wbi/arc/search", mapOf("mid" to "$mid", "pn" to "$page", "ps" to "30", "order" to "pubdate"), sign = true, referer = "https://space.bilibili.com/$mid/")
        val videos = j.obj("list").arr("vlist").objects().map { Video.api(it).copy(duration = durationValue(it.optString("length"))) }
        return FeedPage(videos, more = videos.size == 30)
    }
    suspend fun createQr(): Pair<String, String> {
        val j = withContext(Dispatchers.IO) { passport.newCall(request("https://passport.bilibili.com/x/passport-login/web/qrcode/generate").build()).awaitResponse().use { JSONObject(it.body?.string().orEmpty()) } }
        if (j.optInt("code") != 0) throw ApiException(j.optInt("code"), "二维码获取失败")
        return j.obj("data").getString("url") to j.obj("data").getString("qrcode_key")
    }
    suspend fun pollQr(key: String): Int {
        return withContext(Dispatchers.IO) { passport.newCall(request("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=${Wbi.encode(key)}").build()).awaitResponse().use { response ->
            val j = JSONObject(response.body?.string().orEmpty())
            if (j.optInt("code") != 0) throw ApiException(j.optInt("code"), "登录请求失败")
            val data = j.obj("data"); val state = data.optInt("code", -1)
            if (state == 0) {
                val values = response.headers("Set-Cookie").mapNotNull { c -> Cookie.parse(response.request.url, c)?.let { it.name to it.value } }.toMap().toMutableMap()
                data.optString("url").let { runCatching { it.toHttpUrl() }.getOrNull() }?.let { url ->
                    if (url.host == "bilibili.com" || url.host.endsWith(".bilibili.com")) url.queryParameterNames.forEach { name -> url.queryParameter(name)?.let { values.putIfAbsent(name, it) } }
                }
                cookies.putLogin(values)
            }
            state
        } }
    }
    suspend fun like(video: Video, liked: Boolean) { post("/x/web-interface/archive/like", mapOf("bvid" to video.bvid, "like" to if (liked) "1" else "2"), referer = video.url) }
    suspend fun liked(aid: Long): Boolean = json("/x/web-interface/archive/has/like", mapOf("aid" to "$aid")).optInt("value") == 1
    suspend fun coin(video: Video, count: Int) { accountWrites.coin(video, count) }
    suspend fun coinCount(video: Video): Int {
        require(video.aid > 0) { "视频信息尚未完整，请重新打开视频。" }
        if (cookies.value("SESSDATA").isBlank() || cookies.value("bili_jct").isBlank()) throw ApiException(-101, "登录校验未通过，请重新扫码登录（错误码 -101）", ActionFailure.LOGIN_REQUIRED)
        warmUp()
        val data = json("/x/web-interface/archive/coins", mapOf("aid" to "${video.aid}"))
        val count = data.optInt("multiply", -1)
        if (count !in 0..2) throw ApiException(-2, "无法确认已投枚数，请刷新状态后再操作（错误码 -2）")
        return count
    }
    suspend fun favorite(aid: Long, folder: Long, add: Boolean) { post("/x/v3/fav/resource/deal", mapOf("rid" to "$aid", "type" to "2", (if (add) "add_media_ids" else "del_media_ids") to "$folder")) }
    suspend fun watchLater(aid: Long, add: Boolean) { post(if (add) "/x/v2/history/toview/add" else "/x/v2/history/toview/del", mapOf("aid" to "$aid")) }
    suspend fun follow(mid: Long, add: Boolean) { require(mid > 0); post("/x/relation/modify", mapOf("fid" to "$mid", "act" to if (add) "1" else "2", "re_src" to "11"), referer = "https://space.bilibili.com/$mid") }
    suspend fun report(aid: Long, cid: Long, progress: Long) { if (cookies.value("SESSDATA").isNotBlank()) post("/x/v2/history/report", mapOf("aid" to "$aid", "cid" to "$cid", "progress" to "$progress", "platform" to "android")) }
    suspend fun sendDanmaku(cid: Long, text: String, positionMs: Long) { post("/x/v2/dm/post", mapOf("type" to "1", "oid" to "$cid", "msg" to text, "progress" to "$positionMs", "color" to "16777215", "fontsize" to "25", "pool" to "0", "mode" to "1", "rnd" to "${System.currentTimeMillis() * 1000}")) }
    suspend fun sendComment(aid: Long, text: String) { post("/x/v2/reply/add", mapOf("oid" to "$aid", "type" to "1", "message" to text, "plat" to "1")) }
    suspend fun sendMessage(talker: Long, text: String) { post("/web_im/v1/web_im/send_msg", mapOf("msg[sender_uid]" to cookies.value("DedeUserID"), "msg[receiver_id]" to "$talker", "msg[receiver_type]" to "1", "msg[msg_type]" to "1", "msg[msg_status]" to "0", "msg[content]" to JSONObject().put("content", text).toString(), "msg[timestamp]" to "${System.currentTimeMillis() / 1000}", "msg[dev_id]" to java.util.UUID.randomUUID().toString(), "build" to "0", "mobi_app" to "web"), host = "https://api.vc.bilibili.com") }
    suspend fun sponsors(bvid: String): List<Segment> {
        if (!store.settings.value.sponsor) return emptyList()
        return try {
            withTimeout(3000) { withContext(Dispatchers.IO) {
                val cleanClient = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                cleanClient.newCall(Request.Builder().url("https://www.bsbsb.top/api/skipSegments?videoID=${Wbi.encode(bvid)}").build()).awaitResponse().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    JSONArray(r.body?.string().orEmpty()).objects().map { j -> Segment(j.optLong("cid"), (j.arr("segment").optDouble(0) * 1000).toLong(), (j.arr("segment").optDouble(1) * 1000).toLong(), j.optString("category"), j.optString("actionType"), j.optInt("votes")) }
                }
            } }
        } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; emptyList() }
    }
}
