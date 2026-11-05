// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.bilidesk.android.data.Danmaku
import dev.bilidesk.android.data.Settings
import dev.bilidesk.android.player.DanmakuView

fun speedLabel(speed: Float) = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
internal fun playerPanelWidth(width: Dp) = minOf(380.dp, width * .85f)
internal fun playerPanelHeight(height: Dp) = minOf(720.dp, height * .68f)

@Composable fun HoldSpeedSettings(s: Settings, save: ((Settings) -> Settings) -> Unit) {
    SwitchSetting("启用长按倍速", "按住视频画面加速，松手或取消触摸后恢复原来的速度", s.holdSpeedEnabled) { save { current -> current.copy(holdSpeedEnabled = it) } }
    Text("长按时的播放速度", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1.25f, 1.5f, 2f, 2.5f, 3f, 4f).forEach { rate ->
            FilterChip(selected = s.holdSpeed == rate, onClick = { save { current -> current.copy(holdSpeed = rate) } }, enabled = s.holdSpeedEnabled,
                modifier = Modifier.semantics { contentDescription = "长按倍速 ${speedLabel(rate)}" }, label = { Text(speedLabel(rate)) })
        }
    }
}

@Composable fun DanmakuSettingsControls(s: Settings, save: ((Settings) -> Settings) -> Unit) {
    val sample = remember { (0..60).map { i -> Danmaku(i * 140L, when (i % 3) { 0 -> "弹幕大小与区域预览"; 1 -> "调整后立即生效"; else -> "BiliDesk" }, if (i % 9 == 0) 4 else if (i % 7 == 0) 5 else 1, if (i % 2 == 0) 0xff8eb5 else 0xffffff) } }
    val start = remember { android.os.SystemClock.uptimeMillis() }
    Text("效果预览", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AndroidView(factory = { DanmakuView(it).apply { tag = "bilidesk_danmaku_preview"; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } }, update = { it.bind(sample, s, { (android.os.SystemClock.uptimeMillis() - start) % 10000 + 1200 }, { true }) },
        modifier = Modifier.fillMaxWidth().height(100.dp).padding(vertical = 6.dp).background(Color.Black), onRelease = { it.release() })
    SwitchSetting("显示弹幕", "调整会立即作用于当前视频", s.danmaku) { save { current -> current.copy(danmaku = it) } }
    SwitchSetting("彩色弹幕", "关闭后统一使用白色", s.colored) { save { current -> current.copy(colored = it) } }
    SwitchSetting("滚动弹幕", "横向移动的弹幕", s.scroll) { save { current -> current.copy(scroll = it) } }
    SwitchSetting("顶部弹幕", "固定在顶部的弹幕", s.top) { save { current -> current.copy(top = it) } }
    SwitchSetting("底部弹幕", "固定在底部的弹幕", s.bottom) { save { current -> current.copy(bottom = it) } }
    SwitchSetting("合并重复内容", "短时间内相同内容只显示一次", s.deduplicate) { save { current -> current.copy(deduplicate = it) } }
    LiveSliderSetting("弹幕透明度", s.opacity, .2f..1f, "${(s.opacity * 100).toInt()}%") { save { current -> current.copy(opacity = it) } }
    LiveSliderSetting("弹幕显示区域", s.area, .25f..1f, "${(s.area * 100).toInt()}%") { save { current -> current.copy(area = it) } }
    LiveSliderSetting("弹幕字号", s.fontSize, 12f..30f, "${s.fontSize.toInt()} sp") { save { current -> current.copy(fontSize = it) } }
    LiveSliderSetting("弹幕速度", s.dmSpeed, .5f..2f, speedLabel(s.dmSpeed)) { save { current -> current.copy(dmSpeed = it) } }
    var keywords by remember(s.blacklist) { mutableStateOf(s.blacklist) }
    Text("关键词黑名单", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("多个词用逗号或换行分隔", fontSize = 12.sp) }, minLines = 2, shape = RoundedCornerShape(14.dp))
    TextButton(onClick = { save { current -> current.copy(blacklist = keywords) } }) { Text("应用屏蔽词") }
}

@Composable private fun LiveSliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, change: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 14.sp); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(value.coerceIn(range), change, valueRange = range, modifier = Modifier.semantics { contentDescription = title })
    }
}

/** In the player window, so changing settings never rotates or replaces the playing surface. */
@Composable fun PlayerSettingsPanel(title: String, showBack: Boolean, onBack: () -> Unit, onClose: () -> Unit, scrollContent: Boolean = true, dimAmount: Float = .35f, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAmount)).clickable(onClick = onClose))
        val wide = maxWidth > maxHeight
        val panel = if (wide) Modifier.align(Alignment.CenterEnd).width(playerPanelWidth(maxWidth)).fillMaxHeight()
                    else Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(playerPanelHeight(maxHeight))
        Surface(modifier = panel.clickable(onClick = {}).imePadding(), shape = if (wide) RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回播放设置") }
                    Text(title, Modifier.weight(1f).padding(start = if (showBack) 0.dp else 12.dp), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭播放器设置") }
                }
                HorizontalDivider()
                val scrolling = if (scrollContent) Modifier.verticalScroll(remember(title) { ScrollState(0) }) else Modifier
                Column(Modifier.weight(1f).fillMaxWidth().then(scrolling).padding(20.dp), content = content)
            }
        }
    }
}
