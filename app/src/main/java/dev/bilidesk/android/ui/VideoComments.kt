// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.Comment
import dev.bilidesk.android.data.Video
import dev.bilidesk.android.data.countLabel
import kotlinx.coroutines.*

/** Comments are an overlay on the same player, with no pause or media reattachment. */
@Composable fun VideoComments(video: Video, vm: AppViewModel) {
    var comments by remember(video.key) { mutableStateOf<List<Comment>>(emptyList()) }
    var page by remember(video.key) { mutableIntStateOf(1) }
    var more by remember(video.key) { mutableStateOf(true) }
    var busy by remember(video.key) { mutableStateOf(false) }
    var error by remember(video.key) { mutableStateOf<String?>(null) }
    var text by remember(video.key) { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more || video.aid <= 0) return
        busy = true; error = null
        scope.launch {
            try {
                val next = Graph.api.comments(video.aid, page)
                val merged = (comments + next).distinctBy { it.name to it.text }
                more = merged.size > comments.size
                comments = merged; page++
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "评论加载失败" }
            finally { busy = false }
        }
    }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= comments.size - 3 } }
    LaunchedEffect(video.key) { load() }
    LaunchedEffect(nearEnd, page, busy, error) { if (nearEnd && comments.isNotEmpty() && error == null) load() }
    Column(Modifier.fillMaxSize()) {
        Text("视频继续播放中", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(comments) { c -> Column {
                Text(c.name, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(c.text, Modifier.padding(vertical = 6.dp), fontSize = 14.sp)
                Text("${countLabel(c.likes)} 赞", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            } }
            item {
                when {
                    busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    error != null -> TextButton(onClick = { load() }) { Text("$error · 重试") }
                    comments.isEmpty() -> Text("暂时没有评论", fontSize = 13.sp)
                    !more -> Text("已经看到这里了", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("说点什么…", fontSize = 12.sp) }, maxLines = 3)
            IconButton(onClick = { if (text.isNotBlank()) vm.requireLogin { val outgoing = text; vm.action("评论已发布") { Graph.api.sendComment(video.aid, outgoing); text = "" } } }, enabled = text.isNotBlank()) { Icon(Icons.Default.Send, "发布评论") }
        }
    }
}
