// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import org.junit.Assert.*
import org.junit.Test

class InteractionPolicyTest {
    @Test fun tabletLandscapeAllocatesBothPanesWithoutLosingHeight() {
        val layout = PlayerLayoutPolicy.calculate(1280f, 752f, false)
        assertEquals(PlayerLayoutMode.SIDE_BY_SIDE, layout.mode)
        assertEquals(1280f, layout.videoWidth + layout.detailsWidth, .01f)
        assertEquals(752f, layout.videoHeight, .01f)
        assertEquals(752f, layout.detailsHeight, .01f)
    }
    @Test fun narrowAndSplitWindowsKeepDetailsAccessible() {
        listOf(393f to 820f, 800f to 1200f, 540f to 700f, 780f to 360f).forEach { (w, h) ->
            val layout = PlayerLayoutPolicy.calculate(w, h, false)
            assertEquals(PlayerLayoutMode.STACKED, layout.mode)
            assertTrue(layout.detailsHeight >= h * .4f)
            assertEquals(h, layout.videoHeight + layout.detailsHeight, .01f)
        }
    }
    @Test fun fullscreenAlwaysUsesTheAvailableWindow() {
        listOf(1280f to 800f, 393f to 820f, 600f to 360f).forEach { (w, h) ->
            val layout = PlayerLayoutPolicy.calculate(w, h, true)
            assertEquals(w, layout.videoWidth, .01f); assertEquals(h, layout.videoHeight, .01f)
            assertEquals(0f, layout.detailsWidth, .01f)
        }
    }
    @Test fun navigationUsesActualWindowDimensions() {
        assertTrue(AppWindow(1280f, 800f).useRail)
        assertTrue(AppWindow(780f, 360f).useRail)
        assertFalse(AppWindow(540f, 900f).useRail)
        assertEquals(220f, AppWindow(1280f, 800f).cardMinWidth, .01f)
    }
    @Test fun longHoldRestoresThePreviousNonDefaultSpeedOnce() {
        val hold = HoldSpeedState()
        assertEquals(3f, hold.begin(1.25f, 3f, true))
        assertNull(hold.begin(3f, 4f, true))
        assertEquals(1.25f, hold.end())
        assertNull(hold.end()); assertFalse(hold.active)
    }
    @Test fun disabledOrInvalidHoldDoesNotChangePlayback() {
        val hold = HoldSpeedState()
        assertNull(hold.begin(1f, 3f, false))
        assertNull(hold.begin(1f, Float.NaN, true))
        assertNull(hold.begin(1f, 8f, true))
        assertNull(hold.end()); assertFalse(hold.active)
    }
    @Test fun cacheEvictsOldestAutomaticEntriesAndKeepsManualDownloads() {
        val items = listOf(CacheBudgetItem("manual", 999, false, 0), CacheBudgetItem("old", 60, true, 100), CacheBudgetItem("new", 60, true, 200))
        assertEquals(setOf("old"), CachePolicy.evictions(items, 100, 1000, 300))
    }
    @Test fun expiryIsIndependentOfTheSizeBudget() {
        val items = listOf(CacheBudgetItem("expired", 1, true, 100), CacheBudgetItem("recent", 1, true, 901), CacheBudgetItem("manual", 1, false, 0))
        assertEquals(setOf("expired"), CachePolicy.evictions(items, 10000, 500, 1000))
    }
    @Test fun currentlyPlayingFileIsProtectedAndReservationFreesRoom() {
        val items = listOf(CacheBudgetItem("playing", 70, true, 0), CacheBudgetItem("other", 20, true, 900))
        assertEquals(setOf("other"), CachePolicy.evictions(items, 100, 500, 1000, 30, setOf("playing")))
        assertEquals(emptySet<String>(), CachePolicy.evictions(items.take(1), 10, 1, 1000, protectedKeys = setOf("playing")))
    }
    @Test fun normalPlaybackDoesNotOptIntoAutomaticDownloads() {
        val defaults = Settings()
        assertFalse(defaults.shortCache)
        assertTrue(defaults.autoCacheWifiOnly)
        assertEquals(256, defaults.autoCacheMaxMb)
        assertEquals(7, defaults.autoCacheDays)
    }
}
