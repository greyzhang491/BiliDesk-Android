// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VideoFeedContent(
    val videos: List<Video> = emptyList(), val nextPage: Int = 1, val more: Boolean = true,
    val busy: Boolean = false, val refreshing: Boolean = false, val error: String? = null,
    val refreshFailed: Boolean = false, val revision: Int = 0, val unchanged: Boolean = false, val cached: Boolean = false
)

/** One request at a time. Failures preserve both content and the page to retry. */
class VideoFeedPager(
    private val loadPage: suspend (Int) -> FeedPage,
    initial: List<Video> = emptyList(),
    private val accept: (Video) -> Boolean = { true },
    private val preferNewOnRefresh: Boolean = false,
    private val initialPage: Int = 1,
    initialNextPage: Int = initialPage,
    private val restore: suspend () -> List<Video> = { emptyList() },
    private val save: suspend (List<Video>) -> Unit = {}
) {
    private val gate = Mutex()
    private var restored = false
    private val refreshVersion = java.util.concurrent.atomic.AtomicInteger()
    private val waitingRefresh = java.util.concurrent.atomic.AtomicInteger()
    private val _state = MutableStateFlow(VideoFeedContent(videos = initial.filter(accept).distinctBy { it.key }, nextPage = initialNextPage))
    val state = _state.asStateFlow()

    /** Recommendation filtering is local; it does not shift the server's pagination offset. */
    suspend fun filterRecommendations(keep: (Video) -> Boolean) = gate.withLock {
        _state.value = _state.value.copy(videos = _state.value.videos.filter(keep))
    }

    /** Remove after the server confirms. Offset pagination must revisit the boundary shifted by deletion. */
    suspend fun removeFavorite(key: String) = gate.withLock {
        val before = _state.value
        val remaining = before.videos.filterNot { it.key == key }
        if (remaining.size != before.videos.size) _state.value = before.copy(videos = remaining,
            nextPage = initialPage + remaining.size / 20, error = null, refreshFailed = false)
    }

    suspend fun load(reset: Boolean = false): Boolean {
        val version = if (reset) refreshVersion.incrementAndGet() else 0
        if (reset) {
            waitingRefresh.incrementAndGet()
            try { gate.lock() } finally { waitingRefresh.decrementAndGet() }
        } else if (waitingRefresh.get() > 0 || !gate.tryLock()) return false
        try {
            if (reset && version != refreshVersion.get()) return false
            if (!restored) {
                restored = true
                if (_state.value.videos.isEmpty()) {
                    val cached = restore().filter(accept).distinctBy { it.key }
                    if (cached.isNotEmpty()) _state.value = _state.value.copy(videos = cached, cached = true)
                }
            }
            val before = _state.value
            if (!reset && !before.more) return false
            _state.value = before.copy(busy = true, refreshing = reset, error = null, refreshFailed = false)
            val page = if (reset) initialPage else before.nextPage
            try {
                var result = loadPage(page)
                var lastPage = page
                val incoming = result.videos.filter(accept).distinctBy { it.key }.toMutableList()
                val rawSeen = result.videos.map { it.key }.toMutableSet()
                var hiddenPages = 0
                // A fully filtered page must not hide valid recommendations on the next page.
                // Bound the look-ahead and stop on repeats rather than looping through blocked content.
                while (incoming.isEmpty() && result.videos.isNotEmpty() && result.more && hiddenPages < 2) {
                    result = loadPage(++lastPage); hiddenPages++
                    val fresh = result.videos.any { it.key !in rawSeen }
                    rawSeen.addAll(result.videos.map { it.key })
                    incoming.addAll(result.videos.filter(accept).distinctBy { it.key })
                    if (!fresh) break
                }
                val seen = before.videos.map { it.key }.toSet()
                // Popular/fallback feeds may repeat page 1. Look ahead only a bounded amount,
                // then promote genuinely new items instead of keeping repeated cards at the top.
                if (reset && preferNewOnRefresh && seen.isNotEmpty()) {
                    while (lastPage < 3 && result.more && incoming.none { it.key !in seen }) {
                        result = loadPage(++lastPage)
                        val extra = result.videos.filter(accept).filter { item -> incoming.none { it.key == item.key } }
                        if (extra.isEmpty()) break
                        incoming.addAll(extra)
                    }
                }
                val previous = if (reset || before.cached) emptyList() else before.videos
                val ordered = if (reset && preferNewOnRefresh) incoming.sortedBy { it.key in seen } else incoming
                val next = (previous + ordered).distinctBy { it.key }
                // Repeated/empty pages must not cause an unbounded auto-load loop.
                _state.value = VideoFeedContent(next, lastPage + 1, result.more && next.size > previous.size, revision = before.revision + if (reset) 1 else 0, unchanged = reset && preferNewOnRefresh && incoming.none { it.key !in seen })
                save(next)
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = before.copy(error = networkMessage(e), refreshFailed = reset)
                return false
            }
        } finally {
            _state.value = _state.value.copy(busy = false, refreshing = false)
            gate.unlock()
        }
    }
}
