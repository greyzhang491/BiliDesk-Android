// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.*
import org.json.JSONArray
import org.json.JSONObject

/** Controlled byte transport and genuine indexed, separately encoded MP4 tracks.
 * Test-only interception does not benchmark DNS/TLS/carriers or contact Bilibili.
 */
class NetworkTestMedia(context: Context) {
    data class Read(val host: String, val track: String, val start: Long, val length: Long)
    val requests = CopyOnWriteArrayList<Read>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    @Volatile var bytesPerSecond = 600_000L
    @Volatile var breakPrimary = true
    @Volatile var offline = false
    private val root = File(context.getExternalFilesDir(null), "network-media")
    private fun file(track: String) = File(root, if (track == "audio") "audio.m4a" else "$track.mp4").also { check(it.isFile) { "Missing indexed test track" } }
    private fun ranges(track: String): Pair<String, String> {
        RandomAccessFile(file(track), "r").use { input ->
            var pos = 0L; var init = ""; var index = ""
            while (pos + 8 <= input.length()) {
                input.seek(pos); var size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                if (size == 1L) size = input.readLong()
                if (size < 8 || pos + size > input.length()) break
                if (type == "moov") init = "0-${pos + size - 1}"
                if (type == "sidx") { index = "$pos-${pos + size - 1}"; break }
                pos += size
            }
            check(init.isNotBlank() && index.isNotBlank())
            return init to index
        }
    }
    fun plan(): JSONObject {
        fun stream(track: String, q: Int, bandwidth: Int, width: Int, height: Int): JSONObject {
            val (init, index) = ranges(track)
            return JSONObject().put("id", q).put("baseUrl", "https://primary.fixture.invalid/$track")
                .put("backupUrl", JSONArray().put("https://backup.fixture.invalid/$track"))
                .put("codecs", if (track == "audio") "mp4a.40.2" else "avc1.640028")
                .put("mimeType", if (track == "audio") "audio/mp4" else "video/mp4").put("bandwidth", bandwidth)
                .put("width", width).put("height", height).put("frameRate", "24")
                .put("SegmentBase", JSONObject().put("Initialization", init).put("indexRange", index))
        }
        return JSONObject().put("timelength", 95000).put("dash", JSONObject()
            .put("video", JSONArray().put(stream("high", 80, 700000, 640, 360)).put(stream("low", 16, 160000, 320, 180)))
            .put("audio", JSONArray().put(stream("audio", 30216, 64000, 0, 0))))
    }
    fun respond(request: Request): Response {
        if (offline) throw IOException("Simulated disconnected network")
        val track = request.url.encodedPath.removePrefix("/")
        val payload = file(track)
        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.header("Range").orEmpty())
        val start = range?.groupValues?.get(1)?.toLong() ?: 0
        val end = minOf(range?.groupValues?.get(2)?.toLongOrNull() ?: (payload.length() - 1), payload.length() - 1)
        check(start in 0..end)
        requests += Read(request.url.host, track, start, end - start + 1)
        check(request.header("Cookie") == null) { "Media requests leaked account cookies" }
        val input = RandomAccessFile(payload, "r").apply { seek(start) }
        val indexEnd = ranges(track).second.substringAfter('-').toLong()
        val eligible = if (track == "audio") start > indexEnd && end > start else end - start > 40000
        val shouldBreak = breakPrimary && request.url.host.startsWith("primary.") && eligible && failed.add(track)
        val breakAt = if (track == "audio") minOf(8192L, (end - start + 1) / 2).coerceAtLeast(1) else 32768L
        val body = object : ResponseBody() {
            private val buffered = object : Source {
                var read = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (offline || shouldBreak && read >= breakAt) throw IOException("Simulated mid-segment disconnect")
                    if (read >= end - start + 1) return -1
                    val limit = if (shouldBreak) breakAt - read else Long.MAX_VALUE
                    val bytes = ByteArray(minOf(byteCount, 8192, end - start + 1 - read, limit).toInt())
                    val count = input.read(bytes)
                    if (count > 0) {
                        if (bytesPerSecond > 0) SystemClock.sleep((count * 1000 / bytesPerSecond).coerceAtLeast(1))
                        sink.write(bytes, 0, count); read += count
                    }
                    return count.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() = input.close()
            }.buffer()
            override fun contentType() = (if (track == "audio") "audio/mp4" else "video/mp4").toMediaType()
            override fun contentLength() = end - start + 1
            override fun source(): BufferedSource = buffered
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (range == null) 200 else 206).message("controlled-media")
            .header("Content-Range", "bytes $start-$end/${payload.length()}").body(body).build()
    }
}
