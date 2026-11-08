// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*

@Composable fun RecommendationFeedbackDialog(video: Video, vm: AppViewModel, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, icon = { Icon(Icons.Default.NotInterested, null) }, title = { Text("不感兴趣") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(video.title, maxLines = 2, fontSize = 13.sp)
            Text("告诉我们为什么不想看", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RecommendationReason.entries.forEach { reason ->
                OutlinedButton(enabled = !busy && !uncertain && (reason != RecommendationReason.CREATOR || video.mid > 0), modifier = Modifier.fillMaxWidth(), onClick = {
                    vm.requireLogin { busy = true; vm.markNotInterested(video, reason, onSaved = onDismiss, onError = { message, unknown -> error = message; uncertain = unknown; busy = false }) }
                }) { Text(reason.label) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = {}, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(if (uncertain) "关闭" else "取消") } })
}

