// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import dev.bilidesk.android.download.downloadSize
import kotlinx.coroutines.CancellationException

@Composable fun DownloadOptionsDialog(video: Video, episode: Episode, onDismiss: () -> Unit, audioDefault: Boolean = false, onQueued: () -> Unit = {}) {
    val prefs = LocalContext.current.getSharedPreferences("download_options", Context.MODE_PRIVATE)
    var audio by remember { mutableStateOf(audioDefault || prefs.getBoolean("audio", false)) }
    var quality by remember { mutableIntStateOf(prefs.getInt("quality", 80)) }
    var plan by remember { mutableStateOf<PlayPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(video.key, episode.cid, attempt) {
        error = null; plan = null
        try {
            val available = Graph.api.play(video, episode, 0, forceAvc = true)
            plan = available
            if (quality !in available.qualities) quality = available.qualities.filter { it <= quality }.maxOrNull() ?: available.qualities.firstOrNull() ?: available.video.quality
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "读取可用画质失败" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("缓存选项") }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        Text(episode.title.ifBlank { video.title }, maxLines = 2, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!audio, { audio = false }, label = { Text("视频（带声音）") })
            FilterChip(audio, { audio = true }, label = { Text("仅音频") })
        }
        if (plan == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
        if (!audio && plan != null) {
            Text("画质", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                plan!!.qualities.forEach { q -> FilterChip(quality == q, { quality = q }, label = { Text(Quality.label(q)) }, modifier = Modifier.semantics { contentDescription = "缓存画质 ${Quality.label(q)}" }) }
            }
        }
        if (audio) Text("保存为音频，可在音乐模式中离线收听。", Modifier.padding(vertical = 12.dp), fontSize = 12.sp)
        plan?.let { p ->
            val bandwidth = if (audio) p.audio?.bandwidth ?: p.video.bandwidth else p.video.bandwidth + (p.audio?.bandwidth ?: 0)
            if (bandwidth > 0 && (audio || quality == p.video.quality)) Text("预计 ${downloadSize(bandwidth * episode.duration / 8)}", fontSize = 12.sp)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp); TextButton(onClick = { attempt++ }) { Text("重新读取") } }
    } }, confirmButton = { TextButton(enabled = plan != null, onClick = {
        prefs.edit().putBoolean("audio", audio).putInt("quality", quality).apply()
        Graph.downloads.enqueue(video, episode, quality = quality, audioOnly = audio)
        onQueued(); onDismiss()
    }) { Text("开始缓存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
