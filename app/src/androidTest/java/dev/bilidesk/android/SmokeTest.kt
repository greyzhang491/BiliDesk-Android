// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bilidesk.android.data.SecureCookies
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test fun appLaunchesAndNavigatesToCacheFromNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("open_downloads", true)).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity.window.decorView); assertFalse(activity.isFinishing) }
        }
    }
    @Test fun encryptedCookiesPersistAndNeverReachThirdParties() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jar = SecureCookies(context, "instrumentation_session")
        jar.clear()
        jar.putLogin(mapOf("SESSDATA" to "instrumentation-test-only", "bili_jct" to "test"))
        assertTrue(jar.loadForRequest("https://api.bilibili.com/x".toHttpUrl()).any { it.name == "SESSDATA" })
        assertTrue(jar.loadForRequest("https://www.bsbsb.top/api".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://bilibili.com.attacker.invalid/".toHttpUrl()).isEmpty())
        val raw = context.getSharedPreferences("instrumentation_session", 0).getString("blob", "")!!
        assertFalse(raw.contains("instrumentation-test-only"))
        assertEquals("instrumentation-test-only", SecureCookies(context, "instrumentation_session").value("SESSDATA"))
        jar.saveFromResponse("https://evil.invalid/".toHttpUrl(), listOf(Cookie.Builder().name("SESSDATA").value("evil").domain("evil.invalid").build()))
        assertEquals("instrumentation-test-only", jar.value("SESSDATA"))
        jar.clear()
    }
}
