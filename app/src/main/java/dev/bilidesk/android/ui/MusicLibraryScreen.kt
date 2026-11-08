// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

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
import androidx.compose.ui.graphics.Color
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
import dev.bilidesk.android.player.*
import kotlinx.coroutines.*

@Composable fun MusicHomeScreen(vm: AppViewModel, split: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val libraryWidth = (maxWidth * .38f).coerceAtMost(420.dp)
        if (split && maxWidth >= 700.dp) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(libraryWidth).fillMaxHeight()) { MusicLibraryContent(vm) }
            MusicPlayerPane(vm, Modifier.weight(1f).fillMaxHeight().padding(12.dp).clip(RoundedCornerShape(24.dp)), embedded = true)
        } else MusicPlayerPane(vm, Modifier.fillMaxSize(), embedded = true)
    }
}

@Composable fun MusicLibraryContent(vm: AppViewModel) {
    val user by vm.user.collectAsStateWithLifecycle()
    val recent by Graph.player.music.recent.collectAsStateWithLifecycle()
    val caches by Graph.store.caches.collectAsStateWithLifecycle()
    val status by Graph.player.music.status.collectAsStateWithLifecycle()
    val library by Graph.player.music.library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedLocal by rememberSaveable { mutableStateOf<String?>(null) }
    var createLocal by remember { mutableStateOf(false) }
    var createCloud by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("歌单") }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    if (createLocal) PlaylistNameDialog(onDismiss = { createLocal = false }, onSave = { Graph.player.music.library.create(it) })
    if (createCloud) CreateFolderDialog({ createCloud = false; reload++ }, { })
    LaunchedEffect(user?.mid, reload) {
        if (user == null) { folders = emptyList(); return@LaunchedEffect }
        busy = true; error = null
        try { folders = Graph.api.folders() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.navigate("music_search") }) { Icon(Icons.Default.Search, "搜索歌曲") }
            IconButton(onClick = { vm.openMusicPlayer() }) { Icon(Icons.Default.Album, "打开音乐播放器") }
        }
        Tabs(listOf("歌单", "最近", "离线"), tab) { tab = it; selected = null; selectedLocal = null }
        if (status.loadingPlaylist) Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在读取歌单 · ${status.loadedSongs} 首", Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp)
            TextButton(onClick = { Graph.player.music.cancelPlaylist() }) { Text("取消") }
        }
        status.error?.let { Text(it, Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (tab == "歌单" && selectedLocal != null) {
            val playlist = library.playlists.firstOrNull { it.id == selectedLocal }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedLocal = null }) { Text("全部歌单") }
                Text(playlist?.name ?: "歌单已删除", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(enabled = playlist?.tracks?.isNotEmpty() == true, onClick = { Graph.player.music.playPlaylist(selectedLocal!!); vm.openMusicPlayer() }) { Text("播放全部") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(playlist?.tracks.orEmpty(), key = { it.key }) { track -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { MusicSongRow(track.video, vm, track.displayTitle, cid = track.cid, onPlay = { Graph.player.music.playTrack(track); vm.openMusicPlayer() }) }
                    IconButton(onClick = { scope.launch { try { Graph.player.music.library.remove(selectedLocal!!, track.key) } catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "保存失败") } } }) { Icon(Icons.Default.RemoveCircleOutline, "从歌单移除 ${track.displayTitle}") }
                } }
                if (playlist?.tracks.isNullOrEmpty()) item { Text("歌单还没有歌曲，可从搜索或合集选曲中加入。", fontSize = 13.sp) }
            }
        } else if (tab == "歌单" && selected != null) {
            val folder = folders.firstOrNull { it.id == selected }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = null }) { Text("全部歌单") }
                Text(folder?.title ?: "收藏夹", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(enabled = !status.loadingPlaylist, onClick = { Graph.player.music.playFolder(selected!!); vm.openMusicPlayer() }) { Text("播放全部") }
            }
            key(selected) { MusicPagedSongs(vm, { Graph.api.favorites(selected!!, it) }, folder) {
                folders = folders.map { if (it.id == selected) it.copy(count = (it.count - 1).coerceAtLeast(0)) else it }
            } }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                "歌单" -> {
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Text("我的歌单 · 本机", Modifier.weight(1f), fontWeight = FontWeight.Medium); TextButton(onClick = { createLocal = true }) { Text("新建歌单") } } }
                    items(library.playlists, key = { "local:${it.id}" }) { playlist -> LocalPlaylistRow(playlist, vm) { selectedLocal = playlist.id } }
                    if (library.playlists.isEmpty()) item { Text("把喜欢的歌曲或合集分P保存到多个歌单。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Text("B站收藏夹", Modifier.weight(1f), fontWeight = FontWeight.Medium); TextButton(onClick = { vm.requireLogin { createCloud = true } }) { Text("新建收藏夹") } } }
                    if (user == null) item { Text("登录后，将B站收藏夹作为歌单收听。", fontSize = 13.sp); Button(onClick = { vm.navigate("login") }) { Text("登录 B站") } }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("重新读取") } } }
                    items(folders, key = { it.id }) { folder -> Surface(Modifier.fillMaxWidth().clickable { selected = folder.id }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LibraryMusic, null, Modifier.size(36.dp), tint = Pink)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(folder.title, maxLines = 2, fontWeight = FontWeight.Medium); Text("${folder.count} 个收藏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { Graph.player.music.playFolder(folder.id); vm.openMusicPlayer() }) { Icon(Icons.Default.PlayCircle, "播放歌单 ${folder.title}") }
                            FavoriteFolderMenu(folder) { folders = folders.filterNot { it.id == folder.id } }
                        }
                    } }
                    if (!busy && user != null && folders.isEmpty() && error == null) item { Text("暂时没有收藏夹。也可以先搜索歌曲播放。", fontSize = 13.sp) }
                    item { TextButton(onClick = { vm.navigate("music_search") }) { Text("搜索歌曲") } }
                }
                "最近" -> {
                    if (recent.isEmpty()) item { Text("听过的歌曲会显示在这里。", fontSize = 13.sp) }
                    items(recent, key = { it.key }) { track -> MusicSongRow(track.video, vm, title = track.displayTitle, cid = track.cid, onPlay = { Graph.player.music.playTrack(track); vm.openMusicPlayer() }) }
                }
                else -> {
                    val audio = caches.filter { it.type == "audio" }
                    if (audio.isEmpty()) item { Text("缓存为「仅音频」的歌曲会显示在这里。", fontSize = 13.sp) }
                    items(audio, key = { it.key }) { entry -> MusicSongRow(entry.video, vm, title = entry.pageTitle.ifBlank { entry.video.title }, cid = entry.cid, onPlay = { vm.openCache(entry) }) }
                }
            }
        }
    }
}

@Composable fun MusicSearchScreen(vm: AppViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("歌曲、歌手、BV号") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { query = input.trim() }))
            IconButton(onClick = { query = input.trim() }) { Icon(Icons.Default.Search, "搜索音乐") }
        }
        if (query.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("搜索B站音乐，加入你的播放队列", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        else key(query) { MusicPagedSongs(vm, { page ->
            val bv = Regex("BV[0-9A-Za-z]{10}").find(query)?.value
            if (bv != null) FeedPage(if (page == 1) listOf(Graph.api.detail(Video(bvid = bv)).video) else emptyList(), more = false) else Graph.api.search(query, page)
        }) }
    }
}

@Composable private fun MusicPagedSongs(vm: AppViewModel, loader: suspend (Int) -> FeedPage, folder: Folder? = null, onUnfavorited: () -> Unit = {}) {
    val latest by rememberUpdatedState(loader)
    val pager = remember { VideoFeedPager({ latest(it) }, accept = { it.kind == "video" }) }
    val content by pager.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { pager.load() }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= content.videos.size - 4 } }
    LaunchedEffect(nearEnd, content.nextPage, content.busy, content.more, content.error) { if (nearEnd && !content.busy && content.more && content.error == null) scope.launch { pager.load() } }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(content.videos, key = { it.key }) { song -> MusicSongRow(song, vm, favoriteFolder = folder, onUnfavorited = { onUnfavorited(); scope.launch { pager.removeFavorite(song.key) } }) }
        item { when { content.busy -> LinearProgressIndicator(Modifier.fillMaxWidth()); content.error != null -> TextButton(onClick = { scope.launch { pager.load() } }) { Text("${content.error} · 重试") }; content.videos.isEmpty() -> Text("暂时没有歌曲", fontSize = 13.sp) } }
    }
}

@Composable fun MusicSongRow(video: Video, vm: AppViewModel, title: String = video.title, cid: Long = 0,
    favoriteFolder: Folder? = null, onUnfavorited: () -> Unit = {}, onPlay: () -> Unit = { vm.playMusic(video) }) {
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (removing && favoriteFolder != null) RemoveFavoriteDialog(video, favoriteFolder, { removing = false }, { removing = false; onUnfavorited() })
    val track = MusicTrack(video, cid, title)
    if (save) SaveTracksDialog(listOf(track), { save = false }, { save = false; vm.notices.tryEmit("已加入歌单") })
    if (batch) MusicBatchDialog(video, vm) { batch = false }
    var download by remember { mutableStateOf<Pair<Video, Episode>?>(null) }
    if (favorite) MusicFavoriteDialog(video, vm) { favorite = false }
    download?.let { (v, ep) -> DownloadOptionsDialog(v, ep, { download = null }, audioDefault = true, onQueued = { vm.notices.tryEmit("已加入音频缓存队列") }) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onPlay).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(video.cover, null, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title.ifBlank { video.bvid }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(video.author, maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "歌曲选项 $title") }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("立即播放") }, onClick = { more = false; onPlay() })
                DropdownMenuItem(text = { Text("下一首播放") }, onClick = { more = false; Graph.player.music.addTrack(track, true); vm.notices.tryEmit("已设为下一首") })
                DropdownMenuItem(text = { Text("加入播放队列") }, onClick = { more = false; Graph.player.music.addTrack(track); vm.notices.tryEmit("已加入播放队列") })
                DropdownMenuItem(text = { Text("加入歌单") }, onClick = { more = false; save = true })
                DropdownMenuItem(text = { Text("合集 / 分P选曲") }, onClick = { more = false; batch = true })
                DropdownMenuItem(text = { Text("收藏到收藏夹") }, onClick = { more = false; vm.requireLogin { favorite = true } })
                if (favoriteFolder != null) DropdownMenuItem(text = { Text("取消收藏", color = MaterialTheme.colorScheme.error) }, enabled = video.aid > 0, onClick = { more = false; removing = true })
                DropdownMenuItem(text = { Text("缓存音频") }, onClick = { more = false; scope.launch { try { val detail = Graph.api.detail(video); (detail.pages.firstOrNull { it.cid == cid } ?: detail.pages.firstOrNull())?.let { download = detail.video to it } } catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "无法读取歌曲") } } })
            }
        }
    }
}

@Composable fun MusicFavoriteDialog(video: Video, vm: AppViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val actions = remember(video.key) { ShortEngagement(Graph.api, video, scope) { vm.notices.tryEmit(it) } }
    LaunchedEffect(actions) { actions.loadFolders() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收藏到B站") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { ShortFavoritePanel(actions) } }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
