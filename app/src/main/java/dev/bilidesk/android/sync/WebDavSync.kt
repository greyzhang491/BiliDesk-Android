// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import dev.bilidesk.android.data.BILI_UA
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DavEntry(val name: String, val size: Long, val etag: String? = null)
data class DavReply(val code: Int, val bytes: ByteArray, val retryAfterMs: Long = 0)
class SyncRemoteException(val status: Int, val retryAfterMs: Long = 0, message: String) : IOException(message)

/** Each installation is the sole writer of its own file. No shared last-writer-wins blob can erase another device's edits. */
class WebDavSync(private val username: String, password: String,
    private val root: HttpUrl = "https://dav.jianguoyun.com/dav/BiliDeskSync/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(7, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()) {
    private val authorization = Credentials.basic(username, password, Charsets.UTF_8)
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private var base = root
    init { require(root.encodedPath.endsWith('/') && root.username.isEmpty() && root.password.isEmpty() && root.query == null && root.fragment == null) }
    private suspend fun request(method: String, name: String = "", body: ByteArray? = null, atRoot: Boolean = false): DavReply {
        require(name.isEmpty() || name == "vault.bdks" || name.matches(Regex("device-[a-zA-Z0-9-]{1,64}\\.bdks")))
        val url = (if (atRoot) root else base).resolve(name)!!
        val request = Request.Builder().url(url).header("Authorization", authorization).header("User-Agent", BILI_UA)
            .method(method, body?.toRequestBody((if (method == "PROPFIND") "application/xml; charset=utf-8" else "application/octet-stream").toMediaType()))
        if (method == "PROPFIND") request.header("Depth", "1")
        val reply = withTimeout(16000) { http.newCall(request.build()).awaitReply(if (method == "PROPFIND") 128 * 1024 else SyncCrypto.MAX_ENCRYPTED) }
        when (reply.code) {
            301, 302, 303, 307, 308 -> throw IOException("同步服务要求跳转，为保护应用密码已停止连接")
            401, 403 -> throw SyncRemoteException(reply.code, message = "坚果云授权失败，请检查账号和第三方应用密码")
            429 -> throw SyncRemoteException(429, reply.retryAfterMs, "坚果云请求过于频繁，已暂停并安排自动重试")
            507 -> throw SyncRemoteException(507, message = "坚果云可用额度不足，本机修改已保留")
        }
        return reply
    }
    suspend fun prepare(key: String, create: Boolean) {
        // Nutstore's WebDAV ignores If-None-Match on PUT. Different keys therefore use different
        // subdirectories; same-key simultaneous initializations only write the same constant marker.
        base = root.resolve("vault-${SyncCrypto.vaultId(key)}/")!!
        if (create) {
            val folder = request("MKCOL", atRoot = true); checkCode(folder, setOf(201, 405))
            val vault = request("MKCOL"); checkCode(vault, setOf(201, 405))
        }
        var marker = request("GET", "vault.bdks")
        if (marker.code == 404 && create) {
            // Refuse initialization over pre-existing device files with a missing marker.
            require(list().isEmpty()) { "云端已有同步文件但缺少标记，请恢复原标记或联系维护者" }
            val created = request("PUT", "vault.bdks", SyncCrypto.encrypt("BiliDeskSync-v1".toByteArray(), key))
            checkCode(created, setOf(200, 201, 204))
            marker = request("GET", "vault.bdks")
        }
        if (marker.code == 404) throw IOException("还没有找到这份密钥对应的同步库，请核对第一台设备的同步密钥")
        checkCode(marker, setOf(200))
        require(SyncCrypto.decrypt(marker.bytes, key).toString(Charsets.UTF_8) == "BiliDeskSync-v1") { "同步库标记不正确" }
    }
    suspend fun list(): List<DavEntry> {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"
        val reply = request("PROPFIND", body = xml.toByteArray()); checkCode(reply, setOf(207))
        return parseListing(reply.bytes, base)
    }
    suspend fun read(name: String, key: String): SyncDocument {
        val reply = request("GET", name); checkCode(reply, setOf(200)); return SyncCrypto.decode(reply.bytes, key)
    }
    suspend fun write(device: String, document: SyncDocument, key: String) {
        val reply = request("PUT", "device-$device.bdks", SyncCrypto.encode(document, key)); checkCode(reply, setOf(200, 201, 204))
    }
    private fun checkCode(reply: DavReply, accepted: Set<Int>) { if (reply.code !in accepted) throw SyncRemoteException(reply.code, message = "云同步暂时失败（HTTP ${reply.code}），本机修改已保留") }

    companion object {
        internal fun parseListing(bytes: ByteArray, base: HttpUrl): List<DavEntry> {
            require(bytes.size <= 128 * 1024)
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
            catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("同步目录必须使用UTF-8编码") }
            require(!text.contains('\u0000')) { "同步目录必须使用UTF-8编码" }
            require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "同步目录返回了不安全的XML" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true; isExpandEntityReferences = false
                // Android's Harmony parser does not implement these SAX feature switches.
                // The strict UTF-8 reader, DTD/entity rejection and resolver below are mandatory.
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val doc = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are disabled") } }
                .parse(org.xml.sax.InputSource(StringReader(text)))
            val nodes = doc.getElementsByTagNameNS("DAV:", "response"); require(nodes.length <= 40)
            val result = mutableListOf<DavEntry>()
            for (index in 0 until nodes.length) {
                val item = nodes.item(index) as org.w3c.dom.Element
                val href = item.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val url = base.resolve(href) ?: continue
                val name = url.pathSegments.lastOrNull().orEmpty()
                if (!name.matches(Regex("device-[a-zA-Z0-9-]{1,64}\\.bdks"))) continue
                require(url == base.resolve(name)) { "同步目录包含越界文件地址" }
                val size = item.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: -1
                require(size in 0..SyncCrypto.MAX_ENCRYPTED.toLong()) { "同步文件大小不正确" }
                val etag = item.getElementsByTagNameNS("DAV:", "getetag").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
                result += DavEntry(name, size, etag)
            }
            require(result.size <= 16 && result.map { it.name }.distinct().size == result.size) { "同步设备数量超过16台，请先整理云端旧设备文件" }
            return result
        }
    }
}

private suspend fun Call.awaitReply(limit: Int): DavReply = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    val output = ByteArrayOutputStream()
                    // Rejection bodies are neither displayed nor retained (they may contain private paths).
                    if (it.isSuccessful) it.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "同步响应超过大小限制" }; output.write(buffer, 0, n) }
                    }
                    DavReply(it.code, output.toByteArray(), it.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 3600)?.times(1000) ?: 0)
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    })
}
