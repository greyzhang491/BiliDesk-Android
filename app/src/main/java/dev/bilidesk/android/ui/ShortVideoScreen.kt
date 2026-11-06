// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*

@Composable fun ShortVideoScreen(vm: AppViewModel, onPip: () -> Unit) {
    val session by vm.shortSessions.collectAsStateWithLifecycle()
    val creator by vm.creator.collectAsStateWithLifecycle()
    val feed by key(session) { session.pager.state.collectAsStateWithLifecycle() }
    val state by Graph.player.state.collectAsStateWithLifecycle()
    val s by Graph.store.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var orientationBefore by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    var ownsOrientation by rememberSaveable { mutableStateOf(false) }
    val pager = key(session) { rememberPagerState(initialPage = session.index.coerceIn(0, (feed.videos.size - 1).coerceAtLeast(0)), pageCount = { feed.videos.size.coerceAtLeast(1) }) }
    var menu by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { menu = null }
    var fill by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf(false) }
    var ended by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    ImmersivePlayback(true)
    RestorePlaybackBrightness()
    if (download) state.detail?.video?.let { video -> Graph.player.currentEpisode()?.let { ep -> DownloadOptionsDialog(video, ep, { download = false }, onQueued = { vm.notices.tryEmit("已加入缓存队列") }) } }
    fun followRotation() { if (ownsOrientation) activity?.requestedOrientation = orientationBefore; ownsOrientation = false }
    fun rotateShort(enter: Boolean) {
        if (activity == null) return
        if (activity.isInMultiWindowMode) { vm.notices.tryEmit("分屏中横竖屏跟随设备和窗口方向"); return }
        if (!ownsOrientation) { orientationBefore = activity.requestedOrientation; ownsOrientation = true }
        activity.requestedOrientation = if (enter) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    DisposableEffect(activity) { onDispose { if (ownsOrientation && activity?.isChangingConfigurations == false) activity.requestedOrientation = orientationBefore } }
    fun back() { when { menu != null -> menu = null; ownsOrientation && landscape -> followRotation(); else -> if (!vm.back()) vm.root("home") } }
    BackHandler { back() }
    LaunchedEffect(session) { if (feed.videos.isEmpty()) session.pager.load() }
    val selected = feed.videos.getOrNull(pager.settledPage)
    val activeVideo = state.detail?.video?.takeIf { selected != null && (it.key == selected.key || it.aid > 0 && it.aid == selected.aid) }
    val actions = remember(activeVideo?.key, user?.mid) { activeVideo?.let { ShortEngagement(Graph.api, it, scope) { notice -> vm.notices.tryEmit(notice) } } }
    LaunchedEffect(actions, user?.mid) { if (user != null) actions?.load() }
    fun favorites() { vm.requireLogin { menu = "favorites"; actions?.loadFolders() } }
    LaunchedEffect(session, selected?.key, pager.settledPage) {
        session.index = pager.settledPage
        selected?.let { Graph.player.open(it) }
    }
    LaunchedEffect(session, pager.settledPage, feed.videos.size, feed.busy, feed.error) {
        if (feed.videos.isNotEmpty() && pager.settledPage >= feed.videos.size - 3 && feed.more && !feed.busy && feed.error == null) session.pager.load()
    }
    DisposableEffect(Unit) {
        val listener = object: Player.Listener { override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) ended++ } }
        Graph.player.exo.addListener(listener)
        onDispose { Graph.player.exo.removeListener(listener) }
    }
    LaunchedEffect(session, ended) {
        if (ended == 0 || Graph.player.exo.playbackState != Player.STATE_ENDED) return@LaunchedEffect
        if (s.shortsAutoNext && creator == null && menu == null && !held && !pager.isScrollInProgress) {
            if (pager.settledPage + 1 >= feed.videos.size && feed.more && feed.error == null) session.pager.load()
            if (pager.settledPage + 1 < session.pager.state.value.videos.size) { pager.animateScrollToPage(pager.settledPage + 1); return@LaunchedEffect }
        }
        Graph.player.exo.seekTo(0); Graph.player.exo.play()
    }
    LaunchedEffect(Unit) { vm.notices.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (feed.videos.isEmpty()) {
            if (feed.busy) LoadingState() else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(feed.error ?: "暂时没有视频", color = Color.White)
                TextButton(onClick = { scope.launch { session.pager.load(true) } }) { Text("重新加载") }
                TextButton(onClick = ::back) { Text("返回") }
            }
        } else VerticalPager(state = pager, userScrollEnabled = menu == null && creator == null, key = { feed.videos[it].key }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "上下滑切换视频" }) { index ->
            val video = feed.videos[index]
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val shortWindow = maxHeight < 500.dp
                if (index == pager.settledPage) {
                    val videoBounds = when {
                        menu == null -> Modifier.fillMaxSize()
                        maxWidth > maxHeight -> Modifier.width(maxWidth - playerPanelWidth(maxWidth)).fillMaxHeight()
                        else -> Modifier.fillMaxWidth().height(maxHeight - playerPanelHeight(maxHeight))
                    }
                    Box(videoBounds) { PlayerSurface(state, s, landscape, false, fill, ::rotateShort, ::back, onPip, { menu = it }, { fill = !fill }, shortMode = true, onControlsVisible = { controlsVisible = it }, onGestureActive = { held = it }) }
                    val active = activeVideo
                    // Landscape uses the regular, transient player controls. Story actions and
                    // captions stay in portrait rather than covering the landscape picture.
                    if (menu == null && !landscape && !held) {
                    if (!controlsVisible) Column(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom)).padding(start = 16.dp, end = 116.dp, bottom = 20.dp)) {
                        Text(active?.author ?: video.author, Modifier.clickable(enabled = active?.mid?.let { it > 0 } == true) { active?.let(vm::openCreator) }.padding(bottom = 5.dp), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                        Text(active?.title ?: video.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    val buttons: @Composable () -> Unit = {
                        val ready = active != null && active.aid > 0
                        if (active?.mid?.let { it > 0 } == true) Column(Modifier.clickable { vm.openCreator(active) }.semantics { contentDescription = "打开作者主页" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            UserAvatar(active.authorAvatar, active.author, Modifier.size(44.dp))
                            Text("UP主", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        ShortAction(if (actions?.liked == true) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, if (actions?.liked == true) "已赞" else "点赞", if (actions?.liked == true) "取消点赞" else "点赞视频", ready && actions?.likeBusy != true, active = actions?.liked == true) { vm.requireLogin { actions?.toggleLike() } }
                        ShortAction(Icons.Default.ChatBubbleOutline, "评论", "打开评论", ready) { menu = "comments" }
                        ShortAction(if (actions?.favorited == true) Icons.Default.Star else Icons.Default.StarBorder, if (actions?.favorited == true) "已收藏" else "收藏", "收藏视频", ready, active = actions?.favorited == true, onClick = ::favorites)
                        ShortAction(Icons.Default.Share, "分享", "分享视频", ready) { active?.let { shareVideo(context, it) } }
                    }
                    Column(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom)).padding(end = 8.dp, bottom = 104.dp), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
                    if (!controlsVisible) {
                        Row(Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom)).padding(end = 6.dp, bottom = 20.dp)) {
                            IconButton(onClick = { rotateShort(true) }) { Icon(Icons.Default.ScreenRotation, "切换横竖屏", tint = Color.White) }
                            IconButton(onClick = { menu = "shorts" }) { Icon(Icons.Default.MoreHoriz, "短视频选项", tint = Color.White) }
                        }
                        LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f }, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Bottom)).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(2.dp), color = Color.White.copy(alpha = .7f), trackColor = Color.White.copy(alpha = .15f))
                    }
                    }
                    if (index == feed.videos.lastIndex && feed.error != null) TextButton(onClick = { scope.launch { session.pager.load() } }, Modifier.align(Alignment.TopCenter).padding(top = 60.dp)) { Text("后续视频加载失败 · 重试") }
                } else AsyncImage(video.cover, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        menu?.let { section ->
            val title = when (section) { "comments" -> "评论"; "favorites" -> "添加到收藏夹"; "coin" -> "为作品投币"; "danmaku" -> "弹幕设置"; "holdSpeed" -> "长按倍速"; "speed" -> "播放倍速"; "quality" -> "清晰度"; "lines" -> "播放线路"; "shorts" -> "短视频选项"; else -> "播放设置" }
            PlayerSettingsPanel(title, section !in listOf("playback", "comments", "shorts", "favorites", "coin"), { menu = "playback" }, { menu = null }, scrollContent = section != "comments", dimAmount = if (section == "comments") 0f else .35f) {
                when (section) {
                    "comments" -> state.detail?.video?.let { VideoComments(it, vm) }
                    "favorites" -> actions?.let { ShortFavoritePanel(it) }
                    "coin" -> activeVideo?.let { CoinPanel(it, onLogin = { menu = null; vm.navigate("login") }) }
                    "danmaku" -> DanmakuSettingsControls(s, Graph.store::change)
                    "holdSpeed" -> HoldSpeedSettings(s, Graph.store::change)
                    "speed" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { rate -> FilterChip(selected = Graph.player.exo.playbackParameters.speed == rate, onClick = { Graph.player.speed(rate) }, label = { Text(speedLabel(rate)) }) } }
                    "quality" -> {
                        Text("可用画质取决于内容与账号权限", fontSize = 12.sp)
                        state.plan?.qualities.orEmpty().forEach { q -> TextButton(onClick = { Graph.player.quality(q); menu = null }) { Text(Quality.label(q) + if (state.plan?.video?.quality == q) " ✓" else "") } }
                    }
                    "lines" -> (listOfNotNull(state.plan?.video?.url) + state.plan?.video?.backups.orEmpty()).distinct().forEachIndexed { i, url -> TextButton(onClick = { Graph.player.line(url); menu = null }) { Text("线路 ${i + 1}") } }
                    "shorts" -> {
                        SwitchSetting("播完自动下一条", "关闭时循环当前视频；浏览评论时继续当前视频", s.shortsAutoNext) { next -> Graph.store.change { it.copy(shortsAutoNext = next) } }
                        MenuRow(Icons.Default.ChatBubbleOutline, "评论", "视频继续播放") { menu = "comments" }
                        MenuRow(if (actions?.liked == true) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, if (actions?.liked == true) "取消点赞" else "点赞视频") { vm.requireLogin { actions?.toggleLike() } }
                        MenuRow(Icons.Default.Toll, "投币") { vm.requireLogin { menu = "coin" } }
                        MenuRow(Icons.Default.StarBorder, "收藏到收藏夹", if (actions?.favorited == true) "已收藏" else "") { favorites() }
                        MenuRow(Icons.Default.WatchLater, "稍后再看") { activeVideo?.let { current -> vm.requireLogin { vm.action("已加入稍后再看") { Graph.api.watchLater(current.aid, true) } } } }
                        MenuRow(Icons.Default.Download, "缓存当前分P") { download = true; menu = null }
                        MenuRow(Icons.Default.Share, "分享视频") { activeVideo?.let { shareVideo(context, it) } }
                        MenuRow(Icons.Default.ScreenRotation, "跟随设备旋转", if (ownsOrientation) "恢复自动横竖屏" else "当前跟随设备") { followRotation(); menu = null }
                        Text("长按后下拉选择锁定倍速，继续滑动选速度，松手确定。选择恢复原速可解除锁定。", Modifier.padding(vertical = 16.dp), fontSize = 13.sp)
                        Text("横屏时中间区域上下滑切换视频；左边缘上下滑调亮度，右边缘调音量。", fontSize = 12.sp)
                        TextButton(onClick = { menu = null; scope.launch { session.pager.load(true); if (session.pager.state.value.error == null) pager.scrollToPage(0) } }) { Text("刷新推荐视频") }
                        MenuRow(Icons.Default.OpenInFull, "切回普通播放") { vm.openPlayer() }
                    }
                    else -> {
                        MenuRow(Icons.Default.Speed, "播放倍速", speedLabel(Graph.player.exo.playbackParameters.speed)) { menu = "speed" }
                        MenuRow(Icons.Default.TouchApp, "长按倍速", if (s.holdSpeedEnabled) speedLabel(s.holdSpeed) else "已关闭") { menu = "holdSpeed" }
                        MenuRow(Icons.Default.Subtitles, "弹幕设置", "即时调整当前视频") { menu = "danmaku" }
                        MenuRow(Icons.Default.MoreHoriz, "视频互动与连播", "评论、点赞、收藏、分享") { menu = "shorts" }
                        if (!state.local) MenuRow(Icons.Default.HighQuality, "清晰度", Quality.label(state.plan?.video?.quality ?: 0)) { menu = "quality" }
                        if (!state.local) MenuRow(Icons.Default.NetworkCheck, "播放线路") { menu = "lines" }
                        SwitchSetting("填满视频区域", "开启后裁掉画面边缘；关闭时保留完整画面", fill) { fill = it }
                    }
                }
            }
        }
    }
}
