// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*

fun shareVideo(context: Context, video: Video) {
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${video.title}\n${video.url}"), "分享视频")) }
}

/** A single clip's account state. Only explicit taps invoke a write; double taps do not queue writes. */
@Stable class ShortEngagement(private val api: BiliApi, val video: Video, private val scope: CoroutineScope, private val notice: (String) -> Unit) {
    var liked by mutableStateOf(false); private set
    var likeBusy by mutableStateOf(false); private set
    var folders by mutableStateOf<List<Folder>>(emptyList()); private set
    var folderBusy by mutableStateOf(false); private set
    var folderError by mutableStateOf<String?>(null); private set
    val favorited get() = folders.any { it.selected }
    fun created(folder: Folder) { folders = (folders + folder).distinctBy { it.id } }
    fun load() {
        if (!likeBusy) {
            likeBusy = true
            scope.launch {
                try { liked = api.liked(video.aid) }
                catch (e: Exception) { if (e is CancellationException) throw e }
                finally { likeBusy = false }
            }
        }
        loadFolders()
    }
    fun toggleLike() {
        if (likeBusy) return
        val next = !liked; likeBusy = true
        scope.launch {
            try { api.like(video, next); liked = next; notice(if (next) "点赞成功" else "已取消点赞") }
            catch (e: Exception) { if (e is CancellationException) throw e; notice(e.message ?: "点赞失败，请稍后重试") }
            finally { likeBusy = false }
        }
    }
    fun loadFolders() {
        if (folderBusy) return
        folderBusy = true; folderError = null
        scope.launch {
            try { folders = api.folders(video.aid) }
            catch (e: Exception) { if (e is CancellationException) throw e; folderError = e.message ?: "收藏夹加载失败" }
            finally { folderBusy = false }
        }
    }
    fun toggleFolder(folder: Folder) {
        if (folderBusy) return
        val add = !folder.selected; folderBusy = true; folderError = null
        scope.launch {
            try {
                api.favorite(video.aid, folder.id, add)
                folders = folders.map { if (it.id == folder.id) it.copy(selected = add) else it }
                notice(if (add) "已收藏到${folder.title}" else "已移出${folder.title}")
            } catch (e: Exception) { if (e is CancellationException) throw e; folderError = e.message ?: "收藏操作失败，请刷新后确认" }
            finally { folderBusy = false }
        }
    }
}

@Composable fun ShortAction(icon: ImageVector, label: String, description: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    key(label, description, active) {
    Column(Modifier.width(52.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(23.dp), tint = if (active) Pink else Color.White.copy(alpha = if (enabled) 1f else .4f))
        Text(label, Modifier.padding(top = 3.dp), color = if (active) Pink else Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
    }
    }
}

@Composable fun ShortFavoritePanel(actions: ShortEngagement) {
    var creating by remember { mutableStateOf(false) }
    if (creating) CreateFolderDialog(onDismiss = { creating = false; actions.loadFolders() }, onCreated = actions::created)
    TextButton(onClick = { creating = true }, enabled = !actions.folderBusy) { Text("新建收藏夹") }
    if (actions.folderBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
    if (actions.folders.isEmpty() && !actions.folderBusy && actions.folderError == null) Text("还没有收藏夹，可以在这里新建。", fontSize = 13.sp)
    actions.folders.forEach { folder ->
        Row(Modifier.fillMaxWidth().clickable(enabled = !actions.folderBusy) { actions.toggleFolder(folder) }.semantics { contentDescription = "收藏夹 ${folder.title}" }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(folder.selected, onCheckedChange = null)
            Text(folder.title, Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp)
        }
    }
    actions.folderError?.let { Text(it, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    TextButton(onClick = actions::loadFolders, enabled = !actions.folderBusy) { Text("刷新收藏状态") }
}
