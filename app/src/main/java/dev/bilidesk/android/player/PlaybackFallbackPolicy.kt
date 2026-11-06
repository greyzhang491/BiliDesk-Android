// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

/** Some HEVC configuration failures are wrapped as source/IO errors by Media3.
 * Match the video parser location, never treat every timeout as a codec failure.
 */
object PlaybackFallbackPolicy {
    fun needsAvc(codec: String, errorCode: Int, cause: Throwable?): Boolean {
        if (codec.isBlank() || codec.startsWith("avc", true)) return false
        if (errorCode in 4000..4999) return true
        if (!codec.startsWith("hev", true) && !codec.startsWith("hvc", true)) return false
        var failure = cause
        repeat(8) {
            val current = failure ?: return false
            if (current.stackTrace.any {
                it.className == "androidx.media3.extractor.HevcConfig" ||
                    it.className == "androidx.media3.extractor.mp4.BoxParser" && it.methodName == "parseVideoSampleEntry"
            }) return true
            failure = current.cause
        }
        return false
    }
}
