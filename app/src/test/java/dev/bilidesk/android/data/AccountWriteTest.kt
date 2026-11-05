// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccountWriteTest {
    private val video = Video(bvid = "BV156xx6wEff", aid = 12345)
    private val token = "a".repeat(32)
    private fun writer(server: MockWebServer, session: Boolean = true, csrf: String = token, prepared: AtomicInteger = AtomicInteger()): AccountWriteClient {
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS)
            .cookieJar(object: CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("SESSDATA").value("test-session-only").domain(url.host).path("/").build())
            }).build()
        return AccountWriteClient(client, { csrf }, { session }, { prepared.incrementAndGet(); Unit }, server.url("").toString().trimEnd('/'))
    }
    private fun form(text: String) = text.split('&').associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8") }

    @Test fun coinSendsVideoRefererOriginCookieAndCsrfWithTheChosenCount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            val ready = AtomicInteger()
            writer(server, prepared = ready).coin(video, 1)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/x/web-interface/coin/add", request.path)
            assertEquals(video.url, request.getHeader("Referer"))
            assertEquals("https://www.bilibili.com", request.getHeader("Origin"))
            assertEquals("SESSDATA=test-session-only", request.getHeader("Cookie"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            assertEquals(mapOf("aid" to "12345", "multiply" to "1", "select_like" to "0", "csrf" to token, "csrf_token" to token), form(request.body.readUtf8()))
            assertEquals(1, ready.get())
        }
    }
    @Test fun expiredSessionAndMissingCsrfNeverSendAWrite() = runBlocking {
        MockWebServer().use { server ->
            val ready = AtomicInteger()
            for (client in listOf(writer(server, session = false, prepared = ready), writer(server, csrf = "", prepared = ready))) {
                try { client.coin(video, 1); fail("Expected login failure") }
                catch (e: ApiException) { assertEquals(-101, e.code); assertEquals(ActionFailure.LOGIN_REQUIRED, e.actionFailure) }
            }
            assertEquals(0, server.requestCount); assertEquals(0, ready.get())
        }
    }
    @Test fun invalidVideoOrCountCannotBecomeAnUnintendedOneCoinWrite() = runBlocking {
        MockWebServer().use { server ->
            val client = writer(server)
            for ((v, count) in listOf(video.copy(aid = 0) to 1, video to 0, video to 3, video.copy(kind = "live") to 1)) {
                try { client.coin(v, count); fail("Expected invalid arguments") }
                catch (_: IllegalArgumentException) {}
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun illegalAccessKeepsItsCodeWithoutClaimingTheAccountIsBannedOrVerified() {
        val failure = ActionErrors.from(JSONObject("""{"code":-401,"message":"非法访问","data":{}}"""))
        assertEquals(-401, failure.code); assertEquals(ActionFailure.REJECTED, failure.actionFailure)
        assertTrue(failure.message!!.contains("-401")); assertTrue(failure.message!!.contains("非法访问"))
        assertTrue(failure.message!!.contains("尚未确认")); assertFalse(failure.message!!.contains("封禁"))
    }
    @Test fun explicitCaptchaDecisionGetsOfficialVerificationInstructionsWithoutLeakingTokens() {
        val failure = ActionErrors.from(JSONObject("""{"code":-403,"message":"账号异常","data":{"v_voucher":"secret-challenge-fixture","ga_data":{"decisions":["verify_captcha_level2"]}}}"""))
        assertEquals(ActionFailure.VERIFICATION_REQUIRED, failure.actionFailure)
        assertTrue(failure.message!!.contains("官方")); assertFalse(failure.message!!.contains("secret-challenge-fixture"))
    }
    @Test fun otherBusinessErrorsArePreservedAndNeverRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":-104,"message":"硬币不足"}"""))
            try { writer(server).coin(video, 2); fail("Expected rejection") }
            catch (e: ApiException) { assertEquals(-104, e.code); assertTrue(e.message!!.contains("硬币不足")); assertEquals(ActionFailure.OTHER, e.actionFailure) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun connectionLostAfterSubmissionIsUnknownAndIsNotReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { writer(server).coin(video, 1); fail("Expected unknown result") }
            catch (e: WriteOutcomeUnknown) { assertTrue(e.message!!.contains("未能确认")) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun redirectDoesNotReplayCoinToAnotherAddress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirected-coin")))
            try { writer(server).coin(video, 1); fail("Expected redirect rejection") }
            catch (e: ApiException) { assertEquals(307, e.code) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun unreadableOrServerFailureResponseCannotReportSuccess() = runBlocking {
        MockWebServer().use { server ->
            val client = writer(server)
            for ((index, response) in listOf(MockResponse().setBody("<html>unexpected</html>"), MockResponse().setResponseCode(500).setBody("{}")).withIndex()) {
                server.enqueue(response)
                try { client.coin(video, 1); fail("Expected unknown result") }
                catch (e: WriteOutcomeUnknown) { assertEquals(if (index == 0) 200 else 500, e.httpStatus) }
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun simultaneousCoinTapsAreRejectedInsteadOfQueuedForASecondWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}""").setBodyDelay(500, TimeUnit.MILLISECONDS))
            val client = writer(server)
            val first = async(Dispatchers.Default) { client.coin(video, 1) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            try { client.coin(video, 1); fail("Expected in-flight guard") }
            catch (e: ApiException) { assertTrue(e.message!!.contains("正在处理")) }
            first.await()
            assertEquals(1, server.requestCount)
        }
    }
}
