// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*

/** All submissions require a known count and an explicit user tap; refreshing is read-only. */
@Composable fun CoinPanel(
    video: Video,
    onLogin: () -> Unit,
    loadCount: suspend () -> Int = { Graph.api.coinCount(video) },
    submit: suspend (Int) -> Unit = { Graph.api.coin(video, it) },
    onOfficial: (() -> Unit)? = null
) {
    var coined by remember(video.key) { mutableStateOf<Int?>(null) }
    var checking by remember(video.key) { mutableStateOf(true) }
    var sending by remember(video.key) { mutableStateOf(false) }
    var failure by remember(video.key) { mutableStateOf<Exception?>(null) }
    var confirmed by remember(video.key) { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val latestLoad by rememberUpdatedState(loadCount)
    val latestSubmit by rememberUpdatedState(submit)

    suspend fun refresh() {
        checking = true; confirmed = null
        try {
            val count = latestLoad()
            require(count in 0..2) { "无法确认已投枚数，请稍后刷新。" }
            coined = count; failure = null
        } catch (e: Exception) { if (e is CancellationException) throw e; failure = e }
        finally { checking = false }
    }
    LaunchedEffect(video.key) { refresh() }

    Text("投币会使用账号硬币，请确认数量后提交。", fontSize = 13.sp)
    Text(when {
        checking -> "正在查询已投枚数…"
        failure != null -> "请先刷新状态，确认已投数量后再操作。"
        coined == 2 -> "本视频已投2枚硬币"
        coined == 1 -> "本视频已投1枚，还可投1枚"
        coined == 0 -> "本视频尚未投币"
        else -> "已投枚数尚未确认"
    }, Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    if (checking || sending) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1, 2).forEach { count ->
            Button(modifier = Modifier.weight(1f), enabled = !checking && !sending && failure == null && coined != null && coined!! + count <= 2,
                onClick = {
                    if (sending || checking || failure != null) return@Button
                    sending = true; confirmed = null; copied = false
                    scope.launch {
                        try {
                            latestSubmit(count)
                            coined = (coined!! + count).coerceAtMost(2)
                            confirmed = "投币成功，已投${count}枚；本视频共已投${coined}枚。"
                        } catch (e: Exception) { if (e is CancellationException) throw e; failure = e }
                        finally { sending = false }
                    }
                }) { Text("投${count}枚硬币") }
        }
    }
    confirmed?.let { Text(it, Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
    failure?.let { error ->
        val text = error.message.orEmpty().ifBlank { "投币操作未完成，请先刷新状态。" }.let { message ->
            if (error is ApiException && !message.contains("${error.code}")) "$message（错误码 ${error.code}）" else message
        }
        Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(14.dp)) {
                Text(text, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                Text("不会自动重新投币。请先刷新已投枚数，确认后再决定是否重试。", Modifier.padding(top = 10.dp), fontSize = 12.sp)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("BiliDesk投币错误", "视频：${video.key}\n$text"))
                    copied = true
                }) { Text(if (copied) "错误信息已复制" else "复制错误信息") }
            }
        }
        if (error is ApiException && (error.actionFailure == ActionFailure.LOGIN_REQUIRED || error.code in listOf(-101, -111))) {
            TextButton(onClick = onLogin, enabled = !checking && !sending) { Text("重新扫码登录") }
        }
    }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(enabled = !checking && !sending, onClick = { if (!checking && !sending) { checking = true; scope.launch { refresh() } } }) { Text("刷新已投枚数") }
        TextButton(enabled = !sending, onClick = { onOfficial?.invoke() ?: browse(context, video.url) }) { Text("打开官方页面") }
    }
    Text("若官方App或网页要求验证，请按官方提示完成后返回。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}
