// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Settings(
    val theme: Int = 0, val quality: Int = 80, val preferHevc: Boolean = true,
    val danmaku: Boolean = true, val colored: Boolean = true, val scroll: Boolean = true,
    val top: Boolean = true, val bottom: Boolean = true, val opacity: Float = .85f,
    val area: Float = .5f, val fontSize: Float = 18f, val dmSpeed: Float = 1f,
    val blacklist: String = "", val deduplicate: Boolean = true,
    val background: Boolean = false, val shortCache: Boolean = false,
    val sponsor: Boolean = false, val sponsorCategories: String = "sponsor",
    val cdnMode: Int = 0, val playbackSpeed: Float = 1f,
    val holdSpeedEnabled: Boolean = true, val holdSpeed: Float = 2f,
    val autoCacheMaxMb: Int = 256, val autoCacheDays: Int = 7, val autoCacheWifiOnly: Boolean = true,
    val feedDensity: Int = 1, val feedColumns: Int = 0, val shortsAutoNext: Boolean = false
)

class Store(context: Context) {
    private val settingsLock = Any()
    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).apply {
        if (getInt("cachePolicyVersion", 0) < 1) edit().putBoolean("shortCache", false).putInt("cachePolicyVersion", 1).apply()
    }
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = context.filesDir
    private val _settings = MutableStateFlow(readSettings())
    val settings = _settings.asStateFlow()
    private val _history = MutableStateFlow(readList("history.json").objects().mapNotNull { j -> runCatching { HistoryEntry(Video.from(j.obj("video")), j.getLong("cid"), j.getLong("position"), j.getLong("at")) }.getOrNull() })
    val history = _history.asStateFlow()
    private val _caches = MutableStateFlow(readList("caches.json").objects().mapNotNull { j -> runCatching { CacheEntry(j.getString("key"), Video.from(j.obj("video")), j.getLong("cid"), j.optString("pageTitle"), j.getString("path"), j.getLong("bytes"), j.optBoolean("automatic", false), j.optLong("accessedAt", File(files, j.getString("path")).lastModified()), j.optString("type", "video"), j.optInt("quality")) }.getOrNull() }.filter { File(files, it.path).exists() })
    val caches = _caches.asStateFlow()
    val searchHistory = MutableStateFlow(prefs.getString("searches", "")!!.split('\n').filter { it.isNotBlank() })
    @Volatile private var pinnedCache: String? = null
    init { maintenance.launch { pruneAutomaticCache() } }
    val agreed get() = prefs.getBoolean("agreed", false)
    fun agree() { prefs.edit().putBoolean("agreed", true).apply() }
    private fun readSettings() = Settings(
        theme = prefs.getInt("theme", 0), quality = prefs.getInt("quality", 80), preferHevc = prefs.getBoolean("hevc", true),
        danmaku = prefs.getBoolean("danmaku", true), colored = prefs.getBoolean("colored", true), scroll = prefs.getBoolean("scroll", true),
        top = prefs.getBoolean("top", true), bottom = prefs.getBoolean("bottom", true), opacity = prefs.getFloat("opacity", .85f),
        area = prefs.getFloat("area", .5f), fontSize = prefs.getFloat("fontSize", 18f), dmSpeed = prefs.getFloat("dmSpeed", 1f),
        blacklist = prefs.getString("blacklist", "").orEmpty(), deduplicate = prefs.getBoolean("deduplicate", true),
        background = prefs.getBoolean("background", false), shortCache = prefs.getBoolean("shortCache", false),
        sponsor = prefs.getBoolean("sponsor", false), sponsorCategories = prefs.getString("categories", "sponsor").orEmpty(),
        cdnMode = prefs.getInt("cdnMode", 0), playbackSpeed = prefs.getFloat("playbackSpeed", 1f),
        holdSpeedEnabled = prefs.getBoolean("holdSpeedEnabled", true), holdSpeed = prefs.getFloat("holdSpeed", 2f),
        autoCacheMaxMb = prefs.getInt("autoCacheMaxMb", 256).coerceIn(128, 1024), autoCacheDays = prefs.getInt("autoCacheDays", 7).coerceIn(1, 30), autoCacheWifiOnly = prefs.getBoolean("autoCacheWifiOnly", true),
        feedDensity = prefs.getInt("feedDensity", 1).coerceIn(0, 2), feedColumns = prefs.getInt("feedColumns", 0).coerceIn(0, 8), shortsAutoNext = prefs.getBoolean("shortsAutoNext", false)
    )
    fun change(transform: (Settings) -> Settings): Unit = synchronized(settingsLock) { update(transform(_settings.value)) }
    fun update(s: Settings): Unit = synchronized(settingsLock) {
        val previous = _settings.value
        _settings.value = s
        prefs.edit().putInt("theme", s.theme).putInt("quality", s.quality).putBoolean("hevc", s.preferHevc)
            .putBoolean("danmaku", s.danmaku).putBoolean("colored", s.colored).putBoolean("scroll", s.scroll)
            .putBoolean("top", s.top).putBoolean("bottom", s.bottom).putFloat("opacity", s.opacity)
            .putFloat("area", s.area).putFloat("fontSize", s.fontSize).putFloat("dmSpeed", s.dmSpeed)
            .putString("blacklist", s.blacklist).putBoolean("deduplicate", s.deduplicate).putBoolean("background", s.background)
            .putBoolean("shortCache", s.shortCache).putBoolean("sponsor", s.sponsor).putString("categories", s.sponsorCategories)
            .putInt("cdnMode", s.cdnMode).putFloat("playbackSpeed", s.playbackSpeed)
            .putBoolean("holdSpeedEnabled", s.holdSpeedEnabled).putFloat("holdSpeed", s.holdSpeed)
            .putInt("autoCacheMaxMb", s.autoCacheMaxMb).putInt("autoCacheDays", s.autoCacheDays).putBoolean("autoCacheWifiOnly", s.autoCacheWifiOnly)
            .putInt("feedDensity", s.feedDensity).putInt("feedColumns", s.feedColumns).putBoolean("shortsAutoNext", s.shortsAutoNext).apply()
        if (previous.autoCacheMaxMb != s.autoCacheMaxMb || previous.autoCacheDays != s.autoCacheDays) maintenance.launch { pruneAutomaticCache() }
    }
    fun addSearch(text: String) {
        searchHistory.value = (listOf(text.trim()) + searchHistory.value).distinct().filter { it.isNotBlank() }.take(12)
        prefs.edit().putString("searches", searchHistory.value.joinToString("\n")).apply()
    }
    @Synchronized fun record(video: Video, cid: Long, positionMs: Long) {
        _history.value = (listOf(HistoryEntry(video, cid, positionMs.coerceAtLeast(0), System.currentTimeMillis())) + _history.value.filter { it.video.key != video.key }).take(200)
        writeList("history.json", JSONArray(_history.value.map { JSONObject().put("video", it.video.json()).put("cid", it.cid).put("position", it.positionMs).put("at", it.at) }))
    }
    @Synchronized fun addCache(entry: CacheEntry) {
        _caches.value = listOf(entry) + _caches.value.filter { it.key != entry.key }
        persistCaches()
    }
    @Synchronized fun deleteCache(entry: CacheEntry) {
        if (entry.key == pinnedCache) return
        val candidate = File(files, entry.path).canonicalFile
        if (!candidate.path.startsWith(File(files, "media").canonicalPath + File.separator)) return
        if (candidate.exists() && !candidate.delete()) return
        _caches.value = _caches.value.filter { it.key != entry.key }
        persistCaches()
    }
    fun cacheFile(entry: CacheEntry) = File(files, entry.path)
    @Synchronized fun pinCache(key: String?) { pinnedCache = key; if (key == null) maintenance.launch { pruneAutomaticCache() } }
    @Synchronized fun acquireCache(videoKey: String, cid: Long, forMusic: Boolean = false): CacheEntry? {
        val entry = _caches.value.filter { it.video.key == videoKey && it.cid == cid && (forMusic || it.type != "audio") && cacheFile(it).exists() }.sortedWith(compareBy<CacheEntry> { if (forMusic && it.type == "audio") 0 else 1 }.thenByDescending { it.quality }).firstOrNull()
        pinnedCache = entry?.key
        return entry
    }
    @Synchronized fun touchCache(key: String) {
        _caches.value = _caches.value.map { if (it.key == key) it.copy(accessedAt = System.currentTimeMillis()) else it }
        persistCaches()
    }
    @Synchronized fun pruneAutomaticCache(reserveBytes: Long = 0) {
        val s = _settings.value
        val toDelete = CachePolicy.evictions(_caches.value.map { CacheBudgetItem(it.key, it.bytes, it.automatic, it.accessedAt) }, s.autoCacheMaxMb * CachePolicy.MIB, s.autoCacheDays * 86400000L, System.currentTimeMillis(), reserveBytes, setOfNotNull(pinnedCache))
        _caches.value.filter { it.key in toDelete }.forEach(::deleteCache)
    }
    @Synchronized fun clearAutomaticCache() { _caches.value.filter { it.automatic && it.key != pinnedCache }.forEach(::deleteCache) }
    private fun persistCaches() = writeList("caches.json", JSONArray(_caches.value.map { JSONObject().put("key", it.key).put("video", it.video.json()).put("cid", it.cid).put("pageTitle", it.pageTitle).put("path", it.path).put("bytes", it.bytes).put("automatic", it.automatic).put("accessedAt", it.accessedAt).put("type", it.type).put("quality", it.quality) }))
    private fun readList(name: String): JSONArray = runCatching { JSONArray(AtomicFile(File(files, name)).openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONArray())
    private fun writeList(name: String, data: JSONArray) {
        val atomic = AtomicFile(File(files, name)); val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray()); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}

/** Login cookies never leave bilibili.com. The encrypted preference cannot be restored via backup. */
class SecureCookies(context: Context, preferenceName: String = "encrypted_session"): CookieJar {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val cookies = mutableListOf<Cookie>()
    private val alias = "BiliDesk.Session.v1"
    init {
        runCatching {
            val parts = prefs.getString("blob", null)?.split(':') ?: return@runCatching
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            JSONArray(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))).objects().forEach { j ->
                val b = Cookie.Builder().name(j.getString("name")).value(j.getString("value")).path(j.getString("path")).expiresAt(j.getLong("expires"))
                if (j.optBoolean("hostOnly")) b.hostOnlyDomain(j.getString("domain")) else b.domain(j.getString("domain"))
                if (j.optBoolean("secure")) b.secure()
                if (j.optBoolean("httpOnly")) b.httpOnly()
                val c = b.build()
                if (allowed(c.domain) && c.expiresAt > System.currentTimeMillis()) cookies.add(c)
            }
        }.onFailure { prefs.edit().clear().apply() }
    }
    private fun allowed(host: String) = host == "bilibili.com" || host.endsWith(".bilibili.com")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!allowed(url.host)) return
        for (cookie in cookies.filter { allowed(it.domain) }) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        persist()
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = if (allowed(url.host)) cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
    @Synchronized fun value(name: String): String = cookies.firstOrNull { it.name == name && it.expiresAt > System.currentTimeMillis() }?.value.orEmpty()
    @Synchronized fun putLogin(values: Map<String, String>) {
        for ((name, value) in values) {
            if (name !in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4")) continue
            cookies.removeAll { it.name == name }
            cookies.add(Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 180L * 86400000).build())
        }
        persist()
    }
    @Synchronized fun clear() { cookies.clear(); prefs.edit().clear().apply() }
    private fun persist() {
        val data = JSONArray(cookies.map { JSONObject().put("name", it.name).put("value", it.value).put("domain", it.domain).put("path", it.path).put("expires", it.expiresAt).put("hostOnly", it.hostOnly).put("secure", it.secure).put("httpOnly", it.httpOnly) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(data.toString().toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString("blob", blob).apply()
    }
}
