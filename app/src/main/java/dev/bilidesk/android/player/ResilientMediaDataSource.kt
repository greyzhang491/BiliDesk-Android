// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Reject a CDN that ignores a seek, instead of silently downloading the entire prefix. */
class MediaRangeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val start = chain.request().header("Range")?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull() ?: 0
        if (start > 0 && (response.code != 206 ||
            response.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.toLongOrNull() != start)) {
            response.close()
            throw java.net.ProtocolException("视频线路未支持此进度，请切换线路")
        }
        return response
    }
}

/** Each audio/video request independently resumes on server-supplied backup URLs. */
class ResilientMediaDataSource(private val factory: DataSource.Factory, private val alternatives: Map<String, List<String>>) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var source: DataSource? = null
    private var original: DataSpec? = null
    private var urls: List<String> = emptyList()
    private var attempt = 0
    private var delivered = 0L
    private var expected = C.LENGTH_UNSET.toLong()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; source?.addTransferListener(transferListener) }
    override fun getUri(): Uri? = source?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
    override fun open(dataSpec: DataSpec): Long {
        original = dataSpec
        urls = (listOf(dataSpec.uri.toString()) + alternatives[dataSpec.uri.toString()].orEmpty()).distinct().take(3)
        delivered = 0; attempt = 0
        expected = openNext()
        return expected
    }
    private fun openNext(): Long {
        var last: IOException? = null
        while (attempt < urls.size) {
            source?.let { runCatching { it.close() } }; source = null
            val spec = original!!.subrange(delivered).buildUpon().setUri(urls[attempt++]).build()
            val next = factory.createDataSource()
            source = next
            listeners.forEach(next::addTransferListener)
            try { return next.open(spec) } catch (failure: IOException) { last = failure }
        }
        source?.let { runCatching { it.close() } }; source = null
        throw last ?: IOException("视频线路暂时不可用，请恢复网络后重试")
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            try {
                val read = source?.read(buffer, offset, length) ?: throw IOException("视频连接已关闭")
                if (read == C.RESULT_END_OF_INPUT && expected != C.LENGTH_UNSET.toLong() && delivered < expected) throw EOFException("视频连接提前中断")
                if (read > 0) delivered += read
                return read
            } catch (failure: IOException) {
                if (attempt >= urls.size || expected >= 0 && delivered >= expected) throw failure
                openNext()
            }
        }
    }
    override fun close() { try { source?.close() } finally { source = null; original = null } }
}
