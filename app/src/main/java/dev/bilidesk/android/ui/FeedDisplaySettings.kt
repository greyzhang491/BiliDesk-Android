// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.data.Settings
import kotlin.math.floor

object FeedDisplayPolicy {
    val labels = listOf("宽松", "标准", "紧凑")
    fun columns(width: Float, density: Int, requested: Int): Int {
        val gap = if (density == 0) 12f else 8f
        val available = (width - 24f + gap).coerceAtLeast(0f)
        val limit = floor(available / (96f + gap)).toInt().coerceIn(1, 8)
        if (requested > 0) return requested.coerceIn(1, limit)
        val minimum = when (density) { 0 -> 178f; 2 -> 112f; else -> 150f }
        return floor(available / (minimum + gap)).toInt().coerceIn(1, limit)
    }
    fun nearEnd(lastVisible: Int, total: Int, columns: Int): Boolean =
        total > 0 && lastVisible >= 0 && lastVisible >= total - 1 - columns.coerceAtLeast(1) * 2
}

@Composable fun FeedDisplaySettings(s: Settings, save: ((Settings) -> Settings) -> Unit) {
    Column {
    Text("视频卡片密度", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedDisplayPolicy.labels.forEachIndexed { i, label ->
            FilterChip(selected = s.feedDensity == i, onClick = { save { current -> current.copy(feedDensity = i) } },
                modifier = Modifier.semantics { contentDescription = "卡片密度 $label" }, label = { Text(label) })
        }
    }
    Text("每行视频数量", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..8).forEach { n ->
            FilterChip(selected = s.feedColumns == n, onClick = { save { current -> current.copy(feedColumns = n) } },
                modifier = Modifier.semantics { contentDescription = if (n == 0) "每行数量 自动" else "每行数量 $n" },
                label = { Text(if (n == 0) "自动" else "$n") })
        }
    }
    Text("即时生效并保存；窄窗口会减少列数，保证标题可读。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
