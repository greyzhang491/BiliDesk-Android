// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

data class LocatedUpload(val page: Int, val content: FeedPage)

/** Bounded date search with exact ID confirmation; never scans a creator's whole archive. */
class CreatorVideoLocator(private val load: suspend (Int) -> FeedPage) {
    suspend fun find(video: Video): LocatedUpload? {
        val pages = mutableMapOf<Int, FeedPage>()
        suspend fun page(index: Int): FeedPage = pages[index] ?: load(index).also { pages[index] = it }
        fun matches(other: Video) = video.bvid.isNotBlank() && other.bvid == video.bvid || video.aid > 0 && other.aid == video.aid
        val first = page(1)
        if (first.videos.any(::matches)) return LocatedUpload(1, first)
        if (first.total <= 0 || video.publishedAt <= 0) return null
        var low = 2
        var high = ((first.total + 29) / 30).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        repeat(9) {
            if (low > high) return null
            val middle = low + (high - low) / 2
            val result = page(middle)
            if (result.videos.any(::matches)) return LocatedUpload(middle, result)
            val dates = result.videos.map { it.publishedAt }.filter { it > 0 }
            if (dates.isEmpty()) return null
            when {
                video.publishedAt > dates.max() -> high = middle - 1
                video.publishedAt < dates.min() -> low = middle + 1
                else -> {
                    // Equal publication times may straddle the page boundary.
                    for (neighbor in listOf(middle - 1, middle + 1).filter { it >= low && it <= high }) {
                        if (pages.size >= 10 && neighbor !in pages) return null
                        val nearby = page(neighbor)
                        if (nearby.videos.any(::matches)) return LocatedUpload(neighbor, nearby)
                    }
                    return null
                }
            }
        }
        return null
    }
}
