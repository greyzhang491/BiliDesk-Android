// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import org.json.JSONObject

data class CommentEmote(val token: String, val url: String, val size: Int = 1)
data class CommentPicture(val url: String, val width: Int = 0, val height: Int = 0)
data class ReplyTarget(val root: Long, val parent: Long, val name: String)

/** Offsets refer to the original text, so punctuation, line breaks and emoji survive. */
data class CommentSpan(val start: Int, val end: Int, val emote: CommentEmote? = null, val mid: Long = 0, val mention: Boolean = false)
object CommentContent {
    fun spans(text: String, emotes: List<CommentEmote>, mentions: Map<String, Long>): List<CommentSpan> {
        val tokens = emotes.filter { it.token.isNotEmpty() }.associateBy { it.token }
        val names = mentions.filter { it.key.isNotBlank() && it.value > 0 }.keys.sortedByDescending { it.length }
        val result = mutableListOf<CommentSpan>()
        var index = 0
        while (index < text.length) {
            val emote = tokens.values.filter { text.startsWith(it.token, index) }.maxByOrNull { it.token.length }
            if (emote != null) { result.add(CommentSpan(index, index + emote.token.length, emote)); index += emote.token.length; continue }
            if (text[index] == '@') {
                val name = names.firstOrNull { text.startsWith("@$it", index) && (index + it.length + 1 == text.length || text[index + it.length + 1].let { ch -> ch.isWhitespace() || ch in "：:，,。！？!?；;、)）]】" }) }
                val end = if (name != null) index + name.length + 1 else {
                    var next = index + 1
                    while (next < text.length && !text[next].isWhitespace() && text[next] !in "：:，,。！？!?；;、)）]】") next++
                    next
                }
                if (end > index + 1) { result.add(CommentSpan(index, end, mid = name?.let { mentions[it] } ?: 0, mention = true)); index = end; continue }
            }
            index++
        }
        return result
    }
}

fun Comment.replyTarget(fallbackRoot: Long = 0): ReplyTarget {
    require(id > 0) { "评论已不可用" }
    return ReplyTarget(rootId.takeIf { it > 0 } ?: fallbackRoot.takeIf { it > 0 } ?: id, id, name)
}

/** A successful like/dislike replaces the other reaction; dislike has no public count. */
fun Comment.withReaction(positive: Boolean, on: Boolean): Comment {
    val nextLiked = if (positive) on else if (on) false else liked
    return copy(liked = nextLiked, disliked = if (!positive) on else if (on) false else disliked,
        likes = (likes + (if (nextLiked) 1 else 0) - (if (liked) 1 else 0)).coerceAtLeast(0))
}

data class CreatorProfile(val mid: Long, val name: String, val avatar: String, val sign: String = "", val level: Int? = null,
    val vip: Boolean = false, val official: String = "", val fans: Long? = null, val following: Long? = null, val videoCount: Long? = null) {
    companion object {
        fun parse(mid: Long, data: JSONObject): CreatorProfile {
            val card = data.optJSONObject("card")
            val source = card ?: data
            fun count(j: JSONObject, key: String): Long? = when (val value = j.opt(key)) { is Number -> value.toLong(); is String -> value.toLongOrNull(); else -> null }?.takeIf { it >= 0 }
            val level = if (card != null) source.obj("level_info").optInt("current_level", -1) else source.optInt("level", -1)
            return CreatorProfile(mid, source.optString("name").ifBlank { "用户 $mid" }, source.optString("face").https(), source.optString("sign"), level.takeIf { it in 0..6 },
                source.obj("vip").optInt("status", source.obj("vip").optInt("vipStatus")) == 1,
                source.obj("Official").optString("desc").ifBlank { source.obj("official").optString("title") },
                count(data, "follower") ?: count(source, "fans"), count(source, "attention") ?: count(data, "following"), count(data, "archive_count"))
        }
    }
}
