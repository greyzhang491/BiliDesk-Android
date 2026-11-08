// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.player.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val QueueSurface = Color(0xFF151B28)
private val QueueAccent = Color(0xFFF5A9C6)

@Composable fun MusicQueueSheet(vm: AppViewModel, accent: Color = QueueAccent, onDismiss: () -> Unit) {
    QueueDialogTheme(true, accent) { MusicQueueSheetContent(vm, accent, onDismiss) }
}

@Composable private fun MusicQueueSheetContent(vm: AppViewModel, accent: Color, onDismiss: () -> Unit) {
    val height = (LocalConfiguration.current.screenHeightDp.dp * .76f).coerceAtMost(680.dp)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 640.dp, containerColor = QueueSurface, contentColor = Color.White,
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(32.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .25f))) }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view as? DialogWindowProvider)?.window ?: (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            val oldNavigation = controller?.isAppearanceLightNavigationBars
            val oldStatus = controller?.isAppearanceLightStatusBars
            val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
            controller?.isAppearanceLightNavigationBars = false; controller?.isAppearanceLightStatusBars = false
            if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
            onDispose {
                if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
                if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
                if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null) window?.isNavigationBarContrastEnforced = oldContrast
            }
        }
        MusicQueuePanel(vm, Modifier.fillMaxWidth().height(height).navigationBarsPadding().padding(horizontal = 16.dp),
            dark = true, accent = accent, onSelect = onDismiss)
    }
}

/** Preview a reorder while dragging; persist once on drop so playback and sync stay quiet. */
@Composable fun MusicQueuePanel(vm: AppViewModel, modifier: Modifier = Modifier, dark: Boolean = false,
    accent: Color = QueueAccent, onSelect: () -> Unit = {}) {
    QueueDialogTheme(dark, accent) { MusicQueueContent(vm, modifier, dark, accent, onSelect) }
}

@Composable private fun MusicQueueContent(vm: AppViewModel, modifier: Modifier, dark: Boolean, accent: Color, onSelect: () -> Unit) {
    val controller = Graph.player.music
    val queue by controller.queue.collectAsStateWithLifecycle()
    val library by controller.library.state.collectAsStateWithLifecycle()
    val text = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = text.copy(alpha = .55f)
    val panel = if (dark) Color(0xFF222B3D) else MaterialTheme.colorScheme.surfaceVariant
    val density = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var keys by remember { mutableStateOf(queue.tracks.map { it.key }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    var save by remember { mutableStateOf<MusicTrack?>(null) }
    var favorite by remember { mutableStateOf<MusicTrack?>(null) }
    var clear by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val sourceKeys = queue.tracks.map { it.key }
    LaunchedEffect(sourceKeys) { keys = sourceKeys; dragging = null }
    val byKey = queue.tracks.associateBy { it.key }
    val tracks = keys.mapNotNull(byKey::get)
    val savedKeys = remember(library) { library.playlists.flatMap { it.tracks }.mapTo(hashSetOf()) { it.key } }
    save?.let { track -> QueueDialogTheme(dark, accent) { SaveTracksDialog(listOf(track), { save = null }, { save = null; vm.notices.tryEmit("已收藏到歌单") }) } }
    favorite?.let { track -> QueueDialogTheme(dark, accent) { MusicFavoriteDialog(track.video, vm) { favorite = null } } }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空播放队列？") },
        text = { Text("当前播放会停止，歌单中的歌曲会保留。") },
        confirmButton = { TextButton(onClick = { clear = false; controller.clear() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    fun locate(animate: Boolean) {
        val index = keys.indexOf(controller.queue.value.currentKey)
        if (index >= 0 && dragging == null) scope.launch {
            if (animate) list.animateScrollToItem(index, -24) else list.scrollToItem(index, -24)
        }
    }
    // Opening the queue starts at the current song. Later changes leave the user's scroll alone.
    LaunchedEffect(Unit) { locate(false) }
    fun moveUnderFinger() {
        val key = dragging ?: return
        val from = keys.indexOf(key)
        if (from < 0) return
        val center = dragTop + dragHeight / 2
        val visible = list.layoutInfo.visibleItemsInfo
        val target = visible.lastOrNull { it.index > from && center > it.offset + it.size / 2 }
            ?: visible.firstOrNull { it.index < from && center < it.offset + it.size / 2 }
        if (target != null && target.index in keys.indices) keys = keys.toMutableList().apply { add(target.index, removeAt(from)) }
    }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        while (isActive && dragging != null) {
            withFrameNanos { }
            val info = list.layoutInfo
            val edge = 64 * density
            val speed = when {
                dragTop < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - dragTop) / edge).coerceIn(0f, 1f)
                dragTop + dragHeight > info.viewportEndOffset - edge -> ((dragTop + dragHeight - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && list.scrollBy(speed * 12 * density) != 0f) moveUnderFinger()
        }
    }
    Column(modifier.semantics { contentDescription = "播放队列面板" }) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列", Modifier.weight(1f), color = text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { locate(true) }, enabled = queue.current != null && dragging == null,
                modifier = Modifier.semantics { contentDescription = "定位当前歌曲" }, contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = accent)) {
                Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(17.dp)); Text("定位", Modifier.padding(start = 5.dp), fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { headerMenu = true }) { Icon(Icons.Default.MoreHoriz, "播放队列选项", tint = muted) }
                DropdownMenu(headerMenu, { headerMenu = false }) {
                    DropdownMenuItem(text = { Text("清空队列") }, enabled = tracks.isNotEmpty(), onClick = { headerMenu = false; clear = true }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { modeMenu = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp), colors = ButtonDefaults.textButtonColors(contentColor = muted)) {
                    Icon(when (queue.mode) { MusicMode.SHUFFLE -> Icons.Default.Shuffle; MusicMode.ONE -> Icons.Default.RepeatOne; MusicMode.ORDER -> Icons.Default.FormatListNumbered; MusicMode.LOOP -> Icons.Default.Repeat }, null, Modifier.size(16.dp))
                    Text(queue.mode.label, Modifier.padding(start = 6.dp), fontSize = 12.sp)
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(modeMenu, { modeMenu = false }) { MusicMode.entries.forEach { mode ->
                    DropdownMenuItem(text = { Text(mode.label) }, trailingIcon = { if (mode == queue.mode) Icon(Icons.Default.Check, null) }, onClick = { controller.mode(mode); modeMenu = false })
                } }
            }
            Spacer(Modifier.weight(1f)); Text("${tracks.size} 首", color = muted, fontSize = 12.sp)
        }
        if (tracks.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.QueueMusic, null, Modifier.size(48.dp), tint = muted)
            Text("这里还没有歌曲", Modifier.padding(top = 16.dp), color = text, fontSize = 16.sp)
            Text("从歌单或搜索添加喜欢的音乐", Modifier.padding(top = 8.dp), color = muted, fontSize = 12.sp)
        }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "歌曲队列列表" }, state = list,
            contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tracks, key = { it.key }) { track ->
                val selected = track.key == queue.currentKey
                val active = track.key == dragging
                var menu by remember { mutableStateOf(false) }
                val nextIndex = queue.nextUp.indexOf(track.key)
                val saved = track.key in savedKeys
                val motion = if (active) Modifier.zIndex(2f).graphicsLayer {
                    val offset = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.key }?.offset ?: dragTop.toInt()
                    translationY = dragTop - offset; scaleX = 1.025f; scaleY = 1.025f; shadowElevation = 9 * density; shape = RoundedCornerShape(16.dp)
                } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = 550f))
                Row(Modifier.fillMaxWidth().then(motion).clip(RoundedCornerShape(16.dp))
                    .background(if (active) panel else if (selected) accent.copy(alpha = .12f) else Color.Transparent)
                    .clickable(enabled = dragging == null) { controller.playTrack(track); onSelect() }
                    .padding(vertical = 10.dp, horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DragHandle, "拖动排序 ${track.displayTitle}", Modifier.size(30.dp).pointerInput(track.key) {
                        detectDragGesturesAfterLongPress(onDragStart = {
                            list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.key }?.let { item ->
                                dragTop = item.offset.toFloat(); dragHeight = item.size.toFloat(); dragging = track.key
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }, onDrag = { change, delta -> if (dragging == track.key) { change.consume(); dragTop += delta.y; moveUnderFinger() } },
                            onDragEnd = { if (dragging == track.key) { dragging = null; controller.reorder(keys) } },
                            onDragCancel = { dragging = null; keys = controller.queue.value.tracks.map { it.key } })
                    }, tint = muted.copy(alpha = .65f))
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(panel), contentAlignment = Alignment.Center) {
                        Icon(if (selected) Icons.Default.GraphicEq else Icons.Default.MusicNote, null, Modifier.size(22.dp), tint = if (selected) accent else muted)
                        if (track.video.cover.isNotBlank()) AsyncImage(track.video.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp, end = 4.dp)) {
                        Text(track.displayTitle, color = if (selected) accent else text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 2, lineHeight = 20.sp, overflow = TextOverflow.Ellipsis)
                        val subtitle = listOfNotNull(if (selected) "正在播放" else if (nextIndex == 0) "下一首" else if (nextIndex > 0) "待播 ${nextIndex + 1}" else null,
                            track.video.author.takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 4.dp), color = if (selected || nextIndex >= 0) accent.copy(alpha = .85f) else muted,
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { save = track }, Modifier.size(36.dp), enabled = dragging == null) {
                        Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏到歌单 ${track.displayTitle}", Modifier.size(19.dp), tint = if (saved) accent else muted)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, Modifier.size(36.dp), enabled = dragging == null) { Icon(Icons.Default.MoreVert, "歌曲选项 ${track.displayTitle}", Modifier.size(20.dp), tint = muted) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("下一首播放") }, enabled = !selected, onClick = { menu = false; controller.playNext(track.key); vm.notices.tryEmit("已设为下一首") }, leadingIcon = { Icon(Icons.Default.PlaylistPlay, null) })
                            DropdownMenuItem(text = { Text("收藏到歌单") }, onClick = { menu = false; save = track }, leadingIcon = { Icon(Icons.Default.FavoriteBorder, null) })
                            DropdownMenuItem(text = { Text("收藏到B站") }, onClick = { menu = false; favorite = track }, leadingIcon = { Icon(Icons.Default.BookmarkBorder, null) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("上移一首") }, onClick = { menu = false; val from = controller.queue.value.tracks.indexOfFirst { it.key == track.key }; controller.move(from, (from - 1).coerceAtLeast(0)) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) })
                            DropdownMenuItem(text = { Text("从队列移除") }, onClick = { menu = false; controller.remove(track.key) }, leadingIcon = { Icon(Icons.Default.PlaylistRemove, null) })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun QueueDialogTheme(dark: Boolean, accent: Color, content: @Composable () -> Unit) {
    if (dark) MaterialTheme(colorScheme = darkColorScheme(primary = accent, surface = QueueSurface, onSurface = Color.White,
        surfaceVariant = Color(0xFF222B3D), onSurfaceVariant = Color(0xFFA9B4C9)), content = content)
    else content()
}
