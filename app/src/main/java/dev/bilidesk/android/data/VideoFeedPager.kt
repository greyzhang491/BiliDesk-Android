// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class VideoFeedContent(
    val videos: List<Video> = emptyList(), val nextPage: Int = 1, val more: Boolean = true,
    val busy: Boolean = false, val refreshing: Boolean = false, val error: String? = null,
    val refreshFailed: Boolean = false, val revision: Int = 0, val unchanged: Boolean = false
)

/** One request at a time. Failures preserve both content and the page to retry. */
class VideoFeedPager(
    private val loadPage: suspend (Int) -> FeedPage,
    initial: List<Video> = emptyList(),
    private val accept: (Video) -> Boolean = { true },
    private val preferNewOnRefresh: Boolean = false
) {
    private val gate = Mutex()
    private val refreshVersion = java.util.concurrent.atomic.AtomicInteger()
    private val waitingRefresh = java.util.concurrent.atomic.AtomicInteger()
    private val _state = MutableStateFlow(VideoFeedContent(videos = initial.filter(accept).distinctBy { it.key }))
    val state = _state.asStateFlow()

    suspend fun load(reset: Boolean = false): Boolean {
        val version = if (reset) refreshVersion.incrementAndGet() else 0
        if (reset) {
            waitingRefresh.incrementAndGet()
            try { gate.lock() } finally { waitingRefresh.decrementAndGet() }
        } else if (waitingRefresh.get() > 0 || !gate.tryLock()) return false
        try {
            if (reset && version != refreshVersion.get()) return false
            val before = _state.value
            if (!reset && !before.more) return false
            _state.value = before.copy(busy = true, refreshing = reset, error = null, refreshFailed = false)
            val page = if (reset) 1 else before.nextPage
            try {
                var result = loadPage(page)
                var lastPage = page
                val incoming = result.videos.filter(accept).distinctBy { it.key }.toMutableList()
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
                val previous = if (reset) emptyList() else before.videos
                val ordered = if (reset && preferNewOnRefresh) incoming.sortedBy { it.key in seen } else incoming
                val next = (previous + ordered).distinctBy { it.key }
                // Repeated/empty pages must not cause an unbounded auto-load loop.
                _state.value = VideoFeedContent(next, lastPage + 1, result.more && next.size > previous.size, revision = before.revision + if (reset) 1 else 0, unchanged = reset && preferNewOnRefresh && incoming.none { it.key !in seen })
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = before.copy(error = e.message ?: "加载失败", refreshFailed = reset)
                return false
            }
        } finally {
            _state.value = _state.value.copy(busy = false, refreshing = false)
            gate.unlock()
        }
    }
}
