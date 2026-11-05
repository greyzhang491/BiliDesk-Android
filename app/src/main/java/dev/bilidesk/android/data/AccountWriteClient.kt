// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class ActionFailure { OTHER, LOGIN_REQUIRED, VERIFICATION_REQUIRED, REJECTED, RATE_LIMITED }

class WriteOutcomeUnknown(cause: Throwable? = null, val httpStatus: Int? = null): IOException("未能确认提交结果${httpStatus?.let { "（HTTP $it）" }.orEmpty()}，请先查看平台记录，确认后再决定是否重试。", cause)

/** Preserve the business code and challenge category, without exposing cookies or challenge tokens. */
object ActionErrors {
    fun from(root: JSONObject): ApiException {
        val code = root.optInt("code", -999)
        val raw = root.optString("message").ifBlank { root.optString("msg") }.trim().take(240).ifBlank { "操作失败" }
        val data = root.obj("data")
        val decisions = data.obj("ga_data").arr("decisions")
        val challenge = data.optString("v_voucher").isNotBlank() ||
            (0 until decisions.length()).any { decisions.optString(it).startsWith("verify_captcha") }
        val kind = when {
            code == -101 || code == -111 -> ActionFailure.LOGIN_REQUIRED
            challenge -> ActionFailure.VERIFICATION_REQUIRED
            code in listOf(-412, -509, 429) -> ActionFailure.RATE_LIMITED
            code in listOf(-401, -403, -352) || raw.contains("非法访问") || raw.contains("账号异常") -> ActionFailure.REJECTED
            else -> ActionFailure.OTHER
        }
        val hint = when (kind) {
            ActionFailure.LOGIN_REQUIRED -> "登录校验未通过，请重新扫码登录。"
            ActionFailure.VERIFICATION_REQUIRED -> "B站要求完成验证，请到官方App或网页按提示处理后再返回。"
            ActionFailure.REJECTED -> "本次请求被B站拒绝，具体原因尚未确认；请在官方页面确认登录或验证状态。"
            ActionFailure.RATE_LIMITED -> "操作暂时受限，请稍后再试。"
            else -> ""
        }
        return ApiException(code, "$raw（错误码 $code）${if (hint.isBlank()) "" else "\n$hint"}", kind)
    }
}

/** Account writes are never replayed automatically, including after a transport failure or redirect. */
class AccountWriteClient(
    client: OkHttpClient,
    private val csrf: () -> String,
    private val hasSession: () -> Boolean,
    private val prepareSession: suspend () -> Unit,
    private val base: String = "https://api.bilibili.com"
) {
    private val http = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val coining = AtomicBoolean(false)

    suspend fun post(path: String, params: Map<String, String>, host: String = base, referer: String = "https://www.bilibili.com/"): JSONObject {
        fun requireLogin() {
            if (!hasSession() || csrf().isBlank()) throw ApiException(-101, "登录校验未通过，请重新扫码登录（错误码 -101）", ActionFailure.LOGIN_REQUIRED)
        }
        requireLogin()
        prepareSession() // Standard read-only device-cookie preparation, as in the upstream client.
        requireLogin()
        val token = csrf()
        val origin = referer.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val body = FormBody.Builder().apply { (params + mapOf("csrf" to token, "csrf_token" to token)).forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(host + path).header("User-Agent", BILI_UA)
            .header("Referer", referer).header("Origin", origin).header("Accept", "application/json, text/plain, */*").post(body).build()
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).awaitResponse().use { response ->
                    val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    if (root?.has("code") == true && root.optInt("code", -999) != 0) throw ActionErrors.from(root)
                    if (response.code in 300..399) throw ApiException(response.code, "请求被重定向（HTTP ${response.code}），请到官方页面确认登录或验证状态。", ActionFailure.REJECTED)
                    if (!response.isSuccessful || root == null || !root.has("code")) throw WriteOutcomeUnknown(httpStatus = response.code)
                    root.obj("data")
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: ApiException) { throw e }
          catch (e: WriteOutcomeUnknown) { throw e }
          catch (e: IOException) { throw WriteOutcomeUnknown(e) }
    }

    suspend fun coin(video: Video, count: Int) {
        require(video.kind == "video" && video.aid > 0) { "视频信息尚未完整，请重新打开视频后再投币。" }
        require(count in 1..2) { "投币数量必须为1或2枚。" }
        if (!coining.compareAndSet(false, true)) throw ApiException(-3, "已有投币请求正在处理，请先等待结果。")
        try { post("/x/web-interface/coin/add", mapOf("aid" to "${video.aid}", "multiply" to "$count", "select_like" to "0"), referer = video.url) }
        finally { coining.set(false) }
    }
}
