// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Context
import android.graphics.*
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent illustrations and fake profiles. Never included in the application APK. */
class CommentTestData(context: Context, private val reaction: (Long) -> Int) {
    private val images = (0..3).map { index ->
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(listOf(0xFFDBE7F9.toInt(), 0xFFFFD6DF.toInt(), 0xFFD4EEDF.toInt(), 0xFFFFE8BC.toInt())[index])
        paint.color = Color.WHITE; canvas.drawCircle(240f, 240f, 170f, paint)
        paint.color = listOf(0xFF6B89BF.toInt(), 0xFFD9869D.toInt(), 0xFF70A88B.toInt(), 0xFFD5A64D.toInt())[index]
        canvas.drawRoundRect(100f, 160f, 380f, 330f, 58f, 58f, paint)
        paint.color = Color.WHITE; canvas.drawCircle(185f, 240f, 16f, paint); canvas.drawCircle(295f, 240f, 16f, paint)
        paint.textSize = 34f; paint.textAlign = Paint.Align.CENTER; paint.color = Color.DKGRAY; canvas.drawText("UI DEMO", 240f, 420f, paint)
        val file = File(context.getExternalFilesDir(null), "comment-demo-$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        Uri.fromFile(file).toString()
    }
    fun name(mid: Long) = when (mid) { 90L -> "夏日听风"; 91L -> "小行星电台"; 92L -> "山间慢慢走"; else -> "测试用户$mid" }
    fun avatar(mid: Long) = images[(mid % 3).toInt()]
    fun member(mid: Long) = JSONObject().put("mid", mid.toString()).put("uname", name(mid)).put("avatar", avatar(mid))
        .put("level_info", JSONObject().put("current_level", if (mid == 90L) 6 else 4)).put("vip", JSONObject().put("vipStatus", if (mid == 91L) 1 else 0))
    fun comment(id: Long, message: String, mid: Long = 90, root: Long = 0, parent: Long = 0): JSONObject = JSONObject().put("rpid_str", "$id").put("mid", mid)
        .put("root_str", "$root").put("parent_str", "$parent").put("ctime", 1782912000 + id).put("action", reaction(id)).put("like", 7815 + if (reaction(id) == 1) 1 else 0)
        .put("member", member(mid)).put("reply_control", JSONObject().put("location", "IP属地：${if (mid == 90L) "广西" else "上海"}"))
        .put("content", JSONObject().put("message", message).put("members", JSONArray().put(member(91)))
            .put("emote", JSONObject().put("[鼓掌]", JSONObject().put("url", images[3]).put("meta", JSONObject().put("size", 1)))))
    fun root() = comment(700, "一边听歌一边看评论，这样的排版清楚多了。\n回复 @小行星电台：一起分享喜欢的歌吧[鼓掌]")
        .put("rcount", 38).put("replies", JSONArray().put(reply(701)).put(reply(702)))
    fun reply(id: Long) = comment(id, when (id) {
        701L -> "这个唱片界面很舒服，循环了一整晚。"
        702L -> "回复 @小行星电台：我也把它加入歌单了🎵"
        else -> "第${id - 700}条测试回复，保持完整正文和独立头像。"
    }, if (id % 2 == 1L) 91 else 92, 700, if (id == 702L) 701 else 700)
    fun main(next: String?) = JSONObject().put("cursor", JSONObject().put("next", 1).put("is_end", next == "1").put("all_count", 8))
        .put("replies", if (next != "1") JSONArray().put(root()).put(comment(750, "点击头像可以查看主页，返回时仍然停在这里。", 92))
            .put(comment(751, "回复 @没有UID的用户：这条提及需要查找确认。", 91))
            .put(comment(752, "带图评论可以打开原图，表情也会保留[鼓掌]。", 92).apply { getJSONObject("content").put("pictures", JSONArray().put(JSONObject().put("img_src", images[3]).put("img_width", 480).put("img_height", 480))) })
        else JSONArray((753L..756L).map { comment(it, "自动加载的后续评论 $it", 90) }))
    fun replies(page: String?) = JSONObject().put("page", JSONObject().put("count", 38)).put("replies", JSONArray((if (page == "2") 721L..738L else 701L..720L).map { reply(it) }))
    fun profile(mid: Long) = JSONObject().put("card", JSONObject().put("mid", "$mid").put("name", name(mid)).put("face", avatar(mid)).put("sign", "把日常收藏成一张歌单。此页为离线测试资料。")
        .put("level_info", JSONObject().put("current_level", 6)).put("attention", 64).put("fans", 1280)).put("archive_count", 2)
    fun uploads(mid: Long) = JSONObject().put("page", JSONObject().put("count", 2)).put("list", JSONObject().put("vlist", JSONArray((0..1).map { index -> JSONObject()
        .put("bvid", if (index == 0) "BV17x411w7KC" else "BV156xx6wEff").put("aid", if (index == 0) 170001 else 100001)
        .put("title", "${name(mid)}的投稿 ${index + 1}").put("pic", images[index]).put("author", name(mid)).put("mid", mid).put("play", 8642).put("length", "03:20") })))
    fun searchUsers() = JSONObject().put("result", JSONArray().put(JSONObject().put("mid", 92).put("uname", "没有UID的用户").put("upic", avatar(92)).put("level", 4)))
}
