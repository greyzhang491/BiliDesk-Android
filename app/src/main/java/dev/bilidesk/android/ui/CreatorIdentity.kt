// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.CancellationException

@Composable fun CreatorIdentity(video: Video, vm: AppViewModel) {
    var profile by remember(video.mid) { mutableStateOf<CreatorProfile?>(null) }
    var failed by remember(video.mid) { mutableStateOf(false) }
    var attempt by remember(video.mid) { mutableIntStateOf(0) }
    LaunchedEffect(video.mid, attempt) {
        if (video.mid <= 0) return@LaunchedEffect
        failed = false
        try { profile = Graph.api.creatorProfile(video.mid) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; failed = true }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = video.mid > 0) { vm.openCreator(video) }
                .semantics { contentDescription = "查看${video.author}的主页" }, verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(profile?.avatar?.ifBlank { video.authorAvatar } ?: video.authorAvatar, video.author, Modifier.size(44.dp))
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile?.name ?: video.author, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(profile?.fans?.let { "${countLabel(it)} 粉丝" } ?: if (failed || profile != null) "粉丝数暂不可用" else "正在加载粉丝数", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("投稿 · 搜索 · 定位当前视频", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (failed) TextButton(onClick = { attempt++ }) { Text("重试", fontSize = 11.sp) }
            if (video.mid > 0) CreatorFollowButton(video.mid, vm)
        }
    }
}
