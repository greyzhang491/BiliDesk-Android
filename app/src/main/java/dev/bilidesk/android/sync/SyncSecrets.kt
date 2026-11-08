// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SyncCredentials(val username: String, val password: String, val recoveryKey: String, val deviceName: String) {
    override fun toString() = "SyncCredentials(redacted)"
}

/** Separate from the Bilibili Cookie jar. Never include these values in logs or diagnostics. */
internal class SyncSecrets(context: Context, preferenceName: String) {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val alias = "BiliDesk.MusicSync.v1"
    val exists get() = prefs.contains("blob")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): SyncCredentials? {
        val blob = prefs.getString("blob", null) ?: return null
        val bytes = Base64.getDecoder().decode(blob); require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val j = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        return SyncCredentials(j.getString("username"), j.getString("password"), j.getString("key").also { SyncCrypto.key(it) }, j.getString("deviceName"))
    }
    fun save(config: SyncCredentials) {
        val j = JSONObject().put("username", config.username).put("password", config.password).put("key", config.recoveryKey).put("deviceName", config.deviceName)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(j.toString().toByteArray(Charsets.UTF_8)))
        check(prefs.edit().putString("blob", blob).commit()) { "无法保存同步授权，本机内容未被删除" }
    }
    fun clear() { check(prefs.edit().remove("blob").commit()) { "无法清除本机同步授权，请重试" } }
}
