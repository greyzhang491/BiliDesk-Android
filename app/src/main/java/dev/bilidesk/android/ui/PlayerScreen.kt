// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.PlaybackState
import kotlinx.coroutines.*

@Composable fun PlayerScreen(vm: AppViewModel, onPip: () -> Unit, pip: Boolean) {
    val state by Graph.player.state.collectAsStateWithLifecycle()
    val s by Graph.store.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val largeWindowDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var fill by rememberSaveable { mutableStateOf(false) }
    var orientationBefore by rememberSaveable { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    var ownsOrientation by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val detail = state.detail
    var liked by remember(detail?.video?.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val favorites = remember(detail?.video?.key, user?.mid) { detail?.video?.let { ShortEngagement(Graph.api, it, scope) { vm.notices.tryEmit(it) } } }
    val snackbar = remember { SnackbarHostState() }
    RestorePlaybackBrightness()
    ImmersivePlayback(fullscreen, pip)
    fun setFullscreen(enter: Boolean) {
        if (fullscreen == enter) return
        if (enter && !largeWindowDevice && activity?.isInMultiWindowMode == false) {
            orientationBefore = activity.requestedOrientation
            ownsOrientation = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (!enter && ownsOrientation) {
            activity?.requestedOrientation = orientationBefore
            ownsOrientation = false
        }
        fullscreen = enter
    }
    fun back() {
        when { menu != null -> menu = null; fullscreen -> setFullscreen(false); else -> if (!vm.back()) vm.root("home") }
    }
    LaunchedEffect(Unit) { if (vm.route.value.argument == "fullscreen") setFullscreen(true) }
    BackHandler { back() }
    DisposableEffect(state.playing) {
        if (state.playing) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        onDispose {
            if (ownsOrientation && activity?.isChangingConfigurations == false) activity.requestedOrientation = orientationBefore
        }
    }
    LaunchedEffect(detail?.video?.aid, user?.mid) { if (detail != null && detail.video.aid > 0 && user != null) runCatching { Graph.api.liked(detail.video.aid) }.onSuccess { liked = it } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); Graph.player.clearNotice() } }
    LaunchedEffect(Unit) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).then(if (!fullscreen && !pip) Modifier.safeDrawingPadding() else Modifier)) {
            AdaptivePlayerLayout(fullscreen || pip, Modifier.fillMaxSize(), video = {
                PlayerSurface(state, s, fullscreen, pip, fill, ::setFullscreen, ::back, onPip, { menu = it }, { fill = !fill })
            }, details = {
                PlayerInformation(state, vm, liked, onLike = {
                    vm.requireLogin { val next = !liked; vm.action(if (next) "点赞成功" else "已取消点赞") { Graph.api.like(detail!!.video, next); liked = next } }
                }, onFavorite = {
                    vm.requireLogin { favorites?.loadFolders(); menu = "favorites" }
                }, onCoin = { vm.requireLogin { menu = "coin" } }, onQuality = { menu = "quality" }, onLines = { menu = "lines" })
            })
            menu?.let { section ->
                val title = when (section) { "danmaku" -> "弹幕设置"; "holdSpeed" -> "长按倍速"; "speed" -> "播放倍速"; "quality" -> "清晰度"; "lines" -> "播放线路"; "favorites" -> "添加到收藏夹"; "coin" -> "为作品投币"; else -> "播放设置" }
                PlayerSettingsPanel(title, section != "playback", { menu = "playback" }, { menu = null }) {
                    when (section) {
                        "danmaku" -> DanmakuSettingsControls(s, Graph.store::change)
                        "holdSpeed" -> HoldSpeedSettings(s, Graph.store::change)
                        "speed" -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { speed -> FilterChip(selected = Graph.player.exo.playbackParameters.speed == speed, onClick = { Graph.player.speed(speed) }, label = { Text(speedLabel(speed)) }) } }
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            HoldSpeedSettings(s, Graph.store::change)
                        }
                        "quality" -> {
                            Text("可用画质取决于内容与账号权限", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            state.plan?.qualities.orEmpty().forEach { q -> TextButton(onClick = { Graph.player.quality(q); menu = null }, Modifier.fillMaxWidth()) { Text(Quality.label(q) + if (state.plan?.video?.quality == q) "  ✓" else "") } }
                        }
                        "lines" -> (listOfNotNull(state.plan?.video?.url) + state.plan?.video?.backups.orEmpty()).distinct().forEachIndexed { i, url -> TextButton(onClick = { Graph.player.line(url); menu = null }, Modifier.fillMaxWidth()) { Text("线路 ${i + 1} · ${android.net.Uri.parse(url).host.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        "favorites" -> favorites?.let { ShortFavoritePanel(it) }
                        "coin" -> CoinPanel(detail!!.video, onLogin = { menu = null; vm.navigate("login") })
                        else -> {
                            MenuRow(Icons.Default.Speed, "播放倍速", speedLabel(Graph.player.exo.playbackParameters.speed)) { menu = "speed" }
                            MenuRow(Icons.Default.TouchApp, "长按倍速", if (s.holdSpeedEnabled) speedLabel(s.holdSpeed) else "已关闭") { menu = "holdSpeed" }
                            MenuRow(Icons.Default.Subtitles, "弹幕设置", "即时调整当前视频") { menu = "danmaku" }
                            if (!state.local) MenuRow(Icons.Default.HighQuality, "清晰度", Quality.label(state.plan?.video?.quality ?: 0)) { menu = "quality" }
                            if (!state.local) MenuRow(Icons.Default.NetworkCheck, "播放线路") { menu = "lines" }
                            SwitchSetting("填满视频区域", "开启后可能裁掉画面边缘；关闭时保留完整画面", fill) { fill = it }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PlayerInformation(state: PlaybackState, vm: AppViewModel, liked: Boolean, onLike: () -> Unit, onFavorite: () -> Unit, onCoin: () -> Unit, onQuality: () -> Unit, onLines: () -> Unit) {
    val detail = state.detail
    if (detail == null) { LoadingState(); return }
    val video = detail.video
    val context = LocalContext.current
    var download by remember { mutableStateOf(false) }
    if (download) Graph.player.currentEpisode()?.let { ep -> DownloadOptionsDialog(video, ep, { download = false }, onQueued = { vm.notices.tryEmit("已加入缓存队列") }) }
    var tab by remember { mutableStateOf("简介") }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var dmText by remember { mutableStateOf("") }
    var related by remember(video.key) { mutableStateOf<List<Video>>(emptyList()) }
    LaunchedEffect(video.bvid) { if (video.bvid.isNotBlank()) runCatching { Graph.api.related(video.bvid) }.onSuccess { related = it } }
    Column(Modifier.fillMaxSize().semantics { contentDescription = "视频详情区域" }) {
        Tabs(listOf("简介", "评论", "相关推荐"), tab) { tab = it }
        if (tab == "评论") { VideoComments(video, vm); return@Column }
        LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (tab == "简介") {
                item { Text(video.title, style = MaterialTheme.typography.titleLarge, lineHeight = 29.sp); Text(if (video.kind == "live") "${countLabel(video.plays)} 人气 · ${video.subtitle}" else "${countLabel(video.plays)} 次播放 · ${countLabel(video.danmaku)} 条弹幕${if (state.local) " · 本地缓存" else ""}", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onQuality, enabled = !state.local && state.plan != null && video.kind != "live", label = { Text(if (state.local) "离线播放" else Quality.label(state.plan?.video?.quality ?: 0), fontSize = 11.sp) })
                    AssistChip(onClick = onLines, enabled = !state.local && state.plan != null, label = { Text("切换线路", fontSize = 11.sp) })
                    AssistChip(onClick = { val s = Graph.store.settings.value; Graph.store.update(s.copy(background = !s.background)); vm.notices.tryEmit(if (!s.background) "已开启听视频，锁屏后继续播放" else "已关闭后台播放") }, label = { Text("听视频", fontSize = 11.sp) }, leadingIcon = { Icon(Icons.Default.Headphones, null, Modifier.size(15.dp)) })
                } }
                if (video.kind == "video") item { Row {
                    TextButton(onClick = { vm.openShorts(video, related) }) { Icon(Icons.Default.SwipeVertical, null, Modifier.size(18.dp)); Text("竖屏连播", Modifier.padding(start = 6.dp), fontSize = 12.sp) }
                    TextButton(onClick = { vm.playMusic(video) }) { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)); Text("音乐播放", Modifier.padding(start = 6.dp), fontSize = 12.sp) }
                } }
                if (video.kind == "video") item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PlayerAction(if (liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, "${countLabel(detail.likes)}", onLike, liked)
                    PlayerAction(Icons.Default.Toll, "投币", onCoin)
                    PlayerAction(Icons.Default.FavoriteBorder, "收藏", onFavorite)
                    PlayerAction(Icons.Default.WatchLater, "稍后再看", { vm.requireLogin { vm.action("已加入稍后再看") { Graph.api.watchLater(video.aid, true) } } })
                    PlayerAction(Icons.Default.Download, "缓存", { download = true })
                    PlayerAction(Icons.Default.Share, "分享", { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${video.title}\n${video.url}"), "分享视频")) } })
                } }
                if (detail.pages.size > 1) item { Text("选集 · ${detail.pages.size}", style = MaterialTheme.typography.titleMedium); LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(detail.pages) { i, episode -> FilterChip(selected = state.pageIndex == i, onClick = { Graph.player.page(i) }, label = { Text("${i + 1}. ${episode.title}", maxLines = 1, modifier = Modifier.widthIn(max = 180.dp), overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }) } } }
                if (video.author.isNotBlank()) item { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountCircle, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { if (video.mid > 0) vm.navigate("feed", "space:${video.mid}", video.author) }) { Text(video.author, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("查看 UP 主投稿", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (video.mid > 0) CreatorFollowButton(video.mid, vm) } } }
                if (detail.description.isNotBlank()) item { Text(detail.description, Modifier.clickable { descriptionExpanded = !descriptionExpanded }, maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 21.sp) }
                if (video.kind != "live" && !state.local) item { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(dmText, { dmText = it }, Modifier.weight(1f), placeholder = { Text("发一条友善的弹幕", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(16.dp)); IconButton(onClick = { if (dmText.isNotBlank()) vm.requireLogin { val outgoing = dmText; vm.action("弹幕已发送") { Graph.api.sendDanmaku(Graph.player.currentEpisode()!!.cid, outgoing, Graph.player.exo.currentPosition); dmText = "" } } }) { Icon(Icons.Default.Send, "发送弹幕") } }; if (state.dmError) TextButton(onClick = { Graph.player.reloadDanmaku() }) { Text("弹幕加载失败，点击重试", fontSize = 11.sp) } }
                item { Text("双击左右快退 / 快进 10 秒 · 长按倍速可在播放设置中调整", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
                item { TextButton(onClick = { browse(context, video.url) }) { Text("在官方网页打开", fontSize = 12.sp) } }
            } else {
                items(related, key = { it.key }) { v -> CompactVideo(v) { vm.openVideo(v) } }
                if (related.isEmpty()) item { Text("暂时没有相关视频", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
@Composable private fun PlayerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, active: Boolean = false) {
    Column(Modifier.widthIn(min = 38.dp).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, text, Modifier.size(23.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Text(text, Modifier.padding(top = 7.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
