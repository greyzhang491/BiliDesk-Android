// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.download

import dev.bilidesk.android.data.awaitResponse
import dev.bilidesk.android.data.BILI_UA
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

internal class DownloadHttpException(val status: Int): IOException(when (status) {
    401 -> "登录已失效，请重新登录后继续"
    403, 410 -> "下载地址已失效，请刷新地址后继续"
    412, 429 -> "平台暂时限制此请求（HTTP $status），请稍后再试"
    else -> "下载请求失败（HTTP $status）"
})
internal class DownloadLimitExceeded(message: String): IOException(message)
internal data class TransferProgress(val bytes: Long, val total: Long, val bytesPerSecond: Long, val retry: Int = 0)

/** Bounded ranges, resumable files and only server-supplied mirrors. Never stores signed URLs.
 * Unlike a large, unbounded request, a failed range keeps the bytes already written. A server
 * ignoring Range restarts its full response safely instead of appending duplicate content.
 */
internal class RangeDownloader(
    private val client: OkHttpClient,
    private val chunkBytes: Long = 8L * 1024 * 1024,
    private val maxRetries: Int = 4,
    private val retryDelayMs: Long = 1000
) {
    suspend fun download(
        urls: List<String>, destination: File, identity: String, referer: String, limit: Long = Long.MAX_VALUE,
        checkAllowed: () -> Unit = {}, progress: suspend (TransferProgress) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        require(urls.isNotEmpty() && chunkBytes > 0)
        val sidecar = File(destination.path + ".resume")
        var metadata = runCatching { JSONObject(sidecar.readText()) }.getOrElse { JSONObject() }
        if (metadata.optString("identity") != identity || !destination.exists() || metadata.optLong("total", -1).let { it >= 0 && destination.length() > it }) {
            destination.delete(); sidecar.delete(); metadata = JSONObject().put("identity", identity)
        }
        destination.parentFile?.mkdirs()
        var total = metadata.optLong("total", -1)
        var failures = 0
        var mirror = 0
        var previousBytes = destination.length()
        var previousTime = System.nanoTime()
        var speed = 0L
        var resetCount = 0
        suspend fun report(force: Boolean = false, retry: Int = 0) {
            val now = System.nanoTime()
            val bytes = destination.length()
            if (force || now - previousTime >= 500_000_000L) {
                checkAllowed()
                val delta = now - previousTime
                speed = if (retry > 0) 0 else if (delta >= 100_000_000L) ((bytes - previousBytes).coerceAtLeast(0) * 1_000_000_000.0 / delta).toLong() else speed
                progress(TransferProgress(bytes, total, speed, retry))
                previousBytes = bytes; previousTime = now
            }
        }
        fun checkpoint() {
            metadata.put("identity", identity).put("total", total)
            val temporary = File(sidecar.path + ".tmp")
            temporary.writeText(metadata.toString())
            if (!temporary.renameTo(sidecar)) { sidecar.writeText(metadata.toString()); temporary.delete() }
        }
        report(true)
        while (true) {
            ensureActive(); checkAllowed()
            val offset = destination.length()
            if (total >= 0 && offset == total) return@withContext offset
            if (offset > limit) throw DownloadLimitExceeded("超过缓存容量上限，已停止")
            val end = min(if (total > 0) total - 1 else Long.MAX_VALUE, offset + chunkBytes - 1)
            val request = Request.Builder().url(urls[mirror % urls.size]).header("Referer", referer)
                .header("User-Agent", BILI_UA).header("Accept-Encoding", "identity").header("Range", "bytes=$offset-$end")
            metadata.optString("etag").takeIf { it.isNotBlank() && !it.startsWith("W/") }?.let { request.header("If-Range", it) }
            val call = client.newCall(request.build())
            try {
                coroutineScope {
                    // Cancellation must close an in-progress blocking body read, not wait for it.
                    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                    try {
                        call.awaitResponse().use { response ->
                            if (!response.isSuccessful) throw DownloadHttpException(response.code)
                            val body = response.body ?: throw IOException("下载内容为空")
                            var responseBytes = body.contentLength()
                            if (response.code == 206) {
                                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                                    ?: throw IOException("下载服务器返回了无效的分段信息")
                                val (start, last, size) = range.destructured.toList().map(String::toLong)
                                if (start != offset || last < start || last > end || size <= last) throw IOException("下载分段位置不匹配")
                                val changed = total > 0 && total != size || metadata.optString("etag").let { it.isNotBlank() && response.header("ETag") != null && it != response.header("ETag") }
                                if (changed) {
                                    if (++resetCount > 1) throw DownloadLimitExceeded("下载源内容发生变化，请重新下载")
                                    destination.delete(); metadata.remove("etag"); total = -1; checkpoint()
                                    return@use
                                }
                                total = size
                                responseBytes = last - start + 1
                                if (body.contentLength() >= 0 && body.contentLength() != last - start + 1) throw IOException("下载分段长度不匹配")
                            } else if (response.code == 200) {
                                if (offset > 0) { RandomAccessFile(destination, "rw").use { it.setLength(0) }; previousBytes = 0 }
                                total = body.contentLength()
                            } else throw IOException("下载响应不受支持（HTTP ${response.code}）")
                            if (total > limit) throw DownloadLimitExceeded("超过缓存容量上限，已停止")
                            if (total > 0 && destination.parentFile!!.usableSpace < (total - destination.length()).coerceAtLeast(0) + total + 64L * 1024 * 1024) throw DownloadLimitExceeded("存储空间不足，请腾出空间后继续")
                            response.header("ETag")?.let { metadata.put("etag", it) }
                            checkpoint()
                            val start = destination.length()
                            var received = 0L
                            body.byteStream().use { input -> RandomAccessFile(destination, "rw").use { output ->
                                output.seek(start)
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (responseBytes >= 0 && received + n > responseBytes) throw IOException("下载服务器返回了多余的分段数据")
                                    if (output.filePointer + n > limit) throw DownloadLimitExceeded("超过缓存容量上限，已停止")
                                    output.write(buffer, 0, n); received += n
                                    report()
                                }
                                output.fd.sync()
                            } }
                            if (responseBytes >= 0 && received != responseBytes) throw IOException("下载连接中断，正在保留进度")
                            if (response.code == 200 && total < 0) { total = destination.length(); checkpoint() }
                            if (destination.length() == start) throw IOException("下载服务器没有返回数据")
                        }
                    } finally { cancellation.cancelAndJoin() }
                }
                failures = 0
                report(true)
            } catch (error: IOException) {
                ensureActive()
                if (error is DownloadLimitExceeded || error is DownloadHttpException && error.status in setOf(401, 403, 410, 412, 429)) throw error
                failures++
                if (failures > maxRetries) throw IOException("下载中断，已保留进度；请检查网络后继续", error)
                mirror++
                report(true, failures)
                delay(retryDelayMs * failures)
            }
        }
        @Suppress("UNREACHABLE_CODE") 0L
    }
    fun discard(destination: File) {
        destination.delete(); File(destination.path + ".resume").delete(); File(destination.path + ".resume.tmp").delete()
    }
}
