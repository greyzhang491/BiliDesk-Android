// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep cancellation attached until the body is consumed, including DNS and body stalls. */
private suspend fun Call.awaitJson(): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, failure: IOException) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    if (!it.isSuccessful) throw ApiException(it.code,
                        if (it.code in setOf(412, 429)) "当前网络请求过于频繁，请稍后重试" else "服务请求失败（HTTP ${it.code}）")
                    try { JSONObject(it.body?.string().orEmpty()) }
                    catch (failure: org.json.JSONException) { throw ApiException(-2, "服务返回了无法识别的内容，请稍后重试") }
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    })
}

/** GET metadata only. Account writes retain their separate, non-replaying client. */
internal suspend fun readJson(client: OkHttpClient, request: Request, deadlineMs: Long = 9000): JSONObject {
    repeat(2) { attempt ->
        val started = System.nanoTime()
        try {
            return withTimeoutOrNull(deadlineMs) { client.newCall(request).awaitJson() }
                ?: throw InterruptedIOException("metadata deadline exceeded")
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            // A timed out request has already spent its budget. Retry only quick transport
            // failures, once; never retry API rejections or replay an account operation.
            if (attempt == 0 && failure !is ApiException && failure !is InterruptedIOException &&
                (System.nanoTime() - started) < 2_000_000_000L) { delay(250); return@repeat }
            throw if (failure is ApiException) failure else IOException(networkMessage(failure), failure)
        }
    }
    error("unreachable")
}

fun networkMessage(failure: Throwable): String = when (failure) {
    is CancellationException -> throw failure
    is UnknownHostException -> "暂时无法连接网络，已保留当前内容，请检查连接后重试"
    is InterruptedIOException -> "网络响应较慢，已保留当前内容，请稍后重试"
    is ConnectException -> "网络连接中断，请恢复连接后重试"
    else -> failure.message?.takeUnless { it.contains("timeout", true) || it.contains("failed to connect", true) }
        ?: "网络暂时不可用，请稍后重试"
}
