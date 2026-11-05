// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(coil.annotation.ExperimentalCoilApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.Settings
import kotlinx.coroutines.*
import java.io.File

private fun storageLabel(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

@Composable fun CacheSettingsControls(s: Settings, save: ((Settings) -> Settings) -> Unit) {
    SwitchSetting("自动缓存短视频", "默认关闭；开启后仅在不超过 3 分钟的视频播放结束时下载", s.shortCache) { save { current -> current.copy(shortCache = it) } }
    SwitchSetting("自动缓存仅使用 Wi-Fi", "同时要求网络未被标记为按流量计费", s.autoCacheWifiOnly) { save { current -> current.copy(autoCacheWifiOnly = it) } }
    val limits = listOf(128, 256, 512, 1024)
    ChoiceSetting("自动缓存容量上限", limits.map { "$it MB" }, limits.indexOf(s.autoCacheMaxMb).coerceAtLeast(0)) { save { current -> current.copy(autoCacheMaxMb = limits[it]) } }
    val days = listOf(1, 3, 7, 30)
    ChoiceSetting("自动缓存保留时间", days.map { "$it 天未播放后清理" }, days.indexOf(s.autoCacheDays).coerceAtLeast(0)) { save { current -> current.copy(autoCacheDays = days[it]) } }
    Text("空间不足时先清理最久未播放的自动缓存。手动下载单独保留；正在播放的文件退出后再清理。", Modifier.padding(vertical = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun CacheStorageControls() {
    val caches by Graph.store.caches.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableLongStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { images = withContext(Dispatchers.IO) { File(context.cacheDir, "image_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    Column(Modifier.fillMaxWidth()) {
        Text("存储占用", style = MaterialTheme.typography.titleMedium)
        Text("自动缓存 ${storageLabel(caches.filter { it.automatic }.sumOf { it.bytes })}  ·  手动下载 ${storageLabel(caches.filterNot { it.automatic }.sumOf { it.bytes })}", Modifier.padding(top = 10.dp), fontSize = 12.sp)
        Text("图片缓存 ${storageLabel(images)} / 64 MB", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("普通在线播放只在内存缓冲；图片缓存有独立上限。", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !clearing, onClick = {
                clearing = true
                Graph.downloads.tasks.value.filter { it.automatic && it.running }.forEach { Graph.downloads.cancel(it.key) }
                scope.launch {
                    try { withContext(Dispatchers.IO) { Graph.store.clearAutomaticCache() }; notice = "已清理自动缓存；正在播放的文件会保留" }
                    finally { clearing = false }
                }
            }) { Text("清理自动缓存") }
            TextButton(enabled = !clearing, onClick = {
                clearing = true
                scope.launch {
                    try { context.imageLoader.memoryCache?.clear(); withContext(Dispatchers.IO) { context.imageLoader.diskCache?.clear() }; refresh++; notice = "图片缓存已清理" }
                    finally { clearing = false }
                }
            }) { Text("清理图片缓存") }
        }
        notice?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
    }
}
