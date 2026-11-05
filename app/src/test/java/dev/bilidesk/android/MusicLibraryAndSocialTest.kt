// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MusicLibraryAndSocialTest {
    private val video = Video(bvid = "BVtest", title = "多P歌曲")
    private fun part(cid: Long) = MusicTrack(video, cid, "分P$cid")
    @Test fun followsExcludeBlacklistedAndIncludeMutualRelationships() {
        listOf(1, 2, 3, 6).forEach { assertTrue(SocialModels.following(it)) }
        listOf(-1, 0, 4, 128, 130).forEach { assertFalse(SocialModels.following(it)) }
    }
    @Test fun commentIdsAreLongAndPreviewDepthIsBounded() {
        val c = SocialModels.comment(JSONObject("""{"rpid_str":"12345678901234","root_str":"42","parent":43,"ctime":99,"like":-1,"action":1,"rcount":8,"member":{"uname":"甲","mid":"91"},"content":{"message":"text"},"replies":[{"rpid":9,"replies":[{"rpid":10}]}]}"""))
        assertEquals(12345678901234, c.id); assertEquals(42, c.rootId); assertEquals(91, c.mid); assertTrue(c.liked); assertEquals(0, c.likes)
        assertEquals(8, c.replyCount); assertTrue(c.replies.single().replies.isEmpty())
    }
    @Test fun hotPinsDeduplicateAndRespectServerEndCursor() {
        val p = SocialModels.page(JSONObject("""{"top":{"upper":{"rpid":1}},"replies":[{"rpid":1},{"rpid":2}],"cursor":{"next":9,"is_end":false,"all_count":40}}"""), 0, CommentOrder.HOT)
        assertEquals(listOf(1L, 2L), p.comments.map { it.id }); assertTrue(p.comments.first().pinned); assertEquals(9, p.next); assertTrue(p.more)
        assertFalse(SocialModels.page(JSONObject("""{"replies":[{"rpid":3}],"cursor":{"next":10,"is_end":true}}"""), 9, CommentOrder.HOT).more)
    }
    @Test fun oldestPaginationReversesPageAndWalksTowardFirstPage() {
        val body = JSONObject("""{"page":{"count":45},"replies":[{"rpid":5},{"rpid":4}]}""")
        val p = SocialModels.page(body, 3, CommentOrder.OLDEST)
        assertEquals(listOf(4L, 5L), p.comments.map { it.id }); assertEquals(2, p.next); assertTrue(p.more)
        assertFalse(SocialModels.page(body, 1, CommentOrder.OLDEST).more)
    }
    @Test fun nestedReplyPaginationUsesReplyPageCountRatherThanRootCount() {
        val p = SocialModels.page(JSONObject("""{"page":{"count":21},"replies":[{"rpid":2}]}"""), 1, CommentOrder.NEWEST, replies = true)
        assertEquals(2, p.next); assertTrue(p.more)
    }
    @Test fun emptyCommentPageCannotCauseUnboundedLoading() {
        assertFalse(SocialModels.page(JSONObject("""{"cursor":{"is_end":false,"next":3}}"""), 1, CommentOrder.HOT).more)
    }
    @Test fun favoriteTitleCountsUnicodeCharactersAndTrimsWhitespace() {
        assertEquals("我的歌", SocialModels.folderTitle("  我的歌  "))
        assertEquals("🎶".repeat(20), SocialModels.folderTitle("🎶".repeat(20)))
        assertTrue(runCatching { SocialModels.folderTitle("x".repeat(21)) }.isFailure)
        assertTrue(runCatching { SocialModels.folderTitle("  ") }.isFailure)
    }
    @Test fun playlistsPreserveDifferentPartsAndDeduplicateSamePart() {
        val s = MusicLibraryState().create("a", "收藏").add("a", listOf(part(1), part(2), part(1)))
        assertEquals(listOf(1L, 2L), s.playlists.single().tracks.map { it.cid })
    }
    @Test fun multiplePlaylistsRenameDeleteAndRemoveIndependently() {
        val s = MusicLibraryState().create("a", "一").create("b", "二").add("a", listOf(part(1), part(2))).add("b", listOf(part(2)))
        val changed = s.rename("a", "新名字").remove("a", part(1).key).delete("b")
        assertEquals("新名字", changed.playlists.single().name); assertEquals(listOf(part(2)), changed.playlists.single().tracks)
        assertEquals(2, s.playlists.size)
    }
    @Test fun savingLibraryDoesNotMutateAnExistingPlaybackQueue() {
        val queue = MusicQueueState(listOf(part(1)), part(1).key)
        MusicLibraryState().create("a", "歌单").add("a", listOf(part(2)))
        assertEquals(part(1), queue.current); assertEquals(1, queue.tracks.size)
    }
    @Test fun playlistJsonRoundTripKeepsNamesPartsAndOrder() {
        val s = MusicLibraryState().create("a", "我的歌单").add("a", listOf(part(2), part(1))).create("b", "空歌单")
        assertEquals(s, MusicLibraryState.from(JSONObject(s.json().toString())))
        assertTrue(MusicLibraryState.from(JSONObject()).playlists.isEmpty())
    }
    @Test fun deletedOrInvalidPlaylistCannotBeSilentlyRecreatedOnAdd() {
        assertTrue(runCatching { MusicLibraryState().add("deleted", listOf(part(1))) }.isFailure)
        assertTrue(runCatching { MusicLibraryState().create("a", " ") }.isFailure)
    }
    @Test fun collectionParserPreservesSectionOrderAndFallbackMetadata() {
        val j = JSONObject("""{"ugc_season":{"id":3,"title":"合集","ep_count":3,"sections":[{"title":"第一章","episodes":[{"aid":1,"bvid":"BVone","title":"曲一","cid":11,"arc":{"duration":30}},{"aid":2,"bvid":"BVtwo","title":"曲二","page":{"cid":22}}]},{"episodes":[{"aid":1,"bvid":"BVone","cid":11}]}]}}""")
        val c = VideoCollections.parse(j, video.copy(author = "up", cover = "https://example.invalid/cover"))!!
        assertEquals(listOf(11L, 22L), c.entries.map { it.cid }); assertEquals("第一章", c.entries.first().section); assertEquals("up", c.entries.last().video.author)
        assertEquals(3, c.declaredCount)
    }
    @Test fun batchTracksIncludeEachPartAndCollectionWithoutDuplicateCurrentPart() {
        val detail = VideoDetail(video, "", listOf(Episode(1, "一", 10), Episode(2, "二", 10)), collection = VideoCollection(1, "合集", listOf(CollectionEntry(video, 1), CollectionEntry(Video(bvid = "BVother"), 3))))
        assertEquals(listOf(1L, 2L, 3L), MusicCollections.tracks(detail).map { it.cid })
    }
    private fun audio(rate: Long, id: Int = 30280) = Stream("https://example.invalid/$rate", emptyList(), id, "mp4a.40.2", rate, "audio/mp4")
    @Test fun musicSaverSelectsLowestActualAacAndNeverVideo() {
        val low = audio(64000, 30216); val high = audio(192000)
        val streams = listOf(high, Stream("video", emptyList(), 16, "avc1", 10000, "video/mp4"), low)
        assertEquals(low, MusicAudioPolicy.select(streams, MusicAudioQuality.SAVER)); assertEquals(high, MusicAudioPolicy.select(streams, MusicAudioQuality.HIGH))
    }
    @Test fun balancedQualityUsesAvailableBandwidthAndSaverFallsBackHonestly() {
        assertEquals(audio(128000), MusicAudioPolicy.select(listOf(audio(64000), audio(192000), audio(128000)), MusicAudioQuality.BALANCED))
        assertEquals(audio(192000), MusicAudioPolicy.select(listOf(audio(192000)), MusicAudioQuality.SAVER)); assertNull(MusicAudioPolicy.select(emptyList(), MusicAudioQuality.SAVER))
    }
    @Test fun audioTrafficEstimateUsesBitsAndReportsUnknownMetadataAsUnknown() {
        assertEquals(28.8, MusicAudioPolicy.megabytesPerHour(64000)!!, .001); assertNull(MusicAudioPolicy.megabytesPerHour(0))
    }
    @Test fun appearanceRestoresSettingsAndClampsCorruptValues() {
        val p = MusicPreferences.from(JSONObject("""{"style":"IMMERSIVE","coverColor":false,"accent":"#112233","darkness":99,"lyricSize":1,"animation":false,"audioQuality":"HIGH"}"""))
        assertEquals(MusicStyle.IMMERSIVE, p.style); assertFalse(p.animation); assertEquals(.9f, p.darkness); assertEquals(16f, p.lyricSize)
        assertEquals(p, MusicPreferences.from(JSONObject(p.json().toString())))
        assertEquals(MusicStyle.RECORD, MusicPreferences.from(JSONObject("""{"style":"future","accent":"bad"}""")).style)
    }
    @Test fun lyricEdgeNotesAreRemovedWithoutChangingInteriorWordsOrTiming() {
        val cues = Lyrics.parse(JSONObject("""{"body":[{"from":1,"to":3,"content":" ♪ 🎵 你和我 🎶 ♫ "},{"from":4,"to":5,"content":"保留中间♪歌词"},{"from":6,"to":7,"content":"♫♪"}]}"""))
        assertEquals(listOf("你和我", "保留中间♪歌词"), cues.map { it.text }); assertEquals(1000, cues.first().startMs); assertEquals(3000, cues.first().endMs)
    }
}
