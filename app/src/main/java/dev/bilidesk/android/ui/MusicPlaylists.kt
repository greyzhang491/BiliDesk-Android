// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import kotlinx.coroutines.*

@Composable fun PlaylistNameDialog(initial: String = "", onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (initial.isEmpty()) "新建歌单" else "重命名歌单") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("歌单名称") }, singleLine = true, enabled = !busy)
        error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(enabled = !busy && runCatching { MusicLibraryState.validName(name) }.isSuccess, onClick = { busy = true; scope.launch {
        try { onSave(name.trim()); onDismiss() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false }
    } }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable fun SaveTracksDialog(tracks: List<MusicTrack>, onDismiss: () -> Unit, onSaved: () -> Unit = onDismiss) {
    val library = Graph.player.music.library
    val state by library.state.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (create) PlaylistNameDialog(onDismiss = { create = false }, onSave = { library.create(it, tracks); onSaved() })
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("收藏到歌单") }, text = { Column {
        Text(if (tracks.size == 1) tracks.first().displayTitle else "已选 ${tracks.size} 首歌曲", maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.playlists, key = { it.id }) { playlist ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)).clickable(enabled = !busy && tracks.isNotEmpty(), onClick = {
            busy = true; scope.launch { try { library.add(playlist.id, tracks); onSaved() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false } }
        }).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlaylistPlay, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(playlist.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${playlist.tracks.size} 首", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                if (tracks.isNotEmpty() && tracks.all { t -> playlist.tracks.any { it.key == t.key } }) Icon(Icons.Default.Check, "已收藏", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    } }, confirmButton = { TextButton(enabled = !busy && tracks.isNotEmpty(), onClick = { create = true }) { Text("新建歌单") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable fun MusicBatchDialog(video: Video, vm: AppViewModel, onDismiss: () -> Unit) {
    var tracks by remember(video.key) { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var selected by remember(video.key) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }; var tick by remember { mutableIntStateOf(0) }
    var warning by remember { mutableStateOf<String?>(null) }; var save by remember { mutableStateOf(false) }
    LaunchedEffect(video.key, tick) {
        loading = true; error = null
        try {
            val detail = Graph.api.detail(video)
            tracks = MusicCollections.tracks(detail)
            selected = tracks.map { it.key }.toSet()
            val collection = detail.collection
            warning = if (collection != null && collection.declaredCount > collection.entries.size) "平台本次仅返回合集的 ${collection.entries.size}/${collection.declaredCount} 个视频，请从其他视频补充选曲。" else null
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "选曲信息读取失败" }
        finally { loading = false }
    }
    val chosen = tracks.filter { it.key in selected }
    if (save) SaveTracksDialog(chosen, { save = false }, { save = false; vm.notices.tryEmit("已保存 ${chosen.size} 首歌曲"); onDismiss() })
    AlertDialog(onDismissRequest = onDismiss, title = { Text("合集 / 分P选曲") }, text = { Column {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { tick++ }) { Text("重试") } }
        warning?.let { Text(it, fontSize = 12.sp) }
        if (!loading && error == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${chosen.size}/${tracks.size}", Modifier.weight(1f), fontSize = 12.sp)
                TextButton(onClick = { selected = tracks.map { it.key }.toSet() }) { Text("全选") }
                TextButton(onClick = { selected = tracks.map { it.key }.toSet() - selected }) { Text("反选") }
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(tracks, key = { it.key }) { track -> Row(Modifier.fillMaxWidth().clickable { selected = if (track.key in selected) selected - track.key else selected + track.key }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(track.key in selected, null); Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(track.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp); Text(track.video.author, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } } }
        }
    } }, confirmButton = { TextButton(enabled = chosen.isNotEmpty() && !loading, onClick = { save = true }) { Text("加入歌单") } }, dismissButton = { Row {
        TextButton(enabled = chosen.isNotEmpty() && !loading, onClick = { Graph.player.music.addTracks(chosen); vm.notices.tryEmit("已加入播放队列"); onDismiss() }) { Text("加入队列") }
        TextButton(onClick = onDismiss) { Text("关闭") }
    } })
}

@Composable fun LocalPlaylistRow(playlist: MusicPlaylist, vm: AppViewModel, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    if (rename) PlaylistNameDialog(playlist.name, { rename = false }, { Graph.player.music.library.rename(playlist.id, it) })
    if (delete) AlertDialog(onDismissRequest = { if (!busy) delete = false }, title = { Text("删除“${playlist.name}”？") }, text = { Text("删除本机歌单，正在播放的队列会保留。") }, confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
        try { Graph.player.music.library.delete(playlist.id); delete = false } catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "删除失败") } finally { busy = false }
    } }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { delete = false }) { Text("取消") } })
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Album, null, Modifier.size(36.dp), tint = Pink)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(playlist.name); Text("${playlist.tracks.size} 首 · 本机歌单", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(enabled = playlist.tracks.isNotEmpty(), onClick = { Graph.player.music.playPlaylist(playlist.id); vm.openMusicPlayer() }) { Icon(Icons.Default.PlayCircle, "播放歌单 ${playlist.name}") }
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "歌单选项 ${playlist.name}") }; DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text("删除歌单") }, onClick = { menu = false; delete = true })
            } }
        }
    }
}
