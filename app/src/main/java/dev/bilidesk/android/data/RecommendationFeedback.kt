// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Ephemeral feed context. Deliberately excluded from Video.json and encrypted cloud sync. */
data class RecommendationContext(val trackId: String, val account: String, val target: String = "av")

enum class RecommendationReason(val serverId: Int, val label: String) {
    CONTENT(1, "内容不感兴趣"), CREATOR(4, "不想看这个UP主")
}

data class RecommendationRule(val aid: Long, val bvid: String, val mid: Long, val title: String, val author: String,
    val reason: RecommendationReason, val at: Long) {
    val key get() = if (reason == RecommendationReason.CREATOR) "up:$mid" else "video:${if (aid > 0) "av$aid" else bvid}"
    fun matches(video: Video) = video.kind == "video" && if (reason == RecommendationReason.CREATOR) mid > 0 && video.mid == mid
        else aid > 0 && video.aid == aid || bvid.isNotBlank() && video.bvid == bvid
    fun json() = JSONObject().put("aid", aid).put("bvid", bvid).put("mid", mid).put("title", title.take(500)).put("author", author.take(100)).put("reason", reason.name).put("at", at)
    companion object {
        fun from(j: JSONObject) = RecommendationRule(j.getLong("aid"), j.getString("bvid"), j.getLong("mid"), j.getString("title").take(500), j.getString("author").take(100), RecommendationReason.valueOf(j.getString("reason")), j.getLong("at"))
    }
}

/** Local preferences are account-scoped and bounded, and do not participate in music cloud sync. */
class RecommendationFilters(context: Context, preferenceName: String = "recommendation_filters") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(prefs.all.filterKeys { it.startsWith("account-") }.mapValues { (_, value) ->
        runCatching { JSONArray(value as String).objects().take(1000).map(RecommendationRule::from) }.getOrDefault(emptyList())
    })
    val state = _state.asStateFlow()
    fun rules(account: String) = _state.value[scope(account)].orEmpty()
    suspend fun add(account: String, video: Video, reason: RecommendationReason) {
        require(video.kind == "video" && (video.aid > 0 || video.bvid.matches(Regex("BV[a-zA-Z0-9]{10}"))))
        require(reason != RecommendationReason.CREATOR || video.mid > 0)
        val rule = RecommendationRule(video.aid, video.bvid, video.mid, video.title, video.author, reason, System.currentTimeMillis())
        update(account) { before -> (listOf(rule) + before.filterNot { it.key == rule.key }).take(1000) }
    }
    suspend fun remove(account: String, key: String) = update(account) { it.filterNot { rule -> rule.key == key } }
    internal suspend fun clear(account: String) = update(account) { emptyList() }
    private suspend fun update(account: String, transform: (List<RecommendationRule>) -> List<RecommendationRule>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = scope(account); val next = transform(_state.value[key].orEmpty())
            check(prefs.edit().putString(key, JSONArray(next.map(RecommendationRule::json)).toString()).commit()) { "偏好保存失败，请重试" }
            _state.value = _state.value + (key to next)
        }
    }
    private fun scope(account: String) = "account-" + MessageDigest.getInstance("SHA-256").digest(account.ifBlank { "guest" }.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
}
