// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import dev.bilidesk.android.data.Video
import dev.bilidesk.android.player.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WebDavSyncTest {
    private val root = "/dav/BiliDeskSync/"
    private fun listing(files: Map<String, ByteArray>, directory: String = root) = "<d:multistatus xmlns:d=\"DAV:\">" + files.entries.joinToString("") { (name, bytes) ->
        "<d:response><d:href>$directory$name</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop></d:propstat></d:response>"
    } + "</d:multistatus>"
    private fun setup(server: MockWebServer, files: ConcurrentHashMap<String, ByteArray>, offline: () -> Boolean = { false }) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (offline()) return MockResponse().setResponseCode(503)
                assertEquals("Basic dWktZGVtbzpkZW1vLW9ubHk=", request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie")); assertTrue(request.path!!.startsWith(root))
                val name = request.path!!.removePrefix(root)
                return when (request.method) {
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody(listing(files.filterKeys { it.startsWith(name) && it != name + "vault.bdks" }.mapKeys { it.key.removePrefix(name) }, request.path!!))
                    "GET" -> files[name]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "PUT" -> { files[name] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
    }
    private fun client(server: MockWebServer) = WebDavSync("ui-demo", "demo-only", server.url(root))
    @Test fun twoDevicesExchangeEncryptedJournalsWithoutOverwritingEachOther() = runBlocking {
        MockWebServer().use { server ->
            val files = ConcurrentHashMap<String, ByteArray>(); setup(server, files); val key = SyncCrypto.newKey()
            val phone = client(server); val tablet = client(server)
            phone.prepare(key, true); tablet.prepare(key, false)
            val a = MusicTrack(Video(bvid = "BV156xx6wEff", title = "UI DEMO"), 11)
            val lib = MusicLibraryState(listOf(MusicPlaylist("first", "手机歌单", listOf(a))))
            val doc = SyncDocument().captureLibrary(MusicLibraryState(), lib, SyncClock("phone").let { it::next })
                .withCheckpoint(SyncCheckpoint(MusicQueueState(listOf(a), a.key), 42321, "手机", SyncStamp(100, 0, "phone")))
            phone.write("phone", doc, key)
            val remote = tablet.read(tablet.list().single().name, key)
            assertEquals(42321, remote.checkpoint!!.position)
            tablet.write("tablet", remote, key)
            assertEquals(setOf("device-phone.bdks", "device-tablet.bdks"), phone.list().map { it.name }.toSet())
            assertFalse(files.values.any { it.toString(Charsets.ISO_8859_1).contains("手机歌单") })
        }
    }
    @Test fun differentRecoveryKeysUseSeparateVaultsWhenConditionalPutsAreIgnored() = runBlocking {
        MockWebServer().use { server ->
            val files = ConcurrentHashMap<String, ByteArray>(); setup(server, files); val dav = client(server)
            val firstKey = SyncCrypto.newKey(); dav.prepare(firstKey, true)
            val path = "vault-${SyncCrypto.vaultId(firstKey)}/vault.bdks"; val original = files[path]!!.copyOf()
            val secondKey = SyncCrypto.newKey(); dav.prepare(secondKey, true)
            assertArrayEquals(original, files[path]); assertEquals(2, files.size)
            assertTrue(files.containsKey("vault-${SyncCrypto.vaultId(secondKey)}/vault.bdks"))
        }
    }
    @Test fun redirectDoesNotForwardAuthorizationToAnotherLocation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")))
            try { client(server).list(); fail("Redirect must be rejected") } catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun directoryListingRejectsExternalUrlsAndEntityExpansion() {
        MockWebServer().use { server ->
            val base = server.url(root)
            val external = listing(mapOf("device-a.bdks" to byteArrayOf(1))).replace("$root" + "device-a", "https://example.invalid/" + "device-a")
            assertThrows(IllegalArgumentException::class.java) { WebDavSync.parseListing(external.toByteArray(), base) }
            assertThrows(IllegalArgumentException::class.java) { WebDavSync.parseListing("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///not-real'>]><a>&x;</a>".toByteArray(), base) }
            assertThrows(IllegalArgumentException::class.java) { WebDavSync.parseListing("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///not-real'>]><a>&x;</a>".toByteArray(Charsets.UTF_16), base) }
            val entries = (1..17).associate { "device-$it.bdks" to byteArrayOf(1) }
            assertThrows(IllegalArgumentException::class.java) { WebDavSync.parseListing(listing(entries).toByteArray(), base) }
        }
    }
    @Test fun failedUploadIsNotAutomaticallyReplayedAndCanBeRetriedOnNextSync() = runBlocking {
        MockWebServer().use { server ->
            var offline = true; val files = ConcurrentHashMap<String, ByteArray>(); setup(server, files) { offline }
            val key = SyncCrypto.newKey(); val dav = client(server)
            try { dav.write("phone", SyncDocument(), key); fail("Expected offline response") } catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount); assertTrue(files.isEmpty())
            offline = false; dav.prepare(key, true); dav.write("phone", SyncDocument(), key)
            assertEquals(SyncDocument(), dav.read("device-phone.bdks", key))
        }
    }
    @Test fun providerErrorBodyIsNotShownOrStored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("PRIVATE-SERVER-DIAGNOSTIC"))
            try { client(server).list(); fail("Expected authorization error") } catch (e: java.io.IOException) { assertFalse(e.toString().contains("PRIVATE-SERVER-DIAGNOSTIC")); assertTrue(e.message!!.contains("授权失败")) }
        }
    }
    @Test fun listingKeepsOpaqueEntityTagsForChangeDetection() {
        MockWebServer().use { server ->
            val xml = listing(mapOf("device-a.bdks" to byteArrayOf(1))).replace("</d:getcontentlength>", "</d:getcontentlength><d:getetag>&quot;v7&quot;</d:getetag>")
            assertEquals("\"v7\"", WebDavSync.parseListing(xml.toByteArray(), server.url(root)).single().etag)
            assertNull(WebDavSync.parseListing(xml.replace("&quot;v7&quot;", "x".repeat(300)).toByteArray(), server.url(root)).single().etag)
        }
    }
    @Test fun rateLimitPreservesStatusAndRetryDelayWithoutServerBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1800").setBody("PRIVATE-DAV-PATH"))
            try { client(server).list(); fail("Expected rate limit") } catch (e: SyncRemoteException) {
                assertEquals(429, e.status); assertEquals(1800000L, e.retryAfterMs); assertFalse(e.toString().contains("PRIVATE-DAV-PATH"))
            }
            assertEquals(1, server.requestCount)
        }
    }
}
