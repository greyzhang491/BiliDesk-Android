// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationAndFloatingTest {
    private val video = Video(bvid = "BV156xx6wEff", aid = 100001, mid = 80)
    @Test fun aContentPreferenceMatchesTheSameVideoAcrossIdentifiers() {
        val rule = RecommendationRule(100001, video.bvid, 80, "", "", RecommendationReason.CONTENT, 1)
        assertTrue(rule.matches(Video(aid = 100001))); assertTrue(rule.matches(Video(bvid = video.bvid)))
        assertFalse(rule.matches(Video(aid = 999, mid = 80))); assertFalse(rule.matches(video.copy(kind = "live")))
        assertEquals(rule, RecommendationRule.from(rule.json()))
    }
    @Test fun aCreatorPreferenceDoesNotHideOtherCreatorsOrKinds() {
        val rule = RecommendationRule(100001, video.bvid, 80, "", "", RecommendationReason.CREATOR, 1)
        assertTrue(rule.matches(Video(aid = 999, mid = 80))); assertFalse(rule.matches(video.copy(mid = 81)))
        assertFalse(rule.matches(video.copy(kind = "season")))
    }
    @Test fun recommendationTrackingContextIsNeverPersistedWithVideoMetadata() {
        val source = video.copy(recommendation = RecommendationContext("PRIVATE-TRACE-DEMO", "9"))
        assertFalse(source.json().toString().contains("PRIVATE-TRACE-DEMO")); assertNull(Video.from(source.json()).recommendation)
    }
    @Test fun allFourCornersGrowAndRetainTheirOppositeAnchorAwayFromTheEdges() {
        val rect = FloatingVideoRect(300f, 300f, 200f, 112.5f)
        val limits = FloatingVideoLimits(1200f, 1800f, 16f / 9, 112f, 900f, 12f)
        FloatingVideoCorner.entries.forEach { corner ->
            val changed = FloatingVideoGeometry.resize(rect, corner, if (corner.left) -80f else 80f, if (corner.top) -45f else 45f, limits)
            assertTrue(changed.width > rect.width); assertEquals(16f / 9, changed.width / changed.height, .001f)
            assertEquals(if (corner.left) rect.left + rect.width else rect.left, if (corner.left) changed.left + changed.width else changed.left, .01f)
            assertEquals(if (corner.top) rect.top + rect.height else rect.top, if (corner.top) changed.top + changed.height else changed.top, .01f)
        }
    }
    @Test fun resizingIsClampedWithinBothPhoneAndTabletWindows() {
        for ((width, height, ratio) in listOf(Triple(390f, 800f, 16f / 9), Triple(1280f, 800f, 9f / 16), Triple(250f, 150f, 3f))) {
            val limits = FloatingVideoLimits(width, height, ratio, 112f, 1000f, 6f)
            FloatingVideoCorner.entries.forEach { corner ->
                val changed = FloatingVideoGeometry.resize(FloatingVideoRect(20f, 20f, 160f, 160f / ratio), corner, 10000f, 10000f, limits)
                assertTrue(changed.left >= 6 && changed.top >= 6); assertTrue(changed.left + changed.width <= width - 5.9f)
                assertTrue(changed.top + changed.height <= height - 5.9f); assertEquals(ratio, changed.width / changed.height, .001f)
            }
        }
    }
}
