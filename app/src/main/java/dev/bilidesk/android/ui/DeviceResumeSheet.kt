// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.timeLabel
import dev.bilidesk.android.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SyncSurface = Color(0xFF151B28)
private val SyncPanel = Color(0xFF222B3D)
private val SyncMuted = Color(0xFFA9B4C9)
private val SyncAccent = Color(0xFFF5A9C6)

@Composable private fun SyncSheetHandle() { Box(Modifier.padding(top = 12.dp, bottom = 20.dp).size(32.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f))) }

/** Both the automatic invitation and the device chooser use the same artwork, spacing and colours. */
@Composable fun DeviceResumeSheet(accent: Color = SyncAccent, onDismiss: () -> Unit) {
    val sync = Graph.musicSync
    val state by sync.state.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    rename?.let { (id, name) -> RenameSyncDeviceDialog(id, name) { rename = null } }
    LaunchedEffect(Unit) { sync.onMusicVisible() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 720.dp, containerColor = SyncSurface, contentColor = Color.White, dragHandle = { SyncSheetHandle() }) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val listHeight = (maxHeight - 190.dp).coerceIn(120.dp, 520.dp)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Devices, null, tint = accent) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("设备续播", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("挑一台设备，接着听", color = SyncMuted, fontSize = 12.sp)
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), color = accent, strokeWidth = 2.dp)
                    else IconButton(onClick = sync::sync) { Icon(Icons.Default.Refresh, "刷新设备记录", tint = SyncMuted) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .04f)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, null, Modifier.size(16.dp), tint = accent)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text("正在使用 · ${state.deviceName}", color = Color.White.copy(alpha = .9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(syncStatusText(state), color = SyncMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { rename = sync.deviceId to state.deviceName }) { Icon(Icons.Default.Edit, "重命名本机设备", Modifier.size(18.dp), tint = SyncMuted) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("其他设备", color = SyncMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${state.devices.size} 台", color = SyncMuted, fontSize = 11.sp)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.devices.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Headphones, null, Modifier.size(44.dp), tint = accent.copy(alpha = .6f))
                            Text("等另一台设备留下音乐足迹", Modifier.padding(top = 16.dp), color = Color.White, fontSize = 15.sp)
                            Text("连接同一同步库并播放歌曲后，会自动出现在这里", Modifier.padding(top = 8.dp), color = SyncMuted, fontSize = 12.sp)
                        }
                    }
                    items(state.devices, key = { it.stamp.device }) { record ->
                        ResumeDeviceCard(record, accent, onRename = { rename = record.stamp.device to record.deviceName }, onResume = { sync.resumeRemote(record); onDismiss() })
                    }
                }
            }
        }
    }
}

@Composable fun AutomaticResumeSheet(record: SyncCheckpoint, accent: Color = SyncAccent,
    onResume: () -> Unit, onLater: () -> Unit, onDevices: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    if (rename) RenameSyncDeviceDialog(record.stamp.device, record.deviceName) { rename = false }
    ModalBottomSheet(onDismissRequest = onLater, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 520.dp, containerColor = SyncSurface, contentColor = Color.White, dragHandle = { SyncSheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).semantics { contentDescription = "自动续播提示" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDone, null, Modifier.size(16.dp), tint = accent)
                Text("跨设备续播", Modifier.padding(start = 7.dp), color = accent, fontSize = 12.sp)
            }
            Text(if (record.queue.current == null) "同步这份播放队列？" else "接着上次的音乐", Modifier.padding(top = 10.dp, bottom = 6.dp), fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            Text("音乐一直在，换台设备继续", color = SyncMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 20.dp))
            ResumeDeviceCard(record, accent, showPlayButton = false, onRename = { rename = true }, onResume = onResume)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onLater, Modifier.weight(1f).height(48.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .17f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("稍后再听") }
                Button(onClick = onResume, Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = SyncSurface)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(19.dp)); Text(if (record.queue.current == null) "使用空队列" else "接着听", Modifier.padding(start = 4.dp))
                }
            }
            TextButton(onClick = onDevices, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = SyncMuted)) {
                Icon(Icons.Default.Devices, null, Modifier.size(16.dp)); Text("选择其他设备", Modifier.padding(start = 7.dp), fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun ResumeDeviceCard(record: SyncCheckpoint, accent: Color, showPlayButton: Boolean = true, onRename: () -> Unit, onResume: () -> Unit) {
    val track = record.queue.current
    Surface(Modifier.fillMaxWidth().then(if (showPlayButton) Modifier.clickable(onClick = onResume) else Modifier), shape = RoundedCornerShape(22.dp), color = SyncPanel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .055f))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(deviceIcon(record.deviceName), null, Modifier.size(15.dp), tint = accent)
                Text(record.deviceName, Modifier.weight(1f).padding(start = 6.dp), color = Color.White.copy(alpha = .9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(recordMoment(record.stamp.time), color = SyncMuted, fontSize = 11.sp)
                IconButton(onClick = onRename, Modifier.padding(start = 3.dp).size(32.dp)) { Icon(Icons.Default.Edit, "重命名设备 ${record.deviceName}", Modifier.size(15.dp), tint = SyncMuted) }
            }
            Row(Modifier.padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SyncTrackArtwork(record, accent, 64.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track?.displayTitle?.ifBlank { "未命名歌曲" } ?: "空播放队列", color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!track?.video?.author.isNullOrBlank()) Text(track!!.video.author, Modifier.padding(top = 5.dp), color = SyncMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (showPlayButton) FilledIconButton(onClick = onResume, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = SyncSurface)) { Icon(Icons.Default.PlayArrow, "从${record.deviceName}续播") }
            }
            val duration = track?.video?.duration?.times(1000) ?: 0L
            if (duration > 0) LinearProgressIndicator(progress = { (record.position.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = accent, trackColor = Color.White.copy(alpha = .1f))
            Row(Modifier.fillMaxWidth().padding(top = if (duration > 0) 8.dp else 0.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(record.position / 1000) + if (duration > 0) " / ${timeLabel(duration / 1000)}" else "", color = accent, fontSize = 12.sp)
                Text("${record.queue.tracks.size} 首 · ${record.queue.mode.label}", color = SyncMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun SyncTrackArtwork(record: SyncCheckpoint, accent: Color, size: Dp) {
    val localQueue by Graph.player.music.queue.collectAsStateWithLifecycle()
    val history by Graph.store.history.collectAsStateWithLifecycle()
    val library by Graph.player.music.library.state.collectAsStateWithLifecycle()
    val video = record.queue.current?.video
    val local = remember(video?.key, localQueue, history, library) {
        (localQueue.tracks.map { it.video } + history.map { it.video } + library.playlists.flatMap { it.tracks }.map { it.video })
            .firstOrNull { it.cover.isNotBlank() && (it.key == video?.key || it.aid > 0 && it.aid == video?.aid) }?.cover.orEmpty()
    }
    val cover = video?.cover?.ifBlank { local }.orEmpty()
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = .55f), Color(0xFF334B75)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(size * .42f), tint = Color.White.copy(alpha = .85f))
        if (cover.isNotBlank()) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

@Composable fun CrossDeviceResumeCard() {
    val state by Graph.musicSync.state.collectAsStateWithLifecycle()
    val record = state.pending ?: return
    var devices by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    if (devices) DeviceResumeSheet { devices = false }
    if (rename) RenameSyncDeviceDialog(record.stamp.device, record.deviceName) { rename = false }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResumeDeviceCard(record, SyncAccent, onRename = { rename = true }, onResume = { Graph.musicSync.resumeRemote(record) })
        Row { TextButton(onClick = { devices = true }) { Text("选择设备") }; TextButton(onClick = { Graph.musicSync.dismissRemote(record) }) { Text("暂不恢复") } }
    }
}

@Composable private fun RenameSyncDeviceDialog(device: String, initial: String, onDismiss: () -> Unit) {
    var name by remember(device) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save() { if (busy) return; busy = true; scope.launch {
        try { Graph.musicSync.renameDevice(device, name); onDismiss() }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "名称保存失败，请重试" }
        finally { busy = false }
    } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("给设备起个名字", color = Color.White) }, containerColor = SyncSurface,
        text = { Column {
            Text("名称会自动同步，方便在不同设备间辨认。", color = SyncMuted, fontSize = 13.sp)
            OutlinedTextField(name, { name = it.take(40); error = null }, Modifier.fillMaxWidth().padding(top = 16.dp), label = { Text("设备名称") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (runCatching { SyncDeviceName.validName(name) }.isSuccess) save() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = Color.White.copy(alpha = .6f), focusedBorderColor = SyncAccent, unfocusedBorderColor = SyncMuted, disabledBorderColor = SyncMuted.copy(alpha = .4f), focusedLabelColor = SyncAccent, unfocusedLabelColor = SyncMuted, cursorColor = SyncAccent))
            error?.let { Text(it, color = SyncAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        } }, confirmButton = { TextButton(enabled = !busy && runCatching { SyncDeviceName.validName(name) }.isSuccess, onClick = ::save) { Text(if (busy) "保存中…" else "保存", color = SyncAccent) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消", color = SyncMuted) } })
}

private fun deviceIcon(name: String) = when { name.contains("手机") || name.contains("phone", true) -> Icons.Default.Smartphone; name.contains("平板") || name.contains("pad", true) || name.contains("tablet", true) -> Icons.Default.TabletAndroid; else -> Icons.Default.Devices }
private fun syncStatusText(state: MusicSyncState) = when {
    state.busy -> "正在更新记录"
    state.message !in setOf("自动同步已开启", "已更新设备记录，本机修改将自动上传") -> state.message
    state.uploadPending -> "本机记录等待同步"
    state.lastSuccess > 0 -> "${recordMoment(state.lastSuccess)}更新"
    else -> "自动同步已开启"
}
private fun recordMoment(time: Long): String {
    val age = System.currentTimeMillis() - time
    return when { age in 0..59000 -> "刚刚"; age in 60000..3599999 -> "${age / 60000}分钟前"; else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(time)) }
}
