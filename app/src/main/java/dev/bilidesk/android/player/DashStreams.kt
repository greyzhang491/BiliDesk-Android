// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

import dev.bilidesk.android.data.PlayPlan
import dev.bilidesk.android.data.Stream

/** Convert the API's existing SegmentBase/SIDX metadata; never guess byte offsets. */
object DashStreams {
    private val range = Regex("[0-9]+-[0-9]+")
    fun indexed(stream: Stream): Boolean = listOf(stream.initialization, stream.indexRange).all {
        range.matches(it) && it.substringBefore('-').toLongOrNull()?.let { start ->
            it.substringAfter('-').toLongOrNull()?.let { end -> end >= start } } == true
    }
    fun videos(plan: PlayPlan): List<Stream> {
        if (!plan.automatic) return listOf(plan.video)
        val family = plan.video.codec.substringBefore('.')
        return (listOf(plan.video) + plan.videoOptions).filter { it.quality <= 80 && it.codec.substringBefore('.') == family && indexed(it) }
            .distinctBy { it.quality }.ifEmpty { listOf(plan.video) }
    }
    fun preferLine(plan: PlayPlan, url: String): PlayPlan {
        if (url !in listOf(plan.video.url) + plan.video.backups) return plan
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return plan
        fun prefer(stream: Stream): Stream {
            val urls = (listOf(stream.url) + stream.backups).distinct()
            val choice = if (stream.quality == plan.video.quality && stream.codec == plan.video.codec) url
                else urls.firstOrNull { runCatching { java.net.URI(it).host == host }.getOrDefault(false) } ?: stream.url
            return stream.copy(url = choice, backups = urls.filter { it != choice })
        }
        return plan.copy(video = prefer(plan.video), videoOptions = plan.videoOptions.map(::prefer), audio = plan.audio?.let(::prefer))
    }
    fun xml(streams: List<Stream>, durationMs: Long): String? {
        if (durationMs <= 0 || streams.isEmpty() || streams.any { !indexed(it) }) return null
        fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
        return buildString {
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" minBufferTime=\"PT1S\" mediaPresentationDuration=\"PT${durationMs / 1000.0}S\"><Period start=\"PT0S\"><AdaptationSet segmentAlignment=\"true\">")
            streams.forEachIndexed { index, stream ->
                append("<Representation id=\"$index\" mimeType=\"${escape(stream.mime)}\" codecs=\"${escape(stream.codec)}\" bandwidth=\"${stream.bandwidth.coerceAtLeast(1)}\"")
                if (stream.width > 0 && stream.height > 0) append(" width=\"${stream.width}\" height=\"${stream.height}\"")
                if (Regex("[0-9]+(/[0-9]+)?").matches(stream.frameRate)) append(" frameRate=\"${stream.frameRate}\"")
                append("><BaseURL>${escape(stream.url)}</BaseURL><SegmentBase indexRange=\"${stream.indexRange}\" indexRangeExact=\"true\"><Initialization range=\"${stream.initialization}\"/></SegmentBase></Representation>")
            }
            append("</AdaptationSet></Period></MPD>")
        }
    }
}
