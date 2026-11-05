// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*

@Composable fun MusicAppearanceSheet(onDismiss: () -> Unit) {
    val settings by Graph.store.settings.collectAsStateWithLifecycle()
    val playback by Graph.player.state.collectAsStateWithLifecycle()
    val p = settings.music
    var custom by remember(p.accent) { mutableStateOf(p.accent) }
    fun change(block: (MusicPreferences) -> MusicPreferences) = Graph.store.change { it.copy(music = block(it.music)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("音乐播放器设置", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MusicStyle.entries.forEach { style -> FilterChip(p.style == style, onClick = { change { it.copy(style = style) } }, label = { Text(style.label) }) } }
            SwitchSetting("封面自动取色", "背景与强调色随歌曲封面变化", p.coverColor) { value -> change { it.copy(coverColor = value) } }
            Text("自定义配色", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { listOf("#F5B7CA", "#AAB8FF", "#9EDAC7", "#FFD498", "#BDDDF4").forEach { hex -> Box(Modifier.size(36.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape).clickable { change { it.copy(accent = hex, coverColor = false) } }) } }
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("颜色代码，例如 #AAB8FF") }, isError = !MusicPreferences.validColor(custom), trailingIcon = { TextButton(enabled = MusicPreferences.validColor(custom), onClick = { change { it.copy(accent = custom.uppercase(), coverColor = false) } }) { Text("应用") } })
            Text("背景深度 · ${(p.darkness * 100).toInt()}%", fontSize = 13.sp)
            Slider(p.darkness, { value -> change { it.copy(darkness = value) } }, valueRange = .25f.. .9f)
            Text("封面大小 · ${(p.artworkSize * 100).toInt()}%", fontSize = 13.sp)
            Slider(p.artworkSize, { value -> change { it.copy(artworkSize = value) } }, valueRange = .6f..1f)
            Text("歌词字号 · ${p.lyricSize.toInt()}", fontSize = 13.sp)
            Slider(p.lyricSize, { value -> change { it.copy(lyricSize = value) } }, valueRange = 16f..34f)
            SwitchSetting("歌词左对齐", "关闭时歌词居中显示", p.lyricsLeft) { value -> change { it.copy(lyricsLeft = value) } }
            SwitchSetting("唱片旋转", "暂停时自动停止旋转", p.animation) { value -> change { it.copy(animation = value) } }
            HorizontalDivider()
            Text("听歌流量", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MusicAudioQuality.entries.forEach { quality -> FilterChip(p.audioQuality == quality, onClick = { Graph.player.musicQuality(quality) }, label = { Text(quality.label) }) } }
            val bandwidth = playback.plan?.audio?.bandwidth ?: 0
            val estimate = MusicAudioPolicy.megabytesPerHour(bandwidth)
            Text(if (playback.local) "当前使用本地缓存，无需下载媒体流。" else if (estimate != null) "当前音轨约 ${bandwidth / 1000} kbps，约 ${estimate.toInt()} MB/小时。" else "音质取决于内容与账号权限，优先选用独立音轨。", fontSize = 12.sp)
            Text("只听歌时不加载视频流，也不测速视频线路。查看原视频画面会使用视频流量；封面、字幕另计。音质选项按实际可用音轨选择，省流优先最低码率。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { Graph.store.change { it.copy(music = MusicPreferences()) }; Graph.player.musicQuality(MusicAudioQuality.SAVER) }) { Text("恢复默认设置") }
        }
    }
}
