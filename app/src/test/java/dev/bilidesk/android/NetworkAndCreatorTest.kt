// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.DashStreams
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkAndCreatorTest {
    private fun stream(q: Int = 80, codec: String = "avc1.640028") = Stream("https://cdn.example/a?x=1&y=2", listOf("https://backup.example/a"), q, codec, q * 20000L, "video/mp4", "0-999", "1000-1199", 1920, 1080, "30000/1001")
    @Test fun authorAvatarAndDateSurviveMetadataPersistence() {
        val video = Video.api(JSONObject("""{"bvid":"BV156xx6wEff","pubdate":123456,"owner":{"mid":42,"name":"UP","face":"//i.example/avatar.jpg"}}"""))
        assertEquals("https://i.example/avatar.jpg", video.authorAvatar)
        assertEquals(123456L, video.publishedAt)
        assertEquals(video, Video.from(video.json()))
        assertEquals(0L, Video.from(JSONObject()).publishedAt)
    }
    @Test fun manifestUsesExactServerIndexAndEscapesSignedUrls() {
        val xml = DashStreams.xml(listOf(stream()), 90500)!!
        assertTrue(xml.contains("indexRange=\"1000-1199\""))
        assertTrue(xml.contains("range=\"0-999\""))
        assertTrue(xml.contains("x=1&amp;y=2"))
        assertTrue(xml.contains("PT90.5S"))
    }
    @Test fun unavailableOrMalformedIndexKeepsProgressiveFallback() {
        for (bad in listOf("", "-1-5", "8-3", "0-999999999999999999999999", "0-1\"/>")) {
            assertNull(DashStreams.xml(listOf(stream().copy(indexRange = bad)), 90000))
        }
        assertNull(DashStreams.xml(listOf(stream()), 0))
    }
    @Test fun autoUsesPermittedQualityAndOneCodecFamilyWhileManualKeeps4k() {
        val all = listOf(stream(16), stream(32), stream(80), stream(120), stream(64, "hev1.1.6.L120"))
        val plan = PlayPlan(stream(), videoOptions = all, automatic = true)
        assertEquals(setOf(16, 32, 80), DashStreams.videos(plan).map { it.quality }.toSet())
        assertEquals(listOf(120), DashStreams.videos(plan.copy(video = stream(120), automatic = false)).map { it.quality })
    }
    @Test fun cachedFeedIsVisibleDuringFailureAndReplacedByFreshPage() = runBlocking {
        var offline = true
        val pager = VideoFeedPager({ if (offline) throw IOException("timeout"); FeedPage(listOf(Video(aid = 2))) }, restore = { listOf(Video(aid = 1)) })
        assertFalse(pager.load()); assertTrue(pager.state.value.cached)
        assertEquals(listOf(1L), pager.state.value.videos.map { it.aid })
        assertFalse(pager.state.value.error!!.contains("timeout"))
        offline = false; assertTrue(pager.load())
        assertEquals(listOf(2L), pager.state.value.videos.map { it.aid }); assertFalse(pager.state.value.cached)
        assertEquals(2, pager.state.value.nextPage)
    }
    @Test fun snapshotNamespacesExpireAndRemainBounded() = runBlocking {
        val dir = Files.createTempDirectory("feed-snapshots").toFile()
        try {
            val store = FeedSnapshots(dir)
            store.write("accountA:recommend", listOf(Video(aid = 1)))
            assertTrue(store.read("accountB:recommend").isEmpty())
            assertEquals(1L, store.read("accountA:recommend").single().aid)
            for (index in 0..15) store.write("key$index", (1L..80L).map { Video(aid = it) })
            assertTrue(dir.listFiles()!!.size <= 8)
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 1024 * 1024)
            dir.listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - 8 * 86400000L) }
            assertTrue(store.read("key15").isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun startingArchivePageAndNextPageAreIndependent() = runBlocking {
        val requested = mutableListOf<Int>()
        val pager = VideoFeedPager({ requested += it; FeedPage(listOf(Video(aid = it.toLong()))) }, listOf(Video(aid = 8)), initialPage = 8, initialNextPage = 9)
        pager.load(); pager.load(true)
        assertEquals(listOf(9, 8), requested)
        assertEquals(listOf(8L), pager.state.value.videos.map { it.aid })
    }
    @Test fun locatorFindsOldVideoWithoutLinearScanning() = runBlocking {
        var calls = 0
        val target = Video(aid = 12043, publishedAt = 100000 - 12043)
        val match = CreatorVideoLocator { page ->
            calls++; FeedPage(((page - 1) * 30 + 1..page * 30).map { Video(aid = it.toLong(), publishedAt = 100000L - it) }, total = 15000)
        }.find(target)
        assertEquals(402, match!!.page); assertTrue(match.content.videos.any { it.aid == target.aid }); assertTrue(calls <= 10)
    }
    @Test fun locatorDoesNotInventMatchFromDateOrScanUnknownHistory() = runBlocking {
        var calls = 0
        val locator = CreatorVideoLocator { calls++; FeedPage(listOf(Video(aid = 3, publishedAt = 100)), total = -1) }
        assertNull(locator.find(Video(aid = 4, publishedAt = 100))); assertEquals(1, calls)
    }
    @Test fun metadataTimeoutIsFriendlyAndNotRepeated() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"code\":0}").setBodyDelay(500, TimeUnit.MILLISECONDS))
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(80, TimeUnit.MILLISECONDS).build()
            try { readJson(client, Request.Builder().url(server.url("/read")).build()); fail("Expected timeout") }
            catch (failure: IOException) { assertTrue(failure.message!!.contains("网络")); assertFalse(failure.message!!.contains("timeout")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun readApiRejectionIsNeverRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            try { readJson(OkHttpClient(), Request.Builder().url(server.url("/read")).build()); fail("Expected rejection") }
            catch (failure: ApiException) { assertEquals(429, failure.code) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun ignoredOrWrongRangeIsRejectedBeforeReadingTheVideoPrefix() {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addNetworkInterceptor(dev.bilidesk.android.player.MediaRangeInterceptor()).build()
            for (reply in listOf(MockResponse().setBody("prefix"), MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-5/100").setBody("prefix"))) {
                server.enqueue(reply)
                try { client.newCall(Request.Builder().url(server.url("/video")).header("Range", "bytes=80-85").build()).execute().close(); fail("Unsafe seek accepted") }
                catch (failure: IOException) { assertTrue(failure.message!!.contains("进度")) }
            }
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 80-85/100").setBody("target"))
            client.newCall(Request.Builder().url(server.url("/video")).header("Range", "bytes=80-85").build()).execute().use { assertEquals("target", it.body!!.string()) }
        }
    }
    @Test fun followingProfilesParseRealListNamesAndDoNotInventCounts() {
        val user = CreatorProfile.parse(42, JSONObject("""{"mid":42,"uname":"UP Name","face":"//i.example/avatar.jpg","sign":"简介","vip":{"vipStatus":1}}"""))
        assertEquals("UP Name", user.name); assertEquals("https://i.example/avatar.jpg", user.avatar)
        assertTrue(user.vip); assertNull(user.fans)
    }

    @Test fun explicitLineChoiceUsesOnlyWholeServerUrlsAndAlsoAppliesToAuto() {
        val low = stream(16).copy(url = "https://cdn.example/low?signature=one", backups = listOf("https://backup.example/low?signature=two"))
        val plan = PlayPlan(stream(), videoOptions = listOf(stream(), low), automatic = true)
        val routed = DashStreams.preferLine(plan, plan.video.backups.single())
        assertEquals("https://backup.example/a", DashStreams.videos(routed).first { it.quality == 80 }.url)
        assertEquals("https://backup.example/low?signature=two", DashStreams.videos(routed).first { it.quality == 16 }.url)
        assertEquals(plan, DashStreams.preferLine(plan, "https://unlisted.example/a"))
    }

    @Test fun totalDeadlineReturnsWhileThePlatformDnsResolverIsStillBlocked() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false).dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); return listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }
        }).build()
        try {
            val started = System.nanoTime()
            val attempt = async(Dispatchers.IO) {
                try { readJson(client, Request.Builder().url("https://unresolved.invalid/read").build(), deadlineMs = 300); null }
                catch (failure: IOException) { failure }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val failure = withTimeout(1500) { attempt.await() }
            assertNotNull(failure); assertTrue(failure!!.message!!.contains("网络"))
            assertTrue((System.nanoTime()-started) < 2_000_000_000L)
            assertEquals("The DNS resolver should still be blocked", 1L, release.count)
        } finally { release.countDown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun hevcConfigurationFailureWrappedAsIoFallsBackToAvc() {
        val parser = IndexOutOfBoundsException("index (0) must be less than size (0)").apply {
            stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.HevcConfig", "parseImpl", "HevcConfig.java", 162))
        }
        val wrapped = IOException("Unexpected loader error", parser)
        assertTrue(dev.bilidesk.android.player.PlaybackFallbackPolicy.needsAvc("hev1.1.6.L120", 2000, wrapped))
        assertFalse(dev.bilidesk.android.player.PlaybackFallbackPolicy.needsAvc("avc1.640028", 2000, wrapped))
    }
    @Test fun transportAndAudioParserFailuresDoNotReloadVideoAsAnotherCodec() {
        val network = java.net.SocketTimeoutException("timeout")
        assertFalse(dev.bilidesk.android.player.PlaybackFallbackPolicy.needsAvc("hev1.1.6.L120", 2000, network))
        val audio = IllegalArgumentException().apply {
            stackTrace = arrayOf(StackTraceElement("androidx.media3.extractor.mp4.BoxParser", "parseAudioSampleEntry", "BoxParser.java", 1))
        }
        assertFalse(dev.bilidesk.android.player.PlaybackFallbackPolicy.needsAvc("hev1.1.6.L120", 2000, audio))
        assertTrue(dev.bilidesk.android.player.PlaybackFallbackPolicy.needsAvc("hev1.1.6.L120", 4001, network))
    }

}
