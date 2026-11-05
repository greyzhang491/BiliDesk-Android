// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*

fun browse(context: Context, url: String) {
    val normalized = url.https()
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val browser = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).firstOrNull { it.activityInfo.packageName != context.packageName }
        if (browser != null) intent.setPackage(browser.activityInfo.packageName)
        context.startActivity(intent)
    }
}

@Composable fun BiliApp(vm: AppViewModel, pip: Boolean, onPip: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAppWindow provides AppWindow(maxWidth.value, maxHeight.value)) {
            BiliAppContent(vm, pip, onPip)
        }
    }
}

private val LocalAppWindow = staticCompositionLocalOf { AppWindow() }
private val destinations = listOf(Triple("home", "发现", Icons.Default.Home), Triple("shorts", "短视频", Icons.Default.SmartDisplay), Triple("explore", "分区", Icons.Default.GridView), Triple("music", "音乐", Icons.Default.MusicNote), Triple("library", "我的", Icons.Default.Person))

@Composable private fun BiliAppContent(vm: AppViewModel, pip: Boolean, onPip: () -> Unit) {
    val route by vm.route.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val playback by Graph.player.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val root = route.name in listOf("home", "explore", "music", "library")
    val immersiveMusic = route.name == "music" && LocalAppWindow.current.let { it.width < 600f && it.height > it.width }
    val rail = root && LocalAppWindow.current.useRail
    val musicSplit = LocalAppWindow.current.let { it.width - (if (rail) 80f else 0f) >= 700f && it.width > it.height }
    val topScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    fun destination(name: String) { if (name == "shorts") vm.openShorts() else vm.root(name) }
    BackHandler(enabled = !root && route.name != "player") { if (!vm.back()) vm.root("home") }
    LaunchedEffect(route.name, immersiveMusic) { if (!immersiveMusic && route.name !in listOf("player", "shorts", "music_player")) vm.notices.collect { snackbar.showSnackbar(it) } }
    if (route.name == "music_player") { MusicPlayerScreen(vm); return }
    if (route.name == "player" || pip) { PlayerScreen(vm, onPip, pip); return }
    if (route.name == "shorts") { ShortVideoScreen(vm, onPip); return }
    if (immersiveMusic) { MusicPlayerScreen(vm, onBack = vm::closeMusicHome); return }
    Scaffold(
        modifier = if (root) Modifier.nestedScroll(topScroll.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (route.name != "music") {
            TopAppBar(title = {
                if (root) RootToolbar(vm, user, library = route.name == "library")
                else Text(route.label.ifBlank { when(route.name) { "search" -> "搜索"; "music_search" -> "搜索歌曲"; "settings" -> "设置"; "login" -> "登录 BiliDesk"; "folders" -> "我的收藏"; "downloads" -> "离线缓存"; "messages" -> "消息"; "about" -> "关于 BiliDesk"; else -> "媒体库" } }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }, navigationIcon = { if (!root) IconButton(onClick = { if (!vm.back()) vm.root("home") }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                scrollBehavior = if (root) topScroll else null, expandedHeight = if (root) 56.dp else 64.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surface))
        } },
        bottomBar = { Column(if (root && !rail) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            if (playback.detail != null) {
                if (playback.music) { if (route.name != "music") MusicMiniPlayer(vm) }
                else MiniPlayer(playback.detail!!.video, playback.playing, vm)
            }
            if (root && !rail) AppBottomNavigation(route.name, destinations, ::destination)
        } }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (rail) NavigationRail(Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEach { (name, label, icon) -> NavigationRailItem(selected = route.name == name, onClick = { destination(name) }, icon = { Icon(navigationIcon(name, route.name == name, icon), label) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.padding(vertical = 8.dp)) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            when (route.name) {
                "home" -> HomeScreen(vm)
                "explore" -> ExploreScreen(vm)
                "search" -> SearchScreen(vm, route.argument)
                "library" -> LibraryScreen(vm, user)
                "music" -> MusicHomeScreen(vm, musicSplit)
                "music_search" -> MusicSearchScreen(vm)
                "music_library" -> MusicLibraryContent(vm)
                "folders" -> FolderScreen(vm)
                "feed" -> FeedRoute(vm, route)
                "downloads" -> DownloadsScreen(vm)
                "settings" -> SettingsScreen(vm)
                "login" -> LoginScreen(vm)
                "messages" -> if (user == null) LoginGate(vm, "登录后查看消息") else MessagesScreen(vm)
                "conversation" -> ConversationScreen(vm, route.argument.toLongOrNull() ?: 0)
                "about" -> AboutScreen()
                else -> EmptyState("暂时没有内容", "回到发现页，寻找喜欢的视频", { vm.root("home") })
            }
            }
        }
    }
}

@Composable private fun MiniPlayer(video: Video, playing: Boolean, vm: AppViewModel) {
    Surface(Modifier.fillMaxWidth().clickable { vm.openPlayer() }, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(video.cover, video.title, Modifier.size(46.dp, 30.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp); Text(video.author.ifBlank { "正在播放" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { Graph.player.toggle() }, Modifier.size(40.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "继续播放") }
            IconButton(onClick = { Graph.player.stop() }, Modifier.size(36.dp)) { Icon(Icons.Default.Close, "关闭播放", Modifier.size(18.dp)) }
        }
    }
}

@Composable fun HomeScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf("推荐") }
    key(tab) { VideoFeed(vm, header = {
        Tabs(listOf("推荐", "热门", "排行榜", "直播", "追番"), tab) { tab = it }
    }, preferNewOnRefresh = tab in listOf("推荐", "热门"), load = { page -> when(tab) { "直播" -> Graph.api.liveList(page); "追番" -> Graph.api.bangumi(page); else -> Graph.api.feed(tab, page) } }) }
}

// v2 IDs from upstream PartitionCatalog; these differ from the old ranking region IDs.
val regions = listOf(0 to "全部", 1005 to "动画", 13 to "番剧", 1008 to "游戏", 1007 to "鬼畜", 1003 to "音乐", 1004 to "舞蹈", 1001 to "影视", 1002 to "娱乐", 1010 to "知识", 1012 to "科技数码", 1009 to "资讯", 1020 to "美食", 1021 to "小剧场", 1013 to "汽车", 1014 to "时尚美妆", 1018 to "体育运动", 1024 to "动物", 1029 to "vlog", 1006 to "绘画", 1011 to "人工智能", 1015 to "家装房产", 1016 to "户外潮流", 1017 to "健身", 1019 to "手工", 1022 to "旅游出行", 1023 to "三农", 1025 to "亲子", 1026 to "健康", 1027 to "情感", 1030 to "生活兴趣", 1031 to "生活经验")
@Composable fun ExploreScreen(vm: AppViewModel) {
    var region by rememberSaveable { mutableIntStateOf(0) }
    key(region) { VideoFeed(vm, header = {
        Tabs(regions.map { it.second }, regions.first { it.first == region }.second) { selected -> region = regions.first { it.second == selected }.first }
    }, load = { p -> if (region == 13) Graph.api.bangumi(p) else Graph.api.feed(if (region == 0) "热门" else "分区", p, region) }) }
}

@Composable fun Tabs(tabs: List<String>, selected: String, onClick: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tabs) { name -> Column(Modifier.heightIn(min = 46.dp).clickable { onClick(name) }.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            Text(name, fontSize = 14.sp, fontWeight = if (selected == name) FontWeight.SemiBold else FontWeight.Normal, color = if (selected == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(9.dp))
            Box(Modifier.width(18.dp).height(3.dp).clip(CircleShape).background(if (selected == name) Pink else Color.Transparent))
        } }
    }
}

@Composable fun VideoFeed(vm: AppViewModel, modifier: Modifier = Modifier, header: @Composable () -> Unit = {}, preferNewOnRefresh: Boolean = false, load: suspend (Int) -> FeedPage) {
    RefreshableVideoFeed(modifier, onVideo = { vm.openVideo(it) }, onShorts = { vm.openShorts(queue = it) }, header = header, preferNewOnRefresh = preferNewOnRefresh, load = load)
}

@Composable fun RefreshableVideoFeed(modifier: Modifier = Modifier, onVideo: (Video) -> Unit, onShorts: ((List<Video>) -> Unit)? = null, header: @Composable () -> Unit = {}, preferNewOnRefresh: Boolean = false, load: suspend (Int) -> FeedPage) {
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    val loader by rememberUpdatedState(load)
    val pager = remember { VideoFeedPager({ loader(it) }, preferNewOnRefresh = preferNewOnRefresh) }
    val content by pager.state.collectAsStateWithLifecycle()
    val s by Graph.store.settings.collectAsStateWithLifecycle()
    var displaySettings by remember { mutableStateOf(false) }
    fun fetch(reset: Boolean = false) {
        scope.launch {
            if (reset) grid.stopScroll()
            if (pager.load(reset) && reset) grid.scrollToItem(0)
        }
    }
    LaunchedEffect(Unit) { fetch() }
    LaunchedEffect(content.revision) { if (content.revision > 0) grid.scrollToItem(0) }
    if (displaySettings) AlertDialog(onDismissRequest = { displaySettings = false }, title = { Text("视频排列") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { FeedDisplaySettings(s, Graph.store::change) } }, confirmButton = { TextButton(onClick = { displaySettings = false }) { Text("完成") } })
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val columns = FeedDisplayPolicy.columns(maxWidth.value, s.feedDensity, s.feedColumns)
        val nearEnd by remember(grid, columns) { derivedStateOf { FeedDisplayPolicy.nearEnd(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, grid.layoutInfo.totalItemsCount, columns) } }
        LaunchedEffect(nearEnd, content.nextPage, content.busy, content.error, content.more) {
            if (nearEnd && content.videos.isNotEmpty() && content.more && !content.busy && content.error == null) fetch()
        }
        PullToRefreshBox(isRefreshing = content.refreshing, onRefresh = { fetch(true) }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "下拉刷新视频列表" }) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(if (s.feedDensity == 0) 12.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (s.feedDensity == 0) 16.dp else 10.dp)) {
                item(key = "feed-header", span = { GridItemSpan(maxLineSpan) }) { Column {
                    header()
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${FeedDisplayPolicy.labels[s.feedDensity]} · 每行 $columns 个", Modifier.weight(1f).padding(start = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onShorts != null && content.videos.any { it.kind == "video" }) IconButton(onClick = { onShorts(content.videos) }, Modifier.size(40.dp)) { Icon(Icons.Default.SmartDisplay, "竖屏连续播放", Modifier.size(20.dp)) }
                        IconButton(onClick = { displaySettings = true }, Modifier.size(40.dp)) { Icon(Icons.Default.GridView, "调整视频排列", Modifier.size(20.dp)) }
                        IconButton(onClick = { fetch(true) }, Modifier.size(40.dp), enabled = !content.busy) { Icon(Icons.Default.Refresh, "刷新视频列表", Modifier.size(20.dp)) }
                    }
                    if (content.refreshFailed && content.videos.isNotEmpty()) TextButton(onClick = { fetch(true) }) { Text("刷新失败，已保留原内容 · 重试", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    if (content.unchanged) Text("这次暂时没有新的推荐", Modifier.padding(4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                items(content.videos, key = { "${content.revision}:${it.key}" }) { video -> VideoCard(video, s.feedDensity) { onVideo(video) } }
                item(key = "feed-footer-${content.videos.size}", span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        content.busy -> CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp), strokeWidth = 2.dp)
                        content.error != null -> TextButton(onClick = { fetch(content.refreshFailed) }) { Text("${content.error} · 重试") }
                        content.videos.isEmpty() -> EmptyState("这里还没有内容", "试试其他分区，或下拉刷新", { fetch(true) })
                        else -> Text(if (content.more) "继续滑动，自动加载" else "已经看到这里了 · 下拉可刷新", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                } }
            }
        }
    }
}

@Composable fun VideoCard(video: Video, density: Int = 1, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(7.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(video.cover, video.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .62f)))))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (video.kind == "live") Icons.Default.Sensors else Icons.Default.PlayCircleOutline, null, Modifier.size(13.dp), Color.White); Text(" ${countLabel(video.plays)}", color = Color.White, fontSize = 10.sp) }
                Text(if (video.kind == "live") "直播中" else if (video.kind == "season") video.subtitle else timeLabel(video.duration), Modifier.clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = .28f)).padding(horizontal = 3.dp, vertical = 1.dp), color = Color.White, fontSize = 10.sp)
            }
        }
        Text(video.title, Modifier.padding(top = if (density == 0) 8.dp else 6.dp, start = 1.dp, end = 1.dp).heightIn(min = if (density == 2) 32.dp else 36.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = if (density == 0) 14.sp else if (density == 2) 12.sp else 13.sp, lineHeight = if (density == 2) 17.sp else 19.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.padding(top = 4.dp, start = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp, 11.dp).border(.7.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) { Text("UP", fontSize = 6.sp, lineHeight = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(video.author.ifBlank { if (video.kind == "season") "番剧" else "哔哩哔哩" }, Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
        }
    }
}

@Composable fun SearchScreen(vm: AppViewModel, initial: String) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf(initial) }
    var hot by remember { mutableStateOf<List<String>>(emptyList()) }
    val history by Graph.store.searchHistory.collectAsStateWithLifecycle()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun submit(value: String) {
        if (value.isBlank()) return
        keyboard?.hide()
        if (Regex("BV[0-9A-Za-z]{10}|(?i)av\\d+").containsMatchIn(value)) { vm.resolveLink(value); return }
        text = value; query = value.trim(); Graph.store.addSearch(query)
    }
    LaunchedEffect(Unit) { runCatching { Graph.api.hotSearch() }.onSuccess { hot = it } }
    Column {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), placeholder = { Text("搜视频，或粘贴 BV 号 / 视频链接", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { submit(text) }) { Icon(Icons.Default.ArrowForward, "开始搜索") } }, singleLine = true, shape = RoundedCornerShape(16.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit(text) }))
        if (query.isNotBlank()) key(query) { VideoFeed(vm, load = { p -> Graph.api.search(query, p) }) }
        else LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (history.isNotEmpty()) item { Text("最近搜索", style = MaterialTheme.typography.titleMedium); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { history.forEach { AssistChip(onClick = { submit(it) }, label = { Text(it, maxLines = 1) }) } } }
            item { Text("大家都在搜", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(hot) { i, term -> Row(Modifier.fillMaxWidth().clickable { submit(term) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("%02d".format(i + 1), Modifier.width(36.dp), color = if (i < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text(term, fontSize = 14.sp) } }
        }
    }
}

@Composable fun LoginGate(vm: AppViewModel, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PersonOutline, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary); Text(text, Modifier.padding(vertical = 22.dp), fontSize = 14.sp); Button(onClick = { vm.navigate("login") }) { Text("登录哔哩哔哩") } } }
}
@Composable fun LoadingState() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) } }
@Composable fun EmptyState(title: String, message: String, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Inbox, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(title, Modifier.padding(top = 18.dp), fontWeight = FontWeight.SemiBold); Text(message, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center); if (retry != null) TextButton(onClick = retry, Modifier.padding(top = 10.dp)) { Text("重试") } } }
}

@Composable fun FeedRoute(vm: AppViewModel, route: Route) {
    val local by Graph.store.history.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("本机记录") }
    if (route.argument == "localHistory") {
        Column { Tabs(listOf("本机记录", "云端记录"), tab) { tab = it }
            if (tab == "本机记录") {
                if (local.isEmpty()) EmptyState("还没有观看记录", "播放过的视频会自动出现在这里")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { items(local, key = { it.video.key }) { h -> CompactVideo(h.video, "上次看到 ${timeLabel(h.positionMs / 1000)}") { vm.openVideo(h.video) } } }
            } else { val user by vm.user.collectAsStateWithLifecycle(); if (user == null) LoginGate(vm, "登录后同步云端历史") else VideoFeed(vm, load = { Graph.api.cloudHistory(it) }) }
        }
    } else VideoFeed(vm, load = { p -> when {
        route.argument.startsWith("folder:") -> Graph.api.favorites(route.argument.substringAfter(':').toLong(), p)
        route.argument.startsWith("space:") -> Graph.api.uploads(route.argument.substringAfter(':').toLong(), p)
        route.argument == "myBangumi" -> Graph.api.bangumi(p, true)
        else -> Graph.api.later()
    } })
}
@Composable fun CompactVideo(video: Video, subtitle: String = video.author, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) { AsyncImage(video.cover, video.title, Modifier.width(125.dp).aspectRatio(16f / 10).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(video.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(subtitle, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
}

@Composable fun FolderScreen(vm: AppViewModel) {
    var folders by remember { mutableStateOf<List<Folder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    if (creating) CreateFolderDialog({ creating = false; tick++ }, { })
    LaunchedEffect(tick) { try { folders = Graph.api.folders(); error = null } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { vm.requireLogin { creating = true } }, Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Default.CreateNewFolder, null); Text("新建收藏夹") }
        when {
            error != null -> EmptyState("收藏夹加载失败", error!!, { tick++ })
            folders == null -> LoadingState()
            folders!!.isEmpty() -> EmptyState("还没有收藏夹", "点击上方新建收藏夹")
            else -> LazyColumn(contentPadding = PaddingValues(16.dp)) { items(folders!!, key = { it.id }) { f -> MenuRow(Icons.Default.FolderOpen, f.title, "${f.count} 个视频") { vm.navigate("feed", "folder:${f.id}", f.title) } } }
        }
    }
}

@Composable fun DynamicScreen(vm: AppViewModel) {
    var list by remember { mutableStateOf<List<DynamicItem>>(emptyList()) }; var offset by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun load(reset: Boolean = false) {
        if (busy) return
        busy = true; refreshing = reset; error = null
        scope.launch {
            try {
                val before = list; val requested = if (reset) "" else offset
                val (next, cursor) = Graph.api.dynamics(requested)
                val merged = ((if (reset) emptyList() else before) + next).distinctBy { it.id }
                list = merged
                offset = cursor.takeIf { reset || it != requested && merged.size > before.size }.orEmpty()
                loaded = true
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
            finally { busy = false; refreshing = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    val nearEnd by remember { derivedStateOf { FeedDisplayPolicy.nearEnd(scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, scroll.layoutInfo.totalItemsCount, 1) } }
    LaunchedEffect(nearEnd, offset, busy, error) { if (nearEnd && list.isNotEmpty() && offset.isNotBlank() && !busy && error == null) load() }
    PullToRefreshBox(refreshing, { load(true) }, Modifier.fillMaxSize()) {
    if (list.isEmpty()) { if (busy) LoadingState() else EmptyState("暂时没有动态", error ?: "关注 UP 主后，新动态会出现在这里", { load(true) }); return@PullToRefreshBox }
    LazyColumn(state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("关注的人，新的故事", style = MaterialTheme.typography.titleLarge); TextButton(onClick = { if (!busy) load(true) }) { Text("刷新") } } }
        items(list, key = { it.id }) { d -> Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { AsyncImage(d.avatar, d.name, Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))); Column(Modifier.padding(start = 10.dp)) { Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(d.published, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (d.text.isNotBlank()) Text(d.text, Modifier.padding(vertical = 12.dp), fontSize = 13.sp)
            d.video?.let { CompactVideo(it) { vm.openVideo(it) } }
            if (d.pictures.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(d.pictures) { picture -> AsyncImage(picture, "动态配图", Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) } }
        } } }
        item { if (busy) CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)); else if (error != null) TextButton(onClick = { load() }) { Text("重试：$error") } else Text(if (!loaded || offset.isNotBlank()) "继续滑动，自动加载" else "已经看到这里了", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    }
}
