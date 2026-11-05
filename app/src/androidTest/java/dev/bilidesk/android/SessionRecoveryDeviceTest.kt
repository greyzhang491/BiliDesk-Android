// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bilidesk.android.data.BiliApi
import dev.bilidesk.android.data.SecureCookies
import dev.bilidesk.android.ui.AppViewModel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.bilidesk.android.data.User
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue

/** Only a fake account and an intercepted transport; never reads a real session. */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryDeviceTest {
    @Test fun unreadableEncryptedDataIsPreservedAndRestoresWhenAvailableAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("session_crypto_fixture", 0)
        val original = SecureCookies(context, "session_crypto_fixture")
        try {
            original.clear()
            original.putLogin(mapOf("SESSDATA" to "fixture-session-only", "DedeUserID" to "9"))
            original.rememberUser(original.credentialId()!!, User(9, "重连测试账号", "", 4, false))
            val savedBlob = prefs.getString("blob", null)!!
            assertFalse(prefs.getString("profile", "")!!.contains("重连测试账号"))
            assertFalse(savedBlob.contains("fixture-session-only"))
            prefs.edit().putString("blob", "fixture-unreadable").commit()
            val pending = SecureCookies(context, "session_crypto_fixture")
            assertTrue(pending.restorationPending())
            assertEquals("fixture-unreadable", prefs.getString("blob", null))
            pending.putLogin(mapOf("buvid3" to "fixture-visitor"))
            pending.saveFromResponse("https://api.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("buvid3").value("fixture-visitor").domain("bilibili.com").build()))
            assertEquals("fixture-unreadable", prefs.getString("blob", null))
            prefs.edit().putString("blob", savedBlob).commit()
            assertEquals("fixture-session-only", pending.value("SESSDATA"))
            assertEquals("重连测试账号", pending.cachedUser()!!.name)
            assertFalse(pending.restorationPending())
        } finally { original.clear() }
    }

    @Test fun logoutRejectsLateAccountCookiesAndClearsTheEncryptedProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jar = SecureCookies(context, "session_logout_fixture")
        try {
            jar.clear()
            jar.putLogin(mapOf("SESSDATA" to "fixture-session-only", "DedeUserID" to "9"))
            jar.rememberUser(jar.credentialId()!!, User(9, "重连测试账号", "", 4, false))
            jar.clear()
            jar.saveFromResponse("https://api.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("SESSDATA").value("fixture-session-only").domain("bilibili.com").secure().build()))
            assertEquals("", jar.value("SESSDATA"))
            assertNull(jar.cachedUser())
            assertNull(SecureCookies(context, "session_logout_fixture").credentialId())
            assertNull(context.getSharedPreferences("session_logout_fixture", 0).getString("profile", null))
        } finally { jar.clear() }
    }

    /** Run seed, force-stop the target process, then run restore in another instrumentation. */
    @Test fun savedIdentityPersistsAcrossAnActualProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("sessionPhase")
        assumeTrue("Requires the explicit seed/restore process-restart sequence", phase in listOf("seed", "restore"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val jar = SecureCookies(context, "session_process_fixture")
        if (phase == "seed") {
            jar.clear()
            jar.putLogin(mapOf("SESSDATA" to "fixture-session-only", "DedeUserID" to "9", "buvid3" to "fixture-device"))
            jar.rememberUser(jar.credentialId()!!, User(9, "重连测试账号", "", 4, false))
            assertNotNull(jar.cachedUser())
            return
        }
        val previousApi = Graph.api
        val previousCookies = Graph.cookies
        val store = ViewModelStore()
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Simulated offline after process restart") }.build()
        try {
            val prefs = context.getSharedPreferences("session_process_fixture", 0)
            assertTrue("The session must be on disk before process exit", prefs.contains("blob"))
            assertTrue("The account profile must be on disk before process exit", prefs.contains("profile"))
            assertFalse("The encrypted session must be readable after restart", jar.restorationPending())
            assertEquals("重连测试账号", jar.cachedUser()!!.name)
            assertEquals("fixture-session-only", jar.value("SESSDATA"))
            Graph.cookies = jar
            Graph.api = BiliApi(jar, Graph.store, client)
            lateinit var vm: AppViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
                })[AppViewModel::class.java]
            }
            val until = System.currentTimeMillis() + 10000
            while (vm.account.value.checking && System.currentTimeMillis() < until) Thread.sleep(30)
            assertEquals("重连测试账号", vm.user.value!!.name)
            assertTrue(vm.account.value.canUseAccount)
            assertNotEquals("login", vm.route.value.name)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            jar.clear()
            Graph.cookies = previousCookies
            Graph.api = previousApi
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun savedSessionSurvivesAnOfflineColdStartAndRevalidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousCookies = Graph.cookies
        val previousApi = Graph.api
        val cookies = SecureCookies(context, "session_recovery_fixture")
        val online = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val stores = mutableListOf<ViewModelStore>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            if (!online.get()) throw IOException("Simulated offline transport")
            val body = """{"code":0,"data":{"isLogin":true,"mid":9,"uname":"重连测试账号","face":"","level_info":{"current_level":4},"vip":{"status":0}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun createVm(): AppViewModel {
            lateinit var vm: AppViewModel
            instrumentation.runOnMainSync {
                val store = ViewModelStore().also(stores::add)
                vm = ViewModelProvider(store, object: ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T: ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
                })[AppViewModel::class.java]
            }
            return vm
        }
        fun await(message: String, condition: () -> Boolean) {
            val until = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < until) {
                var ok = false
                instrumentation.runOnMainSync { ok = condition() }
                if (ok) return
                Thread.sleep(30)
            }
            fail(message)
        }
        try {
            cookies.clear()
            cookies.putLogin(mapOf("SESSDATA" to "fixture-session-only", "bili_jct" to "fixture-csrf-only", "DedeUserID" to "9", "buvid3" to "fixture-device"))
            Graph.cookies = cookies
            Graph.api = BiliApi(cookies, Graph.store, client)
            val first = createVm()
            await("Initial fake account must verify") { first.user.value?.name == "重连测试账号" }
            instrumentation.runOnMainSync { stores.first().clear() }
            online.set(false)
            val before = attempts.get()
            // Reconstruct the encrypted jar as happens after process death, while offline.
            val restored = SecureCookies(context, "session_recovery_fixture")
            assertEquals("fixture-session-only", restored.value("SESSDATA"))
            Graph.cookies = restored
            Graph.api = BiliApi(restored, Graph.store, client)
            val second = createVm()
            await("Offline validation should be attempted") { attempts.get() > before }
            Thread.sleep(250)
            assertNotNull("A temporary network failure must not turn a saved session into a login gate", second.user.value)
            assertEquals("重连测试账号", second.user.value!!.name)
            assertEquals("fixture-session-only", restored.value("SESSDATA"))
            online.set(true)
            instrumentation.runOnMainSync { second.refreshUser() }
            await("The saved session must revalidate without QR login") { second.user.value?.name == "重连测试账号" && attempts.get() > before + 1 }
            var allowed = false
            instrumentation.runOnMainSync { second.requireLogin { allowed = true } }
            assertTrue("Saved credentials must allow account navigation", allowed)
            assertNotEquals("login", second.route.value.name)
        } finally {
            instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
            cookies.clear()
            Graph.cookies = previousCookies
            Graph.api = previousApi
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
