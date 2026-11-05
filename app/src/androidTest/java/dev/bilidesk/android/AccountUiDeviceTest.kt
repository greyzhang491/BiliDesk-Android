// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms real account data appears in Compose screens; nothing is submitted. */
@RunWith(AndroidJUnit4::class)
class AccountUiDeviceTest {
    @Test fun favoritesWatchLaterAndBangumiRender() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountReads") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        Configurator.getInstance().waitForIdleTimeout = 300
        val folders = runBlocking { Graph.api.folders() }
        val later = runBlocking { Graph.api.later().videos }
        val bangumi = runBlocking { Graph.api.bangumi(1, mine = true).videos }
        fun click(text: String) {
            val node = device.wait(Until.findObject(By.text(text)), 15000)
            assertNotNull("Navigation item missing", node); node.click()
        }
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)).use {
            click("我的")
            click("收藏")
            val folderText = folders.firstOrNull()?.title ?: "还没有收藏夹"
            assertTrue("Favorite folder data did not render", device.wait(Until.hasObject(By.text(folderText)), 20000))
            device.pressBack()
            click("稍后再看")
            val laterText = later.firstOrNull()?.title ?: "这里还没有内容"
            assertTrue("Watch-later data did not render", device.wait(Until.hasObject(By.text(laterText)), 20000))
            device.pressBack()
            click("我的追番")
            val bangumiText = bangumi.firstOrNull()?.title ?: "这里还没有内容"
            assertTrue("Followed bangumi did not render", device.wait(Until.hasObject(By.text(bangumiText)), 20000))
        }
    }
}
