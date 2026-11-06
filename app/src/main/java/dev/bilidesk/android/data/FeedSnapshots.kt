// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, private metadata snapshots. No HTTP responses, cookies or media bytes. */
class FeedSnapshots(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
    suspend fun read(key: String): List<Video> = withContext(Dispatchers.IO) {
        synchronized(this@FeedSnapshots) {
            runCatching {
                val target = file(key)
                if (!target.isFile || target.length() > 160_000 || now() - target.lastModified() > 7 * 86400000L) return@synchronized emptyList()
                JSONObject(target.readText()).arr("videos").objects().take(32).map(Video::from)
            }.getOrDefault(emptyList())
        }
    }
    suspend fun write(key: String, videos: List<Video>) = withContext(Dispatchers.IO) {
        synchronized(this@FeedSnapshots) {
            runCatching {
                directory.mkdirs()
                val body = JSONObject().put("videos", JSONArray(videos.take(32).map(Video::json))).toString()
                if (body.toByteArray().size <= 160_000) {
                    val target = file(key)
                    val tmp = File(directory, "${target.name}.tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(target)) tmp.delete()
                }
                var bytes = 0L
                directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                    bytes += entry.length()
                    if (index >= 8 || bytes > 1024 * 1024 || now() - entry.lastModified() > 7 * 86400000L) entry.delete()
                }
            }
            Unit
        }
    }
}
