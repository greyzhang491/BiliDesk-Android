// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Route(val name: String = "home", val argument: String = "", val label: String = "")
data class CreatorRequest(val seed: CreatorProfile, val current: Video? = null)
class ShortSession(val pager: VideoFeedPager, var index: Int = 0)

class AppViewModel: ViewModel() {
    private val stack = mutableListOf<Route>()
    private var musicReturn = Route()
    private val _route = MutableStateFlow(Route())
    val route = _route.asStateFlow()
    private val session = AccountSession(viewModelScope, Graph.api.cookies, { Graph.api.user() })
    val account = session.state
    val user = account.map { it.user }.stateIn(viewModelScope, SharingStarted.Eagerly, account.value.user)
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val refreshing = account.map { it.checking }.stateIn(viewModelScope, SharingStarted.Eagerly, account.value.checking)
    private val _creator = MutableStateFlow<CreatorRequest?>(null)
    val creator = _creator.asStateFlow()
    fun openCreator(video: Video) { if (video.mid > 0) _creator.value = CreatorRequest(CreatorProfile(video.mid, video.author, video.authorAvatar), video) }
    fun openCreator(profile: CreatorProfile) { if (profile.mid > 0) _creator.value = CreatorRequest(profile, Graph.player.state.value.detail?.video?.takeIf { it.mid == profile.mid }) }
    fun closeCreator() { _creator.value = null }
    private val _shortSessions = MutableStateFlow(newShortSession(emptyList()))
    val shortSessions = _shortSessions.asStateFlow()
    val shortSession get() = _shortSessions.value
    val networkEpoch = MutableStateFlow(0)
    fun playCreatorVideo(video: Video, visible: List<Video>, nextPage: Int, keyword: String) {
        closeCreator()
        if (_route.value.name == "shorts") {
            val videos = visible.filter { it.kind == "video" }.distinctBy { it.key }
            _shortSessions.value = ShortSession(VideoFeedPager({ Graph.api.uploads(video.mid, it, keyword) }, videos, { it.kind == "video" }, initialPage = nextPage), videos.indexOfFirst { it.key == video.key }.coerceAtLeast(0))
        } else if (_route.value.name.startsWith("music")) playMusic(video) else openVideo(video)
    }
    private fun newShortSession(videos: List<Video>) = ShortSession(VideoFeedPager({ Graph.api.feed("推荐", it) }, videos, { it.kind == "video" }))
    init { refreshUser(); viewModelScope.launch { runCatching { Graph.api.warmUp() } } }
    fun navigate(name: String, argument: String = "", label: String = "") { stack.add(_route.value); _route.value = Route(name, argument, label) }
    fun root(name: String) {
        if (name == "music" && _route.value.name in listOf("home", "explore", "library")) musicReturn = _route.value
        stack.clear(); _route.value = Route(name)
    }
    fun closeMusicHome() { stack.clear(); _route.value = musicReturn }
    fun back(): Boolean { if (stack.isEmpty()) return false; _route.value = stack.removeAt(stack.lastIndex); return true }
    fun openVideo(video: Video) { Graph.player.open(video); if (_route.value.name != "player") navigate("player") }
    fun openPlayer(fullscreen: Boolean = false) { if (Graph.player.state.value.music) { openMusicPlayer(); return }; if (Graph.player.state.value.detail != null && _route.value.name != "player") navigate("player", if (fullscreen) "fullscreen" else "") }
    fun openMusicPlayer() { if (_route.value.name != "music_player") navigate("music_player") }
    fun playMusic(video: Video) {
        val current = Graph.player.state.value.detail?.video?.key == video.key
        val ep = if (current) Graph.player.currentEpisode() else null
        Graph.player.music.play(video, ep?.cid ?: 0, position = if (current) Graph.player.exo.currentPosition else 0)
        openMusicPlayer()
    }
    fun openShorts(seed: Video? = null, queue: List<Video> = emptyList()) {
        if (seed != null || queue.isNotEmpty()) _shortSessions.value = newShortSession(listOfNotNull(seed) + queue)
        if (_route.value.name != "shorts") navigate("shorts")
    }
    fun openCache(cache: CacheEntry) { Graph.player.playCache(cache); if (cache.type == "audio") openMusicPlayer() else navigate("player") }
    fun requireLogin(action: () -> Unit) {
        if (session.canUseAccount()) action()
        else if (account.value.status == AccountStatus.STORAGE_UNAVAILABLE) { notices.tryEmit(account.value.message!!); refreshUser() }
        else navigate("login")
    }
    fun refreshUser(onSuccess: (() -> Unit)? = null) {
        session.refresh(onVerified = onSuccess)
    }
    fun retryAccount() { session.refresh(force = true) }
    fun onNetworkRecovered() { session.refresh(restartRunning = true); networkEpoch.value++; Graph.player.onNetworkRecovered() }
    fun loggedIn() {
        session.refresh(force = true, restartRunning = true) { notices.tryEmit("登录成功") }
        if (account.value.canUseAccount && _route.value.name == "login") back()
    }
    fun logout() {
        Graph.player.reportHistory()
        viewModelScope.launch {
            try { session.logout(); android.webkit.CookieManager.getInstance().removeAllCookies(null); root("library"); notices.tryEmit("已退出登录") }
            catch (error: Exception) { if (error is CancellationException) throw error; notices.tryEmit("本机退出信息保存失败，请稍后重试") }
        }
    }
    fun action(success: String, block: suspend () -> Unit) { viewModelScope.launch { try { block(); notices.emit(success) } catch (e: Exception) { if (e is CancellationException) throw e; notices.emit(e.message ?: "操作失败") } } }
    fun resolveLink(text: String) {
        val short = Regex("https://b23\\.tv/[0-9A-Za-z]+").find(text)?.value
        if (short != null) {
            viewModelScope.launch { try { resolveLink(Graph.api.resolveShortLink(short)) } catch (e: Exception) { if (e is CancellationException) throw e; notices.emit(e.message ?: "短链接解析失败") } }
            return
        }
        val bvid = Regex("BV[0-9A-Za-z]{10}").find(text)?.value
        if (bvid != null) { openVideo(Video(bvid = bvid)); return }
        val aid = Regex("(?i)av(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (aid != null) { openVideo(Video(aid = aid)); return }
        val season = Regex("/bangumi/play/ss(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        if (season != null) { openVideo(Video(kind = "season", targetId = season)); return }
        navigate("search", text)
    }
}
