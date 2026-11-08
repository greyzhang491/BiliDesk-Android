// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun FavoriteFolderMenu(folder: Folder, onDeleted: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (deleting) ConfirmFavoriteDeletion(title = "删除收藏夹？", message = "将从B站账号中删除「${folder.title}」及夹内的收藏记录，无法撤销。视频原稿和其他收藏夹不受影响。",
        actionLabel = "删除收藏夹", onDismiss = { deleting = false }, onDeleted = { deleting = false; onDeleted() },
        write = { Graph.api.deleteFolder(folder.id) }, removed = { Graph.api.folders().none { it.id == folder.id } })
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "收藏夹选项 ${folder.title}") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("删除收藏夹", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
        }
    }
}

@Composable fun FavoriteVideoMenu(video: Video, folder: Folder, onRemoved: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (removing) RemoveFavoriteDialog(video, folder, { removing = false }, { removing = false; onRemoved() })
    Box {
        IconButton(onClick = { menu = true }, Modifier.size(36.dp).background(Color.Black.copy(alpha = .55f), CircleShape)) {
            Icon(Icons.Default.MoreVert, "收藏视频选项 ${video.title}", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("取消收藏") }, enabled = video.aid > 0, onClick = { menu = false; removing = true })
        }
    }
}

@Composable fun RemoveFavoriteDialog(video: Video, folder: Folder, onDismiss: () -> Unit, onRemoved: () -> Unit) {
    ConfirmFavoriteDeletion(title = "取消收藏？", message = "从「${folder.title}」移除「${video.title}」。其他收藏夹中的记录会保留。",
        actionLabel = "取消收藏", onDismiss = onDismiss, onDeleted = onRemoved,
        write = { Graph.api.favorite(video.aid, folder.id, false) }, removed = { Graph.api.folders(video.aid).none { it.id == folder.id && it.selected } })
}

/** An ambiguous network result only offers a read to check the outcome; it never repeats the write. */
@Composable private fun ConfirmFavoriteDeletion(title: String, message: String, actionLabel: String,
    onDismiss: () -> Unit, onDeleted: () -> Unit, write: suspend () -> Unit, removed: suspend () -> Boolean) {
    var busy by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun checkOutcome() {
        if (removed()) onDeleted()
        else error = "平台仍显示原记录。请稍后检查；确认未生效后可关闭此窗口重新操作。"
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, fontSize = 14.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        busy = true; error = null
        scope.launch {
            try { if (uncertain) checkOutcome() else { write(); onDeleted() } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is WriteOutcomeUnknown) {
                    uncertain = true; error = "提交结果暂未确认，正在检查平台记录…"
                    try { checkOutcome() } catch (readError: Exception) {
                        if (readError is CancellationException) throw readError
                        error = "暂时无法确认是否已生效。请检查结果，避免重复提交。"
                    }
                } else error = e.message ?: "操作失败，原记录已保留"
            } finally { busy = false }
        }
    }) { Text(if (uncertain) "检查结果" else actionLabel) } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (uncertain) "关闭" else "取消") } })
}
