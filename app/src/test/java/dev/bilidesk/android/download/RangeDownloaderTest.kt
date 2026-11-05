// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.download

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RangeDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(256 * 1024) { ((it * 31 + it / 256) % 251).toByte() }
    private fun engine(readMs: Long = 1000, retries: Int = 2) = RangeDownloader(OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(readMs, TimeUnit.MILLISECONDS).build(), 64 * 1024, retries, 0)
    private fun partial(file: File, count: Int, etag: String = "v1", content: ByteArray = bytes) {
        file.writeBytes(content.copyOf(count))
        File(file.path + ".resume").writeText(JSONObject().put("identity", "sample").put("total", content.size).put("etag", etag).toString())
    }
    private fun range(request: RecordedRequest, content: ByteArray = bytes, etag: String = "v1"): MockResponse {
        val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
        val start = match.groupValues[1].toInt(); val end = minOf(match.groupValues[2].toInt(), content.lastIndex)
        return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${content.size}").setHeader("ETag", etag).setBody(Buffer().write(content, start, end - start + 1))
    }
    @Test fun boundedRangesProduceExactFileAndCorrectHeaders() = runBlocking {
        MockWebServer().use { server ->
            val ranges = CopyOnWriteArrayList<String>()
            server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                ranges.add(request.getHeader("Range")!!); assertEquals("https://www.bilibili.com/video/BV156xx6wEff", request.getHeader("Referer")); assertEquals("identity", request.getHeader("Accept-Encoding")); assertNull(request.getHeader("Cookie")); return range(request)
            } }
            server.start(); val file = File(temporary.root, "sample.part")
            val count = engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/video/BV156xx6wEff")
            assertEquals(bytes.size.toLong(), count); assertArrayEquals(bytes, file.readBytes()); assertEquals(4, ranges.size)
        }
    }
    @Test fun brokenBodyResumesAtWrittenOffsetInsteadOfZero() = runBlocking {
        MockWebServer().use { server ->
            val offsets = CopyOnWriteArrayList<Int>()
            server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                offsets.add(request.getHeader("Range")!!.substringAfter('=').substringBefore('-').toInt())
                return range(request).apply { if (offsets.size == 1) setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
            } }
            server.start(); val file = File(temporary.root, "broken.part")
            engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/")
            assertTrue(offsets[1] in 1 until 65536); assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun persistedPartialSurvivesNewDownloaderInstance() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = range(request) }
            server.start(); val file = File(temporary.root, "resume.part"); partial(file, 12345)
            engine().download(listOf(server.url("/video?new-signature").toString()), file, "sample", "https://www.bilibili.com/")
            val first = server.takeRequest(); assertEquals("bytes=12345-77880", first.getHeader("Range")); assertEquals("v1", first.getHeader("If-Range")); assertArrayEquals(bytes, file.readBytes())
            assertFalse(File(file.path + ".resume").readText().contains("signature"))
        }
    }
    @Test fun ignoredRangeRestartsFullResponseWithoutDuplicatingBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes))); server.start()
            val file = File(temporary.root, "ignored.part"); partial(file, 12345)
            engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/")
            assertArrayEquals(bytes, file.readBytes()); assertEquals(1, server.requestCount)
        }
    }
    @Test fun chunkedBodyReportsDownloadedBytesWithoutInventingPercentage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 8192).throttleBody(16384, 50, TimeUnit.MILLISECONDS)); server.start()
            val events = mutableListOf<TransferProgress>(); val file = File(temporary.root, "unknown.part")
            engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/", progress = { events.add(it) })
            assertTrue(events.any { it.bytes > 0 && it.total == -1L }); assertArrayEquals(bytes, file.readBytes()); assertEquals(bytes.size.toLong(), events.last().total)
        }
    }
    @Test fun failedMirrorSwitchesToSuppliedBackup() = runBlocking {
        MockWebServer().use { bad -> MockWebServer().use { good ->
            bad.enqueue(MockResponse().setResponseCode(503)); bad.start()
            good.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = range(request) }; good.start()
            val file = File(temporary.root, "mirror.part")
            engine().download(listOf(bad.url("/video").toString(), good.url("/video").toString()), file, "sample", "https://www.bilibili.com/")
            assertEquals(1, bad.requestCount); assertArrayEquals(bytes, file.readBytes())
        } }
    }
    @Test fun changedEtagDiscardsOldResourceBeforeAppendingNewBytes() = runBlocking {
        MockWebServer().use { server ->
            val changed = bytes.map { (it.toInt() xor 127).toByte() }.toByteArray()
            server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest) = range(request, changed, "v2") }; server.start()
            val file = File(temporary.root, "changed.part"); partial(file, 12345)
            engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/")
            assertArrayEquals(changed, file.readBytes()); assertEquals("bytes=0-65535", server.takeRequest().let { server.takeRequest() }.getHeader("Range"))
        }
    }
    @Test fun invalidRangeRetriesOnlyWithinLimitAndDoesNotAppend() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 99-100/${bytes.size}").setBody("xx")) }; server.start()
            val file = File(temporary.root, "invalid.part"); partial(file, 12345)
            try { engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/"); fail("Invalid range accepted") } catch (_: IOException) { }
            assertEquals(3, server.requestCount); assertEquals(12345, file.length().toInt())
        }
    }
    @Test fun platformLimitIsSurfacedWithoutAutomaticRepeatedRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(412)); server.start(); val file = File(temporary.root, "limited.part")
            try { engine().download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/"); fail("412 accepted") } catch (e: DownloadHttpException) { assertEquals(412, e.status) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationClosesBlockedReadPromptlyAndKeepsPartialForResume() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 5, TimeUnit.SECONDS)); server.start()
            val file = File(temporary.root, "pause.part")
            val job = launch(Dispatchers.IO) { engine(readMs = 15000).download(listOf(server.url("/video").toString()), file, "sample", "https://www.bilibili.com/") }
            withTimeout(3000) { while (file.length() == 0L) delay(10) }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(file.length() in 1 until bytes.size.toLong()); assertTrue(File(file.path + ".resume").isFile)
        }
    }
}
