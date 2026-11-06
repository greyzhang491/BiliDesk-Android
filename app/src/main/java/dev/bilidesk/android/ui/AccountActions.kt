// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
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

/** An unreadable relationship must not be presented as a confirmed unfollowed state. */
@Composable fun CreatorFollowButton(mid: Long, vm: AppViewModel, initial: Boolean? = null) {
    val user by vm.user.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var followed by remember(mid, user?.mid) { mutableStateOf<Boolean?>(initial) }
    var busy by remember(mid, user?.mid) { mutableStateOf(false) }
    var failed by remember(mid, user?.mid) { mutableStateOf(false) }
    var reload by remember(mid, user?.mid) { mutableIntStateOf(0) }
    LaunchedEffect(mid, user?.mid) { Graph.api.relationChanges.collect { change ->
        if (change.mid == mid && change.account == user?.mid?.toString()) { followed = change.followed; failed = false }
    } }
    LaunchedEffect(mid, user?.mid, reload) {
        if (user == null || mid <= 0) return@LaunchedEffect
        if (initial != null && reload == 0) { followed = initial; return@LaunchedEffect }
        busy = true; failed = false
        try { followed = Graph.api.following(mid) }
        catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
        finally { busy = false }
    }
    if (mid == user?.mid) return
    val label = when { busy -> "读取中…"; failed -> "重试关注状态"; followed == true -> "已关注"; else -> "关注" }
    key(mid, user?.mid, label) {
    OutlinedButton(enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), onClick = {
        vm.requireLogin {
            if (failed || followed == null) reload++
            else {
                val account = user?.mid; val next = followed != true; busy = true
                scope.launch {
                    try { Graph.api.follow(mid, next); if (vm.user.value?.mid == account) followed = next }
                    catch (e: Exception) { if (e is CancellationException) throw e; if (vm.user.value?.mid == account) { failed = true; vm.notices.tryEmit(e.message ?: "关注操作失败，请刷新确认") } }
                    finally { if (vm.user.value?.mid == account) busy = false }
                }
            }
        }
    }) { Text(label, fontSize = 11.sp) }
    }
}

@Composable fun CreateFolderDialog(onDismiss: () -> Unit, onCreated: (Folder) -> Unit) {
    var title by remember { mutableStateOf("") }
    var privateFolder by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("新建B站收藏夹") }, text = { Column {
        OutlinedTextField(title, { title = it; error = null }, label = { Text("名称（1～20个字）") }, singleLine = true, enabled = !busy && !uncertain)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("私密收藏夹", Modifier.weight(1f)); Switch(privateFolder, { privateFolder = it }, enabled = !busy && !uncertain) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    } }, confirmButton = { TextButton(enabled = !busy && !uncertain && runCatching { SocialModels.folderTitle(title) }.isSuccess, onClick = {
        busy = true
        scope.launch {
            try { val folder = Graph.api.createFolder(title, privateFolder); onCreated(folder); onDismiss() }
            catch (e: Exception) { if (e is CancellationException) throw e; uncertain = e is WriteOutcomeUnknown; error = if (uncertain) "创建结果尚未确认，请关闭后刷新收藏夹，避免重复创建。" else e.message ?: "创建失败" }
            finally { busy = false }
        }
    }) { Text("创建") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (uncertain) "关闭并刷新" else "取消") } })
}
