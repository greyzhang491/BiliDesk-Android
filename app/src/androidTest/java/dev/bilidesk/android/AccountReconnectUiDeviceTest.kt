// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Intent
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.bilidesk.android.data.*
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

/** Toggles only the isolated test device's Wi-Fi; all platform responses are fake. */
@RunWith(AndroidJUnit4::class)
class AccountReconnectUiDeviceTest {
    @Test fun systemNetworkReconnectAutomaticallyRestoresTheVisibleAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val previousApi = Graph.api
        val previousCookies = Graph.cookies
        val jar = SecureCookies(context, "account_network_fixture")
        val online = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (!online.get()) throw IOException("Simulated offline transport")
            val body = """{"code":0,"data":{"isLogin":true,"mid":9,"uname":"重连测试账号","face":"","level_info":{"current_level":4},"vip":{"status":0}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun await(message: String, condition: () -> Boolean) {
            val until = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < until) { if (condition()) return; Thread.sleep(60) }
            fail(message)
        }
        try {
            device.executeShellCommand("svc data disable")
            device.executeShellCommand("svc wifi disable")
            jar.clear()
            jar.putLogin(mapOf("SESSDATA" to "fixture-session-only", "DedeUserID" to "9", "buvid3" to "fixture-device"))
            jar.rememberUser(jar.credentialId()!!, User(9, "重连测试账号", "", 4, false))
            Graph.cookies = jar
            Graph.api = BiliApi(jar, Graph.store, client)
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var vm: AppViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                await("Offline state must retain the saved account") { vm.account.value.status == AccountStatus.RETRY_PENDING }
                val library = device.wait(Until.findObject(By.text("我的")), 10000)
                assertNotNull(library); library.click()
                assertTrue(device.wait(Until.hasObject(By.text("重连测试账号")), 10000))
                repeat(2) {
                    online.set(false)
                    device.executeShellCommand("svc wifi disable")
                    await("Test Wi-Fi must actually disconnect") { manager.activeNetwork == null }
                    scenario.onActivity { vm.retryAccount() }
                    await("Temporary failure must stay retryable") { vm.account.value.status == AccountStatus.RETRY_PENDING }
                    assertNotNull(vm.user.value)
                    online.set(true)
                    device.executeShellCommand("svc wifi enable")
                    // No refresh call here: the real system callback must do the retry.
                    await("Network return must automatically verify saved credentials") { vm.account.value.status == AccountStatus.READY }
                    assertEquals("重连测试账号", vm.user.value!!.name)
                    assertEquals("library", vm.route.value.name)
                    assertFalse(device.hasObject(By.desc("哔哩哔哩登录二维码")))
                }
            }
        } finally {
            device.executeShellCommand("svc wifi disable")
            jar.clear()
            Graph.cookies = previousCookies
            Graph.api = previousApi
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
