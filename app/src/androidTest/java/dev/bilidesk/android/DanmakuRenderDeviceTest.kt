// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.DanmakuView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DanmakuRenderDeviceTest {
    private data class Pixels(val bounds: Rect, val alpha: Long, val red: Int)
    @Test fun productionRendererActuallyChangesAreaFontOpacityColorSpeedAndFilters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = DanmakuView(instrumentation.targetContext)
            view.layout(0, 0, 800, 600)
            fun draw(items: List<Danmaku>, s: Settings, time: Long = 1000): Pixels {
                view.bind(items, s, { time }, { false })
                val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                var left = 800; var top = 600; var right = -1; var bottom = -1; var alpha = 0L; var red = 0
                for (y in 0 until 600) for (x in 0 until 800) {
                    val pixel = bitmap.getPixel(x, y); val a = Color.alpha(pixel)
                    if (a > 0) { left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y); alpha += a; if (Color.red(pixel) > Color.green(pixel) + 20) red++ }
                }
                bitmap.recycle()
                return Pixels(if (right < 0) Rect() else Rect(left, top, right + 1, bottom + 1), alpha, red)
            }
            val fixed = listOf(Danmaku(0, "实际字号验证", 5, 0xff6688))
            val s = Settings(opacity = 1f, deduplicate = false, fontSize = 12f)
            val small = draw(fixed, s); val big = draw(fixed, s.copy(fontSize = 30f))
            assertTrue("Changing font did not enlarge rendered glyphs", big.bounds.height() > small.bounds.height() * 1.8 && big.bounds.width() > small.bounds.width() * 1.8)
            assertTrue("Opacity did not change pixel alpha", draw(fixed, s.copy(opacity = .25f)).alpha < small.alpha * .5)
            assertTrue(draw(fixed, s).red > 0); assertEquals(0, draw(fixed, s.copy(colored = false)).red)
            val bottom = listOf(Danmaku(0, "底部也应遵守区域", 4))
            val quarter = draw(bottom, s.copy(area = .25f)); val full = draw(bottom, s.copy(area = 1f))
            assertTrue("Bottom comments escaped the selected top quarter", quarter.bounds.bottom in 1..150)
            assertTrue("Area setting did not reposition bottom comments", full.bounds.top > 400)
            val moving = listOf(Danmaku(0, "速度", 1))
            assertTrue("Speed did not change media-clock position", draw(moving, s.copy(dmSpeed = 2f), 3000).bounds.centerX() < draw(moving, s.copy(dmSpeed = .5f), 3000).bounds.centerX() - 100)
            assertEquals(0L, draw(fixed, s.copy(top = false)).alpha)
            assertEquals(0L, draw(fixed, s.copy(blacklist = "字号")).alpha)
            assertEquals(0L, draw(moving, s.copy(scroll = false)).alpha)
            assertEquals(0L, draw(fixed, s.copy(danmaku = false)).alpha)
            view.release()
        }
    }
}
