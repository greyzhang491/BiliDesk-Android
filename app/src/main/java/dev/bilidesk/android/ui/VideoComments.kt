// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class CommentFeedState {
    var comments by mutableStateOf<List<Comment>>(emptyList())
    var cursor by mutableIntStateOf(0)
    var total by mutableLongStateOf(-1)
    var more by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var started = false
    val list = LazyListState()
}

/** Shared by both playback layouts. Opening a profile or thread never changes the media. */
@Composable fun VideoComments(video: Video, vm: AppViewModel) {
    val user by vm.user.collectAsStateWithLifecycle()
    key(video.key, user?.mid) { VideoCommentsContent(video, vm) }
}

@Composable private fun VideoCommentsContent(video: Video, vm: AppViewModel) {
    var order by remember { mutableStateOf(CommentOrder.HOT) }
    var thread by remember { mutableStateOf<Comment?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val feeds = remember(order) { mutableMapOf<Long, CommentFeedState>() }
    val feed = remember(order, thread?.id, revision) { feeds.getOrPut(thread?.id ?: 0) { CommentFeedState() } }
    val overrides = remember { mutableStateMapOf<Long, Comment>() }
    val writing = remember { mutableStateMapOf<Long, Boolean>() }
    val uncertain = remember { mutableStateMapOf<Long, Boolean>() }
    var sortMenu by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<CreatorProfile?>(null) }
    var target by remember { mutableStateOf<ReplyTarget?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendUncertain by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun openUser(mid: Long, name: String, avatar: String) { if (mid > 0 || name.startsWith("@")) profile = CreatorProfile(mid, name.removePrefix("@"), avatar) }
    fun refresh() { overrides.clear(); uncertain.clear(); feeds.clear(); revision++ }
    fun react(original: Comment, positive: Boolean) {
        val c = overrides[original.id] ?: original
        if (c.id <= 0 || writing[c.id] == true || uncertain[c.id] == true) return
        vm.requireLogin {
            val on = if (positive) !c.liked else !c.disliked
            writing[c.id] = true
            scope.launch {
                try {
                    if (positive) Graph.api.likeComment(video.aid, c.id, on) else Graph.api.dislikeComment(video.aid, c.id, on)
                    overrides[c.id] = c.withReaction(positive, on)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is WriteOutcomeUnknown) uncertain[c.id] = true
                    vm.notices.tryEmit(error.message ?: "操作未成功，请刷新确认")
                } finally { writing.remove(c.id) }
            }
        }
    }
    fun reply(c: Comment) { if (c.id > 0) target = c.replyTarget(thread?.id ?: 0) }
    fun load() {
        if (feed.busy || !feed.more || video.aid <= 0) return
        val root = thread?.id ?: 0
        feed.busy = true; feed.error = null; feed.started = true
        scope.launch {
            try {
                val response = Graph.api.commentPage(video.aid, feed.cursor, order, root)
                val previousSize = feed.comments.size
                feed.comments = (feed.comments + response.comments.map { if (root > 0 && it.rootId <= 0) it.copy(rootId = root) else it }).distinctBy { it.key }
                feed.cursor = response.next; feed.total = response.total
                feed.more = response.more && feed.comments.size > previousSize
            } catch (error: Exception) { if (error is CancellationException) throw error; feed.error = error.message ?: "评论加载失败" }
            finally { feed.busy = false }
        }
    }
    val nearEnd by remember(feed) { derivedStateOf { (feed.list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= feed.comments.size - 3 } }
    LaunchedEffect(feed) { if (!feed.started) load() }
    LaunchedEffect(feed, nearEnd, feed.cursor, feed.busy, feed.error) { if (nearEnd && feed.comments.isNotEmpty() && feed.error == null) load() }
    BackHandler(thread != null && profile == null) { thread = null }
    profile?.let { seed -> CreatorProfileDialog(seed, vm, { profile = null }, { profile = null; vm.openVideo(it) }) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thread != null) IconButton(onClick = { thread = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上一级评论") }
            Text(if (thread == null) "评论" else "回复详情", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (feed.total >= 0) Text(" ${countLabel(feed.total)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) else Spacer(Modifier.weight(1f))
            if (thread == null) Box {
                TextButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)); Text("按${order.label}", Modifier.padding(start = 4.dp), fontSize = 12.sp) }
                DropdownMenu(sortMenu, { sortMenu = false }) { CommentOrder.entries.forEach { value -> DropdownMenuItem(text = { Text(value.label) }, onClick = { order = value; sortMenu = false }) } }
            }
            IconButton(enabled = !feed.busy && writing.isEmpty() && !sending, onClick = ::refresh) { Icon(Icons.Default.Refresh, "刷新评论", Modifier.size(19.dp)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = if (thread == null) "视频评论列表" else "楼中楼回复列表" }, state = feed.list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            thread?.let { root -> item("thread-root") {
                CommentCard(overrides[root.id] ?: root, video, writing[root.id] == true || uncertain[root.id] == true, ::react, ::reply, ::openUser)
                Text("全部回复", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } }
            items(feed.comments, key = { it.key }) { original ->
                val c = overrides[original.id] ?: original
                val parent = if (thread != null && c.parentId > 0 && c.parentId != thread?.id) feed.comments.find { it.id == c.parentId } ?: thread?.replies?.find { it.id == c.parentId } else null
                CommentCard(c, video, writing[c.id] == true || uncertain[c.id] == true, ::react, ::reply, ::openUser,
                    onThread = if (thread == null) { value -> thread = value } else null, replyTo = parent, overrides = overrides)
            }
            item("footer-${feed.comments.size}") { Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { when {
                feed.busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                feed.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(feed.error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error); TextButton(onClick = ::load) { Text("重试加载评论") }; TextButton(onClick = { browse(context, video.url) }) { Text("在官方页面查看") } }
                feed.comments.isEmpty() -> Text(if (video.aid <= 0) "当前内容请在官方网页查看评论" else if (thread != null) "暂时没有更多回复" else "还没有评论，来说说你的想法", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !feed.more -> Text("已经看到这里了", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> TextButton(onClick = ::load) { Text("加载更多评论") }
            } } }
        }
        if (video.aid > 0) Surface(shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val replying = target
                if (replying != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("回复 ${replying.name}", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = !sending, onClick = { target = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "取消指定回复", Modifier.size(16.dp)) }
                }
                if (sendUncertain) Text("发布结果尚未确认，请先刷新查看，避免重复发送。清空草稿后可重新编辑。", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text(if (replying == null && thread != null) "回复楼主…" else "发一条友善的评论…", fontSize = 12.sp) }, maxLines = 3, enabled = !sending && !sendUncertain, shape = RoundedCornerShape(18.dp))
                    if (sendUncertain) IconButton(onClick = { text = ""; sendUncertain = false }) { Icon(Icons.Default.Clear, "清空未确认的评论草稿") }
                    else IconButton(enabled = text.isNotBlank() && !sending, onClick = { vm.requireLogin {
                        val outgoing = text; val recipient = target ?: thread?.replyTarget(); val postingThread = recipient?.root ?: 0L
                        sending = true
                        scope.launch {
                            try { Graph.api.sendComment(video.aid, outgoing, recipient?.root ?: 0, recipient?.parent ?: 0); text = ""; target = null; feeds.remove(postingThread); revision++; vm.notices.tryEmit("已提交评论，以平台审核结果为准") }
                            catch (error: Exception) { if (error is CancellationException) throw error; sendUncertain = error is WriteOutcomeUnknown; vm.notices.tryEmit(error.message ?: "评论发送失败，草稿已保留") }
                            finally { sending = false }
                        }
                    } }) { if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Send, "发布评论") }
                }
            }
        }
    }
}

@Composable private fun CommentCard(c: Comment, video: Video, busy: Boolean, onReaction: (Comment, Boolean) -> Unit, onReply: (Comment) -> Unit,
    onUser: (Long, String, String) -> Unit, onThread: ((Comment) -> Unit)? = null, replyTo: Comment? = null, overrides: Map<Long, Comment> = emptyMap()) {
    val date = remember(c.createdAt) { if (c.createdAt in 1..253402300799L) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.createdAt * 1000)) else "" }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UserAvatar(c.avatar, c.name, Modifier.size(38.dp), if (c.mid > 0) { { onUser(c.mid, c.name, c.avatar) } } else null)
        Column(Modifier.weight(1f)) {
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 1.dp)) {
                Text(c.name, Modifier.clickable(enabled = c.mid > 0) { onUser(c.mid, c.name, c.avatar) }, color = if (c.vip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                UserLevel(c.level)
                if (video.mid > 0 && c.mid == video.mid) UserBadge("UP主")
                if (c.vip) UserBadge("大会员")
                if (c.official.isNotBlank()) UserBadge("认证", MaterialTheme.colorScheme.secondary)
            }
            if (date.isNotBlank()) Text(date, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.official.isNotBlank()) Text(c.official, Modifier.padding(top = 3.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.pinned) Text("置顶", Modifier.padding(top = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            CommentRichText(c, onUser, Modifier.padding(top = 7.dp), replyTo = replyTo)
            CommentPictures(c.pictures)
            FlowRow {
                key(c.id, c.liked, busy) { TextButton(enabled = c.id > 0 && !busy, onClick = { onReaction(c, true) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(if (c.liked) Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt, if (c.liked) "取消评论点赞 ${c.id}" else "点赞评论 ${c.id}", Modifier.size(17.dp), tint = if (c.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${countLabel(c.likes)}", fontSize = 11.sp, color = if (c.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                key(c.id, c.disliked, busy) { IconButton(enabled = c.id > 0 && !busy, onClick = { onReaction(c, false) }, modifier = Modifier.size(40.dp)) { Icon(if (c.disliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt, if (c.disliked) "取消评论点踩 ${c.id}" else "点踩评论 ${c.id}", Modifier.size(17.dp), tint = if (c.disliked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                TextButton(enabled = c.id > 0, onClick = { onReply(c) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Icon(Icons.Default.ChatBubbleOutline, "回复评论 ${c.id}", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(" 回复", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (c.location.isNotBlank()) Text(c.location, Modifier.padding(horizontal = 6.dp, vertical = 14.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { menu = true }, Modifier.size(40.dp)) { Icon(Icons.Default.MoreHoriz, "更多评论操作 ${c.id}", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("复制评论") }, onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("评论", c.text)); menu = false })
                        if (c.mid > 0) DropdownMenuItem(text = { Text("查看用户主页") }, onClick = { menu = false; onUser(c.mid, c.name, c.avatar) })
                        DropdownMenuItem(text = { Text("在官方页面查看") }, onClick = { menu = false; browse(context, "${video.url}#reply${c.id}") })
                    }
                }
            }
            if (onThread != null && c.id > 0 && c.replyCount > 0) Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    c.replies.take(3).forEach { child ->
                        val nested = overrides[child.id] ?: child
                        val parent = if (child.parentId > 0 && child.parentId != c.id) c.replies.find { it.id == child.parentId } else null
                        Column(Modifier.fillMaxWidth().clickable { onThread(c) }) { CommentRichText(nested, onUser, preview = true, replyTo = parent, maxLines = 3); if (nested.pictures.isNotEmpty()) Text("[图片]", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
                    }
                    Text("查看 ${c.replyCount} 条回复", Modifier.fillMaxWidth().clickable { onThread(c) }.padding(vertical = 5.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), thickness = .5.dp)
}
