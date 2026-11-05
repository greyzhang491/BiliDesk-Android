// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bilidesk.android.data.ApiException
import dev.bilidesk.android.data.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only; it never posts account interactions or exports account content. */
@RunWith(AndroidJUnit4::class)
class AccountReadDeviceTest {
    @Test fun authenticatedAccountListsLoad() = runBlocking {
        assumeTrue("Account tests require explicit opt-in", InstrumentationRegistry.getArguments().getString("accountReads") == "true")
        val user = Graph.api.user()
        assertNotNull("The scanned session was not restored", user)
        assertTrue(user!!.mid > 0)
        assertEquals("Account identity cookie mismatch", user.mid.toString(), Graph.cookies.value("DedeUserID"))
        val results = JSONArray()
        val failures = mutableListOf<String>()
        suspend fun check(name: String, read: suspend () -> Int) {
            delay(600)
            val result = JSONObject().put("feature", name)
            try { result.put("ok", true).put("count", read()) }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                result.put("ok", false).put("errorType", e.javaClass.simpleName)
                if (e is ApiException) result.put("code", e.code)
                failures.add("$name (${if (e is ApiException) e.code else e.javaClass.simpleName})")
            }
            results.put(result)
        }
        fun validVideos(videos: List<Video>): Int {
            assertTrue("Response contains videos without identifiers", videos.all { it.bvid.isNotBlank() || it.aid > 0 || it.targetId > 0 })
            return videos.size
        }
        var firstFolder: Long? = null
        check("收藏夹") { val folders = Graph.api.folders(); assertTrue(folders.all { it.id > 0 && it.title.isNotBlank() }); firstFolder = folders.firstOrNull()?.id; folders.size }
        firstFolder?.let { folder -> check("收藏视频") { validVideos(Graph.api.favorites(folder, 1).videos) } }
        check("云端历史") { validVideos(Graph.api.cloudHistory(1).videos) }
        check("稍后再看") { validVideos(Graph.api.later().videos) }
        check("我的追番") { validVideos(Graph.api.bangumi(1, mine = true).videos) }
        check("关注动态") { val feed = Graph.api.dynamics().first; assertTrue(feed.all { it.id.isNotBlank() }); feed.size }
        for (type in listOf("回复", "@我", "点赞", "私信")) check("消息-$type") { Graph.api.messages(type).size }
        val report = JSONObject().put("authenticated", true).put("scope", "read-only").put("results", results)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "account-read-results.json").writeText(report.toString(2))
        assertTrue("Account reads failed: ${failures.joinToString()}", failures.isEmpty())
    }
}
