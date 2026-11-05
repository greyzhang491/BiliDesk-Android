// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import dev.bilidesk.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentContentTest {
    @Test fun parsesRealMemberMetadataAndOptionalFieldsWithoutInventingThem() {
        val c = SocialModels.comment(JSONObject("""{"rpid_str":"12345678901234567","mid":0,"like":7815.0,"action":2,"member":{"mid":"91","uname":"昵称","avatar":"//example.invalid/face.png","level_info":{"current_level":6},"vip":{"vipStatus":1},"official_verify":{"type":0,"desc":"认证说明"}},"reply_control":{"location":"IP属地：广西"},"content":{"message":"正文"}}"""))
        assertEquals(12345678901234567L, c.id); assertEquals(91L, c.mid); assertEquals(7815L, c.likes)
        assertEquals(6, c.level); assertEquals("IP属地：广西", c.location); assertTrue(c.vip); assertTrue(c.disliked); assertFalse(c.liked)
        assertEquals("认证说明", c.official); assertEquals("https://example.invalid/face.png", c.avatar)
        val absent = SocialModels.comment(JSONObject())
        assertNull(absent.level); assertEquals("", absent.location); assertEquals("", absent.official)
    }
    @Test fun parsesUsedEmotesPicturesAndMappedMentionsAndKeepsRawText() {
        val c = SocialModels.comment(JSONObject("""{"content":{"message":"回复 @昵称：\n好看[打call]🎵","emote":{"[打call]":{"url":"//example.invalid/e.png","meta":{"size":2}},"[未使用]":{"url":"//example.invalid/no.png"}},"members":[{"uname":"昵称","mid":"91"}],"pictures":[{"img_src":"//example.invalid/a.png","img_width":300,"img_height":200},{}]}}"""))
        assertEquals("回复 @昵称：\n好看[打call]🎵", c.text); assertEquals(listOf("[打call]"), c.emotes.map { it.token })
        assertEquals(2, c.emotes.single().size); assertEquals(300, c.pictures.single().width); assertEquals(91L, c.mentions["昵称"])
    }
    @Test fun richSpansDoNotEatPunctuationLineBreaksOrUnicode() {
        val text = "🎵 @名字：第一行\n[赞]和@没有映射！结束"
        val spans = CommentContent.spans(text, listOf(CommentEmote("[赞]", "url")), mapOf("名字" to 4L))
        assertEquals(listOf("@名字", "[赞]", "@没有映射"), spans.map { text.substring(it.start, it.end) })
        assertEquals(4L, spans.first().mid); assertEquals(0L, spans.last().mid)
        assertTrue(spans.zipWithNext().all { it.first.end <= it.second.start })
    }
    @Test fun matchingMentionRequiresANameBoundaryAndChoosesLongestEmote() {
        val spans = CommentContent.spans("@小明同学： [a]b", listOf(CommentEmote("[a]", "short"), CommentEmote("[a]b", "long")), mapOf("小明" to 7))
        assertEquals(0L, spans.first().mid); assertEquals("long", spans.last().emote!!.url)
    }
    @Test fun replyTargetsKeepTheRootAndParentDistinctForNestedComments() {
        val root = Comment("楼主", "", "", 0, id = 700)
        assertEquals(ReplyTarget(700, 700, "楼主"), root.replyTarget())
        assertEquals(ReplyTarget(700, 701, "甲"), root.copy(id = 701, name = "甲", rootId = 700).replyTarget())
        assertEquals(700, root.copy(id = 702).replyTarget(700).root)
        assertTrue(runCatching { root.copy(id = 0).replyTarget() }.isFailure)
    }
    @Test fun previewsInheritTheirRootWithoutRecursiveExpansion() {
        val c = SocialModels.comment(JSONObject("""{"rpid":700,"replies":[{"rpid":701,"parent":700,"replies":[{"rpid":702}]}]}"""))
        assertEquals(700, c.replies.single().rootId); assertTrue(c.replies.single().replies.isEmpty())
    }
    @Test fun reactionsAreMutuallyExclusiveAndAdjustOnlyTheVisibleLikeCount() {
        val original = Comment("甲", "", "", 5)
        val liked = original.withReaction(true, true)
        assertEquals(6, liked.likes); assertTrue(liked.liked); assertFalse(liked.disliked)
        val disliked = liked.withReaction(false, true)
        assertEquals(5, disliked.likes); assertFalse(disliked.liked); assertTrue(disliked.disliked)
        assertEquals(original, disliked.withReaction(false, false))
        assertEquals(liked, disliked.withReaction(true, true))
        assertEquals(0, original.copy(likes = 0, liked = true).withReaction(true, false).likes)
    }
    @Test fun publicCardAndSignedProfileDoNotTurnUnknownCountersIntoZero() {
        val card = CreatorProfile.parse(9, JSONObject("""{"card":{"name":"甲","face":"//example.invalid/a","sign":"签名","fans":23,"attention":10,"level_info":{"current_level":4},"Official":{"desc":"认证"}},"following":false,"archive_count":8}"""))
        assertEquals(23L, card.fans); assertEquals(10L, card.following); assertEquals(8L, card.videoCount); assertEquals(4, card.level)
        assertEquals("认证", card.official)
        val fallback = CreatorProfile.parse(10, JSONObject("""{"name":"乙","level":2,"vip":{"status":1}}"""))
        assertEquals(2, fallback.level); assertTrue(fallback.vip); assertNull(fallback.fans); assertNull(fallback.following); assertNull(fallback.videoCount)
    }
}
