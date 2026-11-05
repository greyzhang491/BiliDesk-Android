// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared by both playback layouts. Opening a thread never touches the player. */
@Composable fun VideoComments(video: Video, vm: AppViewModel) {
    val user by vm.user.collectAsStateWithLifecycle()
    var order by remember(video.key) { mutableStateOf(CommentOrder.HOT) }
    var thread by remember(video.key) { mutableStateOf<Comment?>(null) }
    val likes = remember(video.key, user?.mid) { mutableStateMapOf<Long, Comment>() }
    val writing = remember(video.key, user?.mid) { mutableStateMapOf<Long, Boolean>() }
    val scope = rememberCoroutineScope()
    var sortMenu by remember { mutableStateOf(false) }
    fun like(c: Comment) {
        if (c.id <= 0 || writing[c.id] == true) return
        vm.requireLogin {
            val account = user?.mid; val next = !c.liked; writing[c.id] = true
            scope.launch {
                try { Graph.api.likeComment(video.aid, c.id, next); if (account == vm.user.value?.mid) likes[c.id] = c.copy(liked = next, likes = (c.likes + if (next) 1 else -1).coerceAtLeast(0)) }
                catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "点赞失败，请刷新确认") }
                finally { writing.remove(c.id) }
            }
        }
    }
    BackHandler(thread != null) { thread = null }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (thread != null) TextButton(onClick = { thread = null }) { Text("返回评论") }
            Text(if (thread != null) "楼中楼" else "评论", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (thread == null) Box {
                TextButton(onClick = { sortMenu = true }) { Text("按${order.label}"); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(sortMenu, { sortMenu = false }) { CommentOrder.entries.forEach { value -> DropdownMenuItem(text = { Text(value.label) }, onClick = { order = value; sortMenu = false }) } }
            }
        }
        key(video.key, user?.mid, order, thread?.id) { CommentList(video, vm, order, thread, likes, writing, ::like, { thread = it }, Modifier.weight(1f)) }
    }
}

@Composable private fun CommentList(video: Video, vm: AppViewModel, order: CommentOrder, root: Comment?, overrides: Map<Long, Comment>, writing: Map<Long, Boolean>, onLike: (Comment) -> Unit, onThread: (Comment) -> Unit, modifier: Modifier) {
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var cursor by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState(); val scope = rememberCoroutineScope()
    fun load() {
        if (busy || !more || video.aid <= 0) return
        busy = true; error = null
        scope.launch {
            try {
                val response = Graph.api.commentPage(video.aid, cursor, order, root?.id ?: 0)
                val previousSize = comments.size
                comments = (comments + response.comments).distinctBy { it.key }; cursor = response.next
                more = response.more && comments.size > previousSize
            }
            catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "评论加载失败" }
            finally { busy = false }
        }
    }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= comments.size - 3 } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(nearEnd, cursor, busy, error) { if (nearEnd && comments.isNotEmpty() && error == null) load() }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (root != null) item("root") { CommentCard(overrides[root.id] ?: root, writing[root.id] == true, onLike, null); HorizontalDivider(Modifier.padding(top = 12.dp)) }
            items(comments, key = { it.key }) { original -> val c = overrides[original.id] ?: original; CommentCard(c, writing[c.id] == true, onLike, if (root == null) onThread else null) }
            item("footer") { when {
                busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                error != null -> TextButton(onClick = ::load) { Text("$error · 重试") }
                comments.isEmpty() -> Text(if (video.aid <= 0) "当前内容请在官方网页查看评论" else "暂时没有评论", fontSize = 13.sp)
                !more -> Text("已经看到这里了", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> TextButton(onClick = ::load) { Text("加载更多评论") }
            } }
        }
        if (root == null && video.aid > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("说点什么…", fontSize = 12.sp) }, maxLines = 3, enabled = !sending)
            IconButton(enabled = text.isNotBlank() && !sending, onClick = { vm.requireLogin {
                sending = true; val outgoing = text
                scope.launch { try { Graph.api.sendComment(video.aid, outgoing); text = ""; vm.notices.tryEmit("评论已发布") } catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "评论发送失败") } finally { sending = false } }
            } }) { Icon(Icons.Default.Send, "发布评论") }
        }
    }
}

@Composable private fun CommentCard(c: Comment, busy: Boolean, onLike: (Comment) -> Unit, onThread: ((Comment) -> Unit)?) {
    val date = remember(c.createdAt) { if (c.createdAt > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.createdAt * 1000)) else "" }
    Column {
        Text((if (c.pinned) "置顶 · " else "") + c.name, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        Text(c.text, Modifier.padding(vertical = 6.dp), fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(date, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            key(c.id, c.liked, busy) { TextButton(enabled = c.id > 0 && !busy, onClick = { onLike(c) }) { Icon(if (c.liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, if (c.liked) "取消评论点赞 ${c.id}" else "点赞评论 ${c.id}", Modifier.size(16.dp)); Text(" ${countLabel(c.likes)}", fontSize = 11.sp) } }
        }
        if (onThread != null && c.id > 0 && c.replyCount > 0) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxWidth().clickable { onThread(c) }) {
            Column(Modifier.padding(10.dp)) { c.replies.take(2).forEach { Text("${it.name}：${it.text}", fontSize = 12.sp, maxLines = 2) }; Text("查看 ${c.replyCount} 条回复", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
        }
    }
}
