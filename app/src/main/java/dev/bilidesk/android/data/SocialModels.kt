// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import org.json.JSONObject

enum class CommentOrder(val label: String) { HOT("最热"), NEWEST("最新"), OLDEST("最早") }
data class CommentPage(val comments: List<Comment>, val total: Long = -1, val next: Int = 0, val more: Boolean = false)

object SocialModels {
    fun following(attribute: Int) = attribute >= 0 && attribute and 128 == 0 && (attribute == 1 || attribute and 2 != 0)
    fun comment(j: JSONObject, previews: Boolean = true): Comment {
        fun id(name: String) = j.optString(name + "_str").toLongOrNull() ?: j.optLong(name)
        val children = if (previews) j.arr("replies").objects().map { comment(it, false) } else emptyList()
        return Comment(j.obj("member").optString("uname", "用户"), j.obj("member").optString("avatar").https(), j.obj("content").optString("message"), j.optLong("like").coerceAtLeast(0),
            id("rpid"), j.optLong("mid", j.obj("member").optLong("mid")), j.optLong("ctime"), j.optInt("action") == 1,
            maxOf(j.optInt("rcount"), j.optInt("count"), children.size), id("root"), id("parent"), children)
    }
    fun page(j: JSONObject, page: Int, order: CommentOrder, replies: Boolean = false, size: Int = 20): CommentPage {
        val body = j.arr("replies").objects().map { comment(it, !replies) }
        val pinned = if (!replies && order == CommentOrder.HOT && page == 0) {
            val top = j.obj("top")
            (listOfNotNull(top.optJSONObject("upper"), top.optJSONObject("admin")) + j.arr("top_replies").objects()).map { comment(it).copy(pinned = true) }
        } else emptyList()
        val cursor = j.optJSONObject("cursor")
        val total = j.obj("page").optLong("count", cursor?.optLong("all_count", -1) ?: -1)
        val sorted = if (order == CommentOrder.OLDEST && !replies) body.reversed() else body
        val items = (pinned + sorted).distinctBy { it.key }
        val next = when {
            order == CommentOrder.OLDEST && !replies -> (page - 1).coerceAtLeast(0)
            cursor != null && !replies -> cursor.optInt("next", page + 1).takeIf { it > page } ?: page + 1
            else -> page + 1
        }
        val more = when {
            order == CommentOrder.OLDEST && !replies -> page > 1
            cursor != null && !replies -> !cursor.optBoolean("is_end", body.size < size) && body.isNotEmpty()
            total >= 0 -> page.toLong() * size < total && body.isNotEmpty()
            else -> body.size >= size
        }
        return CommentPage(items, total, next, more)
    }
    fun folderTitle(raw: String): String {
        val title = raw.trim()
        require(title.isNotBlank() && title.codePointCount(0, title.length) <= 20) { "收藏夹名称请填写1～20个字" }
        return title
    }
}

data class CollectionEntry(val video: Video, val cid: Long = 0, val title: String = "", val section: String = "")
data class VideoCollection(val id: Long, val title: String, val entries: List<CollectionEntry>, val declaredCount: Int = entries.size)
object VideoCollections {
    fun parse(data: JSONObject, owner: Video): VideoCollection? {
        val source = data.optJSONObject("ugc_season") ?: return null
        val entries = source.arr("sections").objects().flatMap { section ->
            section.arr("episodes").objects().mapNotNull { item ->
                val arc = item.obj("arc")
                val bv = item.optString("bvid").ifBlank { arc.optString("bvid") }
                val aid = item.optLong("aid", arc.optLong("aid"))
                if (bv.isBlank() && aid <= 0) return@mapNotNull null
                val summary = Video.api(arc).copy(bvid = bv, aid = aid,
                    title = item.optString("title").clean().ifBlank { arc.optString("title").clean() },
                    cover = arc.optString("pic").https().ifBlank { owner.cover },
                    author = arc.obj("owner").optString("name").ifBlank { owner.author },
                    mid = arc.obj("owner").optLong("mid", owner.mid))
                CollectionEntry(summary, item.optLong("cid").takeIf { it > 0 } ?: item.obj("page").optLong("cid"), summary.title, section.optString("title").clean())
            }
        }.distinctBy { "${it.video.key}:${it.cid}" }.take(5000)
        return VideoCollection(source.optLong("id"), source.optString("title").clean(), entries, source.optInt("ep_count", entries.size)).takeIf { entries.isNotEmpty() }
    }
}
