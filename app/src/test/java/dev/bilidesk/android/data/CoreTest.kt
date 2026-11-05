// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class CoreTest {
    private val img = "7cd084941338484aae1ad9425b84077c"
    private val sub = "4932caff0ff746eab6f01bf08b70ac45"
    private fun stream(q: Int, codec: String = "avc1") = Stream("https://test.invalid/$q/$codec", emptyList(), q, codec, 1000, "video/mp4")

    @Test fun wbiKnownMixinAndSignature() {
        val key = Wbi.mixin(img, sub)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        val signed = Wbi.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169)
        // Independently checked as MD5 of the literal sorted query + the known mixin key.
        assertEquals("6149fdadf571698ca7e6a567265cd0ee", signed["w_rid"])
        assertEquals("1702204169", signed["wts"])
    }
    @Test fun wbiEncodesUnicodeAndFiltersReservedCharacters() {
        assertEquals("%E6%B5%8B%E8%AF%95%20~%2F", Wbi.encode("测试 !'()*~/"))
        val original = mapOf("keyword" to "hello!world", "w_rid" to "stale", "wts" to "0")
        val signed = Wbi.sign(original, Wbi.mixin(img, sub), 1)
        assertEquals("helloworld", signed["keyword"])
        assertEquals("stale", original["w_rid"])
        assertNotEquals("stale", signed["w_rid"])
    }
    @Test fun qualityFallbackNeverRaisesRequestedQuality() {
        assertEquals(64, Quality.select(listOf(stream(120), stream(80), stream(64)), 74, false).quality)
        assertEquals(16, Quality.select(listOf(stream(64), stream(16)), 32, false).quality)
        assertEquals(64, Quality.select(listOf(stream(80), stream(64)), 16, false).quality)
    }
    @Test fun automaticQualityChoosesHighestAndCodecPreferenceIsHonored() {
        val streams = listOf(stream(80, "avc1"), stream(80, "hev1"), stream(120, "avc1"), stream(80, "av01"))
        assertEquals(120, Quality.select(streams, 0, true).quality)
        assertEquals("hev1", Quality.select(streams, 80, true).codec)
        assertEquals("avc1", Quality.select(streams, 80, false).codec)
    }
    @Test(expected = IllegalArgumentException::class) fun noStreamsIsAnError() { Quality.select(emptyList(), 80, true) }
    @Test fun sponsorSkipsOnlyMatchingPageTrustedActionAndCategory() {
        val good = Segment(12, 1000, 2000, "sponsor", "skip", 0)
        val input = listOf(good.copy(cid = 13), good.copy(votes = -1), good.copy(action = "full"), good.copy(category = "intro"), good.copy(endMs = 900), good.copy(cid = 0), good)
        assertEquals(listOf(good), SponsorRules.select(input, 12, setOf("sponsor")))
    }
    @Test fun videoFormatsSearchResultsAndRoundTrips() {
        val json = JSONObject("""{"bvid":"BV17x411w7KC","aid":170001,"title":"<em>测试</em>&amp;视频","pic":"//example.invalid/cover.jpg","author":"UP","mid":42,"duration":"1:02:03","play":12345,"video_review":123}""")
        val video = Video.api(json)
        assertEquals("测试&视频", video.title)
        assertEquals(3723, video.duration)
        assertEquals("https://example.invalid/cover.jpg", video.cover)
        assertEquals(42, video.mid)
        assertEquals(video, Video.from(video.json()))
    }
    @Test fun numberAndDurationFormatting() {
        assertEquals("1.2万", countLabel(12345))
        assertEquals("01:02", timeLabel(62))
        assertEquals("1:00:00", timeLabel(3600))
    }
    private fun varint(value: Long): ByteArray {
        var rest = value; val bytes = ByteArrayOutputStream()
        do { val low = (rest and 127).toInt(); rest = rest ushr 7; bytes.write(low or if (rest != 0L) 128 else 0) } while (rest != 0L)
        return bytes.toByteArray()
    }
    private fun field(number: Int, value: Long) = varint((number shl 3).toLong()) + varint(value)
    private fun bytes(number: Int, value: ByteArray) = varint(((number shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
    private fun elem(time: Long, text: String, mode: Int = 1) = bytes(1, field(2, time) + field(3, mode.toLong()) + field(5, 0xff00ff) + bytes(7, text.toByteArray()) + field(9, 5))
    @Test fun danmakuReadsUtf8ColorModeWeightAndSorts() {
        val result = DanmakuParser.parse(elem(2000, "后来的", 5) + elem(1000, "弹幕", 4))
        assertEquals(2, result.size)
        assertEquals(Danmaku(1000, "弹幕", 4, 0xff00ff, 5), result.first())
        assertEquals(5, result.last().mode)
    }
    @Test fun danmakuSkipsUnknownFieldsAndHonorsCap() {
        val unknown = bytes(3, byteArrayOf(1, 2, 3))
        assertEquals(1, DanmakuParser.parse(unknown + elem(1, "a") + elem(2, "b"), 1).size)
        assertTrue(DanmakuParser.parse(byteArrayOf()).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun danmakuRejectsTruncatedMessages() { DanmakuParser.parse(byteArrayOf(10, 100, 8)) }
    @Test(expected = IllegalArgumentException::class) fun danmakuRejectsOverflowVarints() { DanmakuParser.parse(ByteArray(12) { 255.toByte() }) }
}
