// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import dev.bilidesk.android.ui.*
import dev.bilidesk.android.download.DownloadChoice
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MusicAndRefreshTest {
    private fun song(n: Int) = MusicTrack(Video(bvid = "BV$n", title = "song$n"))
    private fun songs() = listOf(song(1), song(2), song(3), song(4))
    private fun queue(mode: MusicMode = MusicMode.ORDER) = MusicQueueState(songs(), song(1).key, mode)
    @Test fun addingSearchResultPreservesCurrentSongAndExistingQueue() {
        val q = queue().add(song(5))
        assertEquals(song(1), q.current); assertEquals(5, q.tracks.size)
    }
    @Test fun nextUpOverridesShuffleAndKeepsInsertionOrder() {
        val q = queue(MusicMode.SHUFFLE).add(song(5), true).add(song(3), true)
        val next = q.advance(true)!!; assertEquals(song(5), next.current); assertEquals(song(3), next.advance(false)!!.current)
    }
    @Test fun automaticSingleRepeatAndManualNextHaveDifferentBehavior() {
        val q = queue(MusicMode.ONE)
        assertEquals(song(1), q.advance(false)!!.current); assertEquals(song(2), q.advance(true)!!.current)
    }
    @Test fun orderStopsAtEndAndLoopRestarts() {
        val q = queue().select(song(4).key)
        assertNull(q.advance(false)); assertEquals(song(1), q.copy(mode = MusicMode.LOOP).advance(false)!!.current)
    }
    @Test fun shuffleDoesNotRepeatUntilEverySongHasPlayed() {
        var q = queue(MusicMode.SHUFFLE); val played = mutableSetOf(q.currentKey)
        repeat(3) { q = q.advance(false, Random(27))!!; assertTrue(played.add(q.currentKey)) }
        assertEquals(4, played.size)
    }
    @Test fun previousFollowsPlaybackHistoryInShuffle() {
        val q = queue(MusicMode.SHUFFLE).select(song(4).key).select(song(2).key)
        assertEquals(song(4), q.previous().current); assertEquals(song(1), q.previous().previous().current)
    }
    @Test fun removingCurrentChoosesAdjacentAndClearsPriorityReferences() {
        val q = queue().select(song(2).key).add(song(2), true).remove(song(2).key)
        assertEquals(song(3), q.current); assertTrue(q.nextUp.isEmpty()); assertEquals(3, q.tracks.size)
    }
    @Test fun reorderingDoesNotChangePlayingSong() {
        val q = queue().move(0, 3)
        assertEquals(song(1), q.current); assertEquals(song(1), q.tracks.last())
    }
    @Test fun droppedOrderPreservesPlaybackPriorityAndConcurrentSongs() {
        val q = queue(MusicMode.SHUFFLE).playNext(song(3).key).select(song(2).key).remove(song(4).key).add(song(5))
        val moved = q.reorder(listOf(song(4).key, song(3).key, song(1).key, song(1).key, song(2).key))
        assertEquals(listOf(song(3), song(1), song(2), song(5)), moved.tracks)
        assertEquals(q.currentKey, moved.currentKey); assertEquals(q.nextUp, moved.nextUp)
        assertEquals(q.remaining, moved.remaining); assertEquals(q.history, moved.history)
    }
    @Test fun queueNextSelectionTakesPriorityWithoutStartingOrDuplicatingASong() {
        val q = queue(MusicMode.SHUFFLE).playNext(song(3).key).playNext(song(4).key).playNext(song(3).key)
        assertEquals(song(1), q.current); assertEquals(listOf(song(3).key, song(4).key), q.nextUp)
        assertEquals(song(3), q.advance(true)!!.current); assertEquals(q, q.playNext(q.currentKey))
        assertEquals(q, q.playNext("unknown"))
    }
    @Test fun persistenceRestoresQueueOrderModePriorityAndPosition() {
        val q = queue(MusicMode.SHUFFLE).add(song(5), true).select(song(3).key)
        val json = JSONObject(q.json(12345).toString()); assertEquals(q, MusicQueueState.from(json)); assertEquals(12345, json.getLong("position"))
    }
    @Test fun resolvedVideoMetadataDoesNotChangeQueueIdentity() {
        val original = MusicTrack(Video(aid = 10))
        val resolved = original.copy(video = Video(aid = 10, bvid = "BV10", title = "resolved"))
        assertEquals(original.key, resolved.key)
    }
    @Test fun lyricParsingRejectsInvalidRangesAndSortsRealTimestamps() {
        val cues = Lyrics.parse(JSONObject("""{"body":[{"from":5,"to":7,"content":"第二句"},{"from":0.5,"to":2,"content":"第一句"},{"from":3,"to":1,"content":"bad"},{"from":-1,"to":2,"content":"bad"}]}"""))
        assertEquals(2, cues.size); assertEquals(500, cues.first().startMs); assertEquals("第二句", cues.last().text)
        assertEquals(-1, Lyrics.current(cues, 200)); assertEquals(0, Lyrics.current(cues, 1000)); assertEquals(-1, Lyrics.current(cues, 3000)); assertEquals(1, Lyrics.current(cues, 6000))
    }
    @Test fun subtitleSoundMarkersAreNotPresentedAsLyricsAndRealWordsKeepTheirTiming() {
        val cues = Lyrics.parse(JSONObject("""{"body":[{"from":0,"to":3,"content":"[Music]"},{"from":3,"to":6,"content":"♪ 伴奏 ♫"},{"from":6,"to":9,"content":"Music is my life"},{"from":9,"to":12,"content":"你的声音穿过晚风"},{"from":12,"to":15,"content":"（掌声）"}]}"""))
        assertEquals(listOf("Music is my life", "你的声音穿过晚风"), cues.map { it.text })
        assertEquals(listOf(6000L, 9000L), cues.map { it.startMs }); assertEquals(-1, Lyrics.current(cues, 1000))
    }
    @Test fun lockedSpeedIsChosenDuringDragAndCommittedOnRelease() {
        val gesture = PressPlaybackGesture()
        gesture.begin(1.25f, 2f, true, true, 10000, 120000)
        gesture.move(0f, 80f, 400f, 1f); assertTrue(gesture.choosingLock); assertNull(gesture.lockedSpeed)
        gesture.move(0f, 160f, 400f, 1f); assertEquals(3f, gesture.lockChoice)
        assertEquals(3f, gesture.end(false).speed); assertEquals(3f, gesture.lockedSpeed)
    }
    @Test fun speedChooserCancellationRestoresRateAndNeverSeeks() {
        val g = PressPlaybackGesture(); g.begin(1.5f, 2f, true, true, 10000, 120000)
        g.move(0f, 70f, 400f, 1f); g.move(120f, 150f, 400f, 1f)
        val effect = g.end(true); assertEquals(1.5f, effect.speed); assertNull(effect.seekTo); assertNull(g.lockedSpeed)
    }
    @Test fun secondHeldDownReleaseCanRestoreOriginalRate() {
        val g = PressPlaybackGesture(); g.begin(1.25f, 2f, true, true, 0, 60000); g.move(0f, 80f, 400f, 1f); g.end(false)
        g.begin(2f, 2f, true, true, 0, 60000); g.move(0f, 80f, 400f, 1f)
        assertNull(g.lockChoice); assertEquals(1.25f, g.end(false).speed)
    }
    @Test fun cacheVariantsKeepAudioAndVideoQualitiesSeparate() {
        val video = DownloadChoice.key("BV1", 1, 80, false)
        assertEquals("BV1-1", video)
        assertEquals(3, setOf(video, DownloadChoice.key("BV1", 1, 64, false), DownloadChoice.key("BV1", 1, 80, true)).size)
    }
    @Test fun refreshedNewItemsAppearBeforeRepeatedItems() = runBlocking {
        val old = listOf(song(1).video, song(2).video)
        val pager = VideoFeedPager({ FeedPage(listOf(song(1).video, song(3).video, song(2).video), more = false) }, old, preferNewOnRefresh = true)
        assertTrue(pager.load(true)); assertEquals("BV3", pager.state.value.videos.first().bvid); assertEquals(1, pager.state.value.revision)
    }
    @Test fun refreshLooksPastRepeatedFallbackFirstPageAndAdvancesCursor() = runBlocking {
        val calls = mutableListOf<Int>()
        val pager = VideoFeedPager({ page -> calls.add(page); FeedPage(listOf(song(if (page == 1) 1 else 2).video)) }, listOf(song(1).video), preferNewOnRefresh = true)
        pager.load(true); assertEquals(listOf(1, 2), calls); assertEquals("BV2", pager.state.value.videos.first().bvid); assertEquals(3, pager.state.value.nextPage)
    }
    @Test fun pullRefreshWaitsForAppendInsteadOfBeingDropped() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var calls = 0
        val pager = VideoFeedPager({ calls++; if (calls == 1) { entered.complete(Unit); finish.await(); FeedPage(listOf(song(1).video)) } else FeedPage(listOf(song(2).video), more = false) })
        val append = launch { pager.load() }; entered.await(); val refresh = async { pager.load(true) }; yield(); finish.complete(Unit); append.join()
        assertTrue(refresh.await()); assertEquals(listOf("BV2"), pager.state.value.videos.map { it.bvid })
    }
    @Test fun repeatedRefreshIsBoundedAndDoesNotPretendThereIsNewContent() = runBlocking {
        var calls = 0
        val pager = VideoFeedPager({ calls++; FeedPage(listOf(song(1).video)) }, listOf(song(1).video), preferNewOnRefresh = true)
        pager.load(true); assertTrue(calls <= 3); assertTrue(pager.state.value.unchanged); assertEquals(1, pager.state.value.videos.size)
    }
}
