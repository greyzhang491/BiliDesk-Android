// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** The WebDAV provider receives authenticated ciphertext; the recovery key stays on the devices. */
object SyncCrypto {
    const val MAX_ENCRYPTED = 512 * 1024
    const val MAX_PLAIN = 8 * 1024 * 1024
    private val magic = "BDKS1".toByteArray(Charsets.US_ASCII)
    private val aad = "dev.bilidesk.android.music-sync.v1".toByteArray()
    fun newKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun key(text: String): ByteArray {
        require(text.matches(Regex("[A-Za-z0-9_-]{43}"))) { "同步密钥应为43位，请完整复制另一台设备上的密钥" }
        val key = Base64.getUrlDecoder().decode(text)
        require(key.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(key) == text) { "同步密钥格式不正确" }
        return key
    }
    fun vaultId(text: String) = MessageDigest.getInstance("SHA-256").digest(key(text)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun encrypt(plain: ByteArray, recoveryKey: String): ByteArray {
        require(plain.size <= MAX_PLAIN) { "同步数据过大，已保留本机内容" }
        val packed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(plain) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(recoveryKey), "AES")); cipher.updateAAD(aad)
        val result = magic + cipher.iv + cipher.doFinal(packed)
        require(result.size <= MAX_ENCRYPTED) { "同步数据超过512KB限制，已保留本机内容" }
        return result
    }
    fun decrypt(bytes: ByteArray, recoveryKey: String): ByteArray {
        require(bytes.size in (magic.size + 12 + 16)..MAX_ENCRYPTED && bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "云端数据格式不正确，本机内容未被替换" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(recoveryKey), "AES"), GCMParameterSpec(128, bytes.copyOfRange(5, 17))); cipher.updateAAD(aad)
        val packed = try { cipher.doFinal(bytes.copyOfRange(17, bytes.size)) }
        catch (_: java.security.GeneralSecurityException) { throw IllegalArgumentException("同步密钥不匹配或云端数据已损坏，本机内容未被替换") }
        return GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= MAX_PLAIN) { "云端数据超出大小限制" }; output.write(buffer, 0, n) }
            output.toByteArray()
        }
    }
    fun encode(document: SyncDocument, recoveryKey: String) = encrypt(document.json().toString().toByteArray(Charsets.UTF_8), recoveryKey)
    fun decode(bytes: ByteArray, recoveryKey: String) = SyncDocument.from(JSONObject(decrypt(bytes, recoveryKey).toString(Charsets.UTF_8)))
}
