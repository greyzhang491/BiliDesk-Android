// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Short, separate AVC/AAC streams derived on-device from the independently supplied sample. */
class MusicTestMedia(source: File, directory: File) {
    val video = File(directory, "music-fixture-video.mp4")
    val audio = File(directory, "music-fixture-audio.m4a")
    val coverA = File(directory, "music-fixture-cover-a.png")
    val coverB = File(directory, "music-fixture-cover-b.png")
    val durationMs = 45000L
    init {
        if (!video.isFile || video.length() < 1024) extract(source, video, "video/")
        if (!audio.isFile || audio.length() < 1024) extract(source, audio, "audio/")
        for ((file, color) in listOf(coverA to Color.rgb(220, 75, 100), coverB to Color.rgb(45, 110, 220))) {
            if (!file.isFile) {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
    }
    private fun extract(source: File, target: File, kind: String) {
        val extractor = MediaExtractor(); var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(kind) }
            extractor.selectTrack(track)
            muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(extractor.getTrackFormat(track)); muxer.start()
            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024); val info = MediaCodec.BufferInfo()
            while (extractor.sampleTime in 0 until durationMs * 1000) {
                buffer.clear(); val count = extractor.readSampleData(buffer, 0); if (count < 0) break
                info.set(0, count, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(out, buffer, info); extractor.advance()
            }
            muxer.stop()
        } finally { muxer?.release(); extractor.release() }
    }
}
