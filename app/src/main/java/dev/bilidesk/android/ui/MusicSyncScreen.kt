// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.sync.SyncCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable fun MusicSyncScreen() {
    val sync = Graph.musicSync
    val state by sync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(true) }
    var keyVisible by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyDialog by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    if (showDevices) DeviceResumeSheet { showDevices = false }
    // Credentials and recovery keys must not be captured by screenshots, recents or screen recording.
    DisposableEffect(context) {
        val activity = context as? Activity
        val alreadySecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("断开跨设备同步？") },
        text = { Text("清除本机保存的同步授权。本机歌单、队列、进度和坚果云上的数据都会保留。") },
        confirmButton = { TextButton(onClick = { disconnect = false; scope.launch { try { sync.disconnect() } catch (_: Exception) { error = "暂时无法断开，请稍后重试" } } }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("取消") } })
    keyDialog?.let { key -> AlertDialog(onDismissRequest = { keyDialog = null }, title = { Text("连接另一台设备") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("在另一台设备选择“连接已有同步库”，使用同一个坚果云账号，并填写下面的同步密钥。请私下保存，不要发送到聊天、Issue或公开仓库。")
            OutlinedTextField(key, {}, readOnly = true, label = { Text("同步密钥") }, modifier = Modifier.fillMaxWidth())
            Text("密钥用于解密记录。卸载应用前请保存；密钥丢失无法恢复云端加密数据。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { keyDialog = null }) { Text("已妥善保存") } }) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Icon(Icons.Default.CloudSync, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text("把音乐带到另一台设备", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            Text("同步多个歌单、完整播放队列、当前歌曲与进度，以及视频观看进度。音视频缓存仍保留在各自设备。", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
        }
        item { Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.configured) {
            item { Text("坚果云账号：${state.account}\n本机名称：${state.deviceName}", lineHeight = 23.sp) }
            if (state.lastSuccess > 0) item { Text("最近同步：${DateFormat.getDateTimeInstance().format(Date(state.lastSuccess))}", fontSize = 12.sp) }
            item { CrossDeviceResumeCard() }
            item { OutlinedButton(onClick = { showDevices = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Devices, null); Text("选择设备续播记录", Modifier.padding(start = 8.dp)) } }
            item { TextButton(onClick = sync::sync, enabled = !state.busy) { Text("重试同步") } }
            item { OutlinedButton(onClick = { scope.launch { keyDialog = sync.recoveryKey() } }, modifier = Modifier.fillMaxWidth()) { Text("连接另一台设备 / 查看同步密钥") } }
            item { TextButton(onClick = { disconnect = true }, enabled = !state.busy) { Text("断开同步") } }
        } else {
            item { Text("第一次使用：创建同步库；另一台设备：连接已有同步库。两台设备使用同一个坚果云账号和同一份同步密钥。", fontSize = 13.sp, lineHeight = 21.sp) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = create, onClick = { create = true; recoveryKey = "" }, label = { Text("创建同步库") }, enabled = !state.busy)
                FilterChip(selected = !create, onClick = { create = false; recoveryKey = "" }, label = { Text("连接已有同步库") }, enabled = !state.busy)
            } }
            item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("坚果云账号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), enabled = !state.busy) }
            item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("第三方应用密码") }, supportingText = { Text("在坚果云的安全选项中生成；无需填写B站密码或坚果云登录密码。") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.busy) }
            item { OutlinedTextField(deviceName, { deviceName = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("本机名称，如“我的手机”") }, singleLine = true, enabled = !state.busy) }
            if (create) item { OutlinedButton(onClick = { recoveryKey = SyncCrypto.newKey(); keyVisible = true }, enabled = !state.busy) { Text(if (recoveryKey.isEmpty()) "生成同步密钥" else "重新生成密钥") } }
            item { OutlinedTextField(recoveryKey, { if (!create) recoveryKey = it.trim() }, Modifier.fillMaxWidth(), label = { Text(if (create) "同步密钥，请私下保存" else "填写第一台设备的同步密钥") }, readOnly = create,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "隐藏" else "显示") } }, enabled = !state.busy) }
            item { Row { Checkbox(agreed, { agreed = it }, enabled = !state.busy); Text("同意把加密的歌单、队列和进度存入自己的坚果云 BiliDeskSync 文件夹。应用密码和同步密钥仅在本机加密保存。", Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 19.sp) } }
            item { Button(enabled = agreed && !state.busy && username.isNotBlank() && password.isNotBlank() && recoveryKey.isNotBlank() && deviceName.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                error = null
                scope.launch {
                    try { sync.configure(username, password, recoveryKey, deviceName, create); if (sync.state.value.configured) { password = ""; recoveryKey = "" } }
                    catch (e: Exception) { if (e is CancellationException) throw e; error = if (e is IllegalArgumentException) e.message else sync.state.value.message }
                }
            }) { Text(if (create) "创建并开启同步" else "连接并合并已有记录") } }
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        item { Text("歌单和队列变更、暂停与切歌后自动上传；连续播放时约每30秒更新进度。打开应用、进入音乐页或网络恢复后自动检查其他设备。正常使用无需来这里点同步。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("音乐播放页右上方的设备按钮可查看每台设备各自的最近队列和进度，选择后接着听。播放时不显示续播提示；已经接过的同一份记录不重复提醒。其他设备的记录仍会保留。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TextButton(onClick = { browse(context, "https://www.jianguoyun.com/") }) { Text("注册坚果云免费账号") } }
        item { TextButton(onClick = { browse(context, "https://help.jianguoyun.com/?p=2064") }) { Text("如何生成应用密码") } }
        item { Text("免费版有流量及请求频率限制，额度不足时保留本机内容并暂停联网同步。请勿把音视频缓存放入这个同步目录。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

