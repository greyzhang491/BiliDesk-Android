// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FeedAndGestureTest {
    private fun videos(vararg ids: Long) = ids.map { Video(aid = it, title = "video $it") }
    @Test fun aFilteredRecommendationPageCanAdvanceToVisibleContentWithoutAnUnboundedLoop() = runBlocking {
        val calls = mutableListOf<Int>()
        val pager = VideoFeedPager({ page -> calls += page; FeedPage(videos(page.toLong())) }, accept = { it.aid >= 3 })
        pager.load(); assertEquals(listOf(3L), pager.state.value.videos.map { it.aid }); assertEquals(listOf(1, 2, 3), calls)
        val hidden = VideoFeedPager({ page -> FeedPage(videos(page.toLong())) }, accept = { false })
        hidden.load(); assertFalse(hidden.state.value.more); assertEquals(4, hidden.state.value.nextPage)
    }
    @Test fun favoriteRemovalRevisitsTheShiftedPageBoundaryWithoutLosingVideos() = runBlocking {
        val server = (1L..65L).map { Video(aid = it) }.toMutableList(); val pages = mutableListOf<Int>()
        val pager = VideoFeedPager({ p -> pages += p; FeedPage(server.drop((p - 1) * 20).take(20), more = p * 20 < server.size) })
        pager.load(); pager.load()
        val revision = pager.state.value.revision
        for (id in listOf(2L, 21L, 35L)) { server.removeAll { it.aid == id }; pager.removeFavorite("av$id") }
        assertEquals(revision, pager.state.value.revision)
        while (pager.state.value.more) pager.load()
        assertEquals(server.map { it.aid }, pager.state.value.videos.map { it.aid }); assertEquals(listOf(1, 2, 2, 3, 4), pages)
    }
    @Test fun removingFromAFullyLoadedFavoriteListKeepsItComplete() = runBlocking {
        val pager = VideoFeedPager({ FeedPage(videos(1, 2), more = false) }); pager.load(); pager.removeFavorite("av1")
        assertEquals(listOf(2L), pager.state.value.videos.map { it.aid }); assertFalse(pager.state.value.more)
    }
    @Test fun paginationAppendsUniqueItemsAndStopsOnRepeatedPage() = runBlocking {
        val pages = mutableListOf<Int>()
        val pager = VideoFeedPager({ p -> pages += p; FeedPage(if (p == 1) videos(1, 2) else videos(2, 3)) })
        pager.load(); pager.load(); pager.load(); pager.load()
        assertEquals(listOf(1L, 2L, 3L), pager.state.value.videos.map { it.aid })
        assertEquals(listOf(1, 2, 3), pages); assertFalse(pager.state.value.more)
    }
    @Test fun successfulRefreshReplacesContentAndResetsPagination() = runBlocking {
        var batch = 0; val pages = mutableListOf<Int>()
        val pager = VideoFeedPager({ p -> pages += p; FeedPage(videos((++batch).toLong())) })
        pager.load(); pager.load(); pager.load(true); pager.load()
        assertEquals(listOf(1, 2, 1, 2), pages)
        assertEquals(listOf(3L, 4L), pager.state.value.videos.map { it.aid })
    }
    @Test fun failedRefreshPreservesContentAndNextPage() = runBlocking {
        var fail = false
        val pager = VideoFeedPager({ p -> if (fail) error("offline"); FeedPage(videos(p.toLong())) })
        pager.load(); fail = true; pager.load(true)
        assertEquals(listOf(1L), pager.state.value.videos.map { it.aid }); assertEquals(2, pager.state.value.nextPage)
        assertTrue(pager.state.value.refreshFailed); assertFalse(pager.state.value.busy)
        fail = false; pager.load(); assertEquals(listOf(1L, 2L), pager.state.value.videos.map { it.aid })
    }
    @Test fun nextPageFailureRetriesTheSamePage() = runBlocking {
        val pages = mutableListOf<Int>(); var fail = true
        val pager = VideoFeedPager({ p -> pages += p; if (p == 2 && fail) error("offline"); FeedPage(videos(p.toLong())) })
        pager.load(); pager.load(); assertEquals(2, pager.state.value.nextPage)
        fail = false; pager.load(); assertEquals(listOf(1, 2, 2), pages)
        assertNull(pager.state.value.error)
    }
    @Test fun overlappingAppendRequestsAreNotQueued() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var calls = 0
        val pager = VideoFeedPager({ calls++; entered.complete(Unit); finish.await(); FeedPage(videos(1)) })
        val first = launch { pager.load() }; entered.await(); pager.load()
        assertEquals(1, calls); finish.complete(Unit); first.join(); assertFalse(pager.state.value.busy)
    }
    @Test fun cancellationUnlocksThePagerWithoutLosingContent() = runBlocking {
        var block = true; val entered = CompletableDeferred<Unit>()
        val pager = VideoFeedPager({ if (block) { entered.complete(Unit); awaitCancellation() }; FeedPage(videos(2), more = false) }, videos(1))
        val job = launch { pager.load() }; entered.await(); job.cancelAndJoin()
        assertFalse(pager.state.value.busy); assertEquals(1, pager.state.value.videos.size)
        block = false; pager.load(); assertEquals(2, pager.state.value.videos.size)
    }
    @Test fun shortFeedAcceptsFullLengthOrdinaryVideosAndFiltersOtherKinds() = runBlocking {
        val long = Video(aid = 1, duration = 3600)
        val pager = VideoFeedPager({ FeedPage(listOf(long, Video(kind = "live", targetId = 8))) }, accept = { it.kind == "video" })
        pager.load(); assertEquals(listOf(long), pager.state.value.videos)
        assertEquals(3600L, pager.state.value.videos.single().duration)
    }
    @Test fun densityUsesAvailableWidthAndReadableColumnLimits() {
        assertEquals(2, FeedDisplayPolicy.columns(393f, 1, 0))
        assertEquals(3, FeedDisplayPolicy.columns(393f, 2, 0))
        assertEquals(1, FeedDisplayPolicy.columns(393f, 1, 1))
        assertEquals(3, FeedDisplayPolicy.columns(393f, 1, 8))
        assertTrue(FeedDisplayPolicy.columns(1200f, 1, 0) > 5)
        assertEquals(1, FeedDisplayPolicy.columns(80f, 2, 8))
    }
    @Test fun paginationPrefetchesTwoRowsButNotEmptyLayouts() {
        assertFalse(FeedDisplayPolicy.nearEnd(-1, 0, 2))
        assertFalse(FeedDisplayPolicy.nearEnd(10, 26, 2))
        assertTrue(FeedDisplayPolicy.nearEnd(21, 26, 2))
    }
    @Test fun holdDownLocksAndSecondHoldDownRestoresOriginalSpeed() {
        val g = PressPlaybackGesture()
        assertEquals(3f, g.begin(1.25f, 3f, true, true, 10000, 200000).speed)
        assertEquals(3f, g.move(0f, 70f, 400f, 1f).speed)
        assertEquals(3f, g.end(false).speed); assertEquals(3f, g.lockedSpeed)
        g.begin(3f, 3f, true, true, 10000, 200000)
        assertEquals(1.25f, g.move(0f, 70f, 400f, 1f).speed)
        assertEquals(1.25f, g.end(false).speed); assertNull(g.lockedSpeed)
    }
    @Test fun ordinaryReleaseRestoresSpeedWithoutPersistingALock() {
        val g = PressPlaybackGesture(); g.begin(1.5f, 2f, true, false, 0, 200000)
        g.move(0f, 100f, 400f, 1f)
        assertEquals(1.5f, g.end(false).speed); assertNull(g.lockedSpeed)
    }
    @Test fun heldHorizontalDragPreviewsAndCommitsBoundedSeekOnRelease() {
        val g = PressPlaybackGesture(); g.begin(1f, 2f, true, true, 60000, 3600000)
        val preview = g.move(200f, 0f, 400f, 1f)
        assertNull(preview.seekTo); assertEquals(120000L, g.targetPosition)
        assertEquals(1f, preview.speed)
        assertEquals(120000L, g.end(false).seekTo)
        assertNull(g.targetPosition)
    }
    @Test fun cancelledSeekNeverJumpsAndRestoresNormalSpeed() {
        val g = PressPlaybackGesture(); g.begin(.75f, 3f, true, true, 50000, 100000)
        g.move(-500f, 0f, 400f, 1f); assertEquals(0L, g.targetPosition)
        val end = g.end(true); assertNull(end.seekTo); assertEquals(.75f, end.speed)
    }
    @Test fun aHorizontalGestureCannotBecomeASpeedLock() {
        val g = PressPlaybackGesture(); g.begin(1f, 2f, true, true, 0, 100000)
        g.move(100f, 0f, 400f, 1f); g.move(100f, 200f, 400f, 1f)
        assertEquals(PressDrag.SEEK, g.drag); assertNull(g.lockedSpeed)
    }
    @Test fun disabledAccelerationStillAllowsHeldSeeking() {
        val g = PressPlaybackGesture(); assertNull(g.begin(1.5f, 3f, false, true, 0, 100000).speed)
        g.move(100f, 0f, 400f, 1f); assertEquals(25000L, g.end(false).seekTo); assertNull(g.lockedSpeed)
    }
    @Test fun leavingPlaybackClearsBothActiveHoldAndLockedSpeed() {
        val g = PressPlaybackGesture(); g.begin(1.25f, 3f, true, true, 0, 100000)
        g.move(0f, 200f, 400f, 2f); g.end(false)
        assertEquals(1.25f, g.reset().speed); assertNull(g.lockedSpeed); assertFalse(g.active)
        assertNull(g.reset().speed)
    }
    @Test fun leftAdjustsBrightnessRightAdjustsVolumeAndValuesAreClamped() {
        assertEquals(VerticalAdjustment.BRIGHTNESS, VerticalGesturePolicy.target(100f, 800f))
        assertEquals(VerticalAdjustment.VOLUME, VerticalGesturePolicy.target(700f, 800f))
        assertEquals(1f, VerticalGesturePolicy.value(.5f, -1000f, 400f), .001f)
        assertEquals(0f, VerticalGesturePolicy.value(.5f, 1000f, 400f), .001f)
    }
}
