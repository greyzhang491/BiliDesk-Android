// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import dev.bilidesk.android.Graph
import dev.bilidesk.android.R
import kotlin.math.roundToInt

/** A second surface for the same player, inside the profile's window. No re-prepare, seek or overlay permission. */
@Composable internal fun CreatorFloatingVideo(vm: AppViewModel, onReturn: () -> Unit) {
    val engine = Graph.player
    val playback by engine.state.collectAsStateWithLifecycle()
    if (playback.detail == null || playback.music) return
    val returnToVideo by rememberUpdatedState(onReturn)
    val position by vm.floatingVideoPosition.collectAsStateWithLifecycle()
    val scale by vm.floatingVideoScale.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val density = LocalDensity.current
        val ratio = (playback.plan?.video?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null }
            ?: engine.exo.videoSize.let { if (it.width > 0 && it.height > 0) it.width.toFloat() * it.pixelWidthHeightRatio / it.height else 16f / 9f }).coerceIn(.3f, 3f)
        val baseWidth = minOf(if (maxWidth >= 600.dp) 240.dp else if (ratio < 1f) 128.dp else 184.dp, maxWidth * .48f, maxHeight * .45f * ratio)
        val largest = minOf((maxWidth - 12.dp) * .9f, (maxHeight - 12.dp) * .9f * ratio).coerceAtLeast(1.dp)
        val smallest = minOf(112.dp, largest)
        val width = (baseWidth * scale).coerceIn(smallest, largest)
        val height = width / ratio
        val maxX = with(density) { (maxWidth - width - 12.dp).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - height - 12.dp).toPx().coerceAtLeast(0f) }
        val margin = with(density) { 6.dp.toPx() }
        val limits = with(density) { FloatingVideoLimits(maxWidth.toPx(), maxHeight.toPx(), ratio, smallest.toPx(), largest.toPx(), margin) }
        val rect = with(density) { FloatingVideoRect(margin + maxX * position.first, margin + maxY * position.second, width.toPx(), height.toPx()) }
        val latestGeometry by rememberUpdatedState(rect to limits)
        val latestBaseWidth by rememberUpdatedState(with(density) { baseWidth.toPx() })
        Box(Modifier.offset { IntOffset((margin + maxX * position.first).roundToInt(), (margin + maxY * position.second).roundToInt()) }
            .size(width, height).shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(12.dp))
            .semantics { contentDescription = "主页视频小窗"; onClick("回到播放页") { returnToVideo(); true } }) {
            AndroidView(factory = { context -> (LayoutInflater.from(context).inflate(R.layout.view_floating_video, null) as PlayerView).apply {
                tag = "bilidesk_profile_floating"; useController = false; PlayerViewHost.attachFloating(this, engine.exo)
            } }, update = { view -> PlayerViewHost.attachFloating(view, engine.exo); view.keepScreenOn = playback.playing },
                onRelease = PlayerViewHost::release, modifier = Modifier.fillMaxSize())
            Box(Modifier.matchParentSize()
                .pointerInput(Unit) { detectDragGestures { change, delta ->
                    change.consume()
                    val current = vm.floatingVideoPosition.value
                    val (box, bounds) = latestGeometry
                    val travelX = (bounds.width - box.width - 2 * bounds.margin).coerceAtLeast(1f)
                    val travelY = (bounds.height - box.height - 2 * bounds.margin).coerceAtLeast(1f)
                    vm.floatingVideoPosition.value = ((current.first + delta.x / travelX).coerceIn(0f, 1f) to (current.second + delta.y / travelY).coerceIn(0f, 1f))
                } }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { returnToVideo() }, onTap = { engine.toggle() }) }, contentAlignment = Alignment.Center) {
                if (!playback.playing && !playback.loading) Icon(Icons.Default.PlayArrow, null, Modifier.size(32.dp), tint = Color.White)
            }
            FloatingVideoCorner.entries.forEach { corner ->
                val coordinates = remember(corner) { arrayOfNulls<LayoutCoordinates>(1) }
                val alignment = when (corner) { FloatingVideoCorner.TOP_LEFT -> Alignment.TopStart; FloatingVideoCorner.TOP_RIGHT -> Alignment.TopEnd; FloatingVideoCorner.BOTTOM_LEFT -> Alignment.BottomStart; FloatingVideoCorner.BOTTOM_RIGHT -> Alignment.BottomEnd }
                Canvas(Modifier.align(alignment).size(30.dp).onGloballyPositioned { coordinates[0] = it }
                    .semantics { contentDescription = "缩放小窗 ${corner.label}" }.pointerInput(corner) {
                    var origin = latestGeometry.first; var movement = Offset.Zero; var touchStart = Offset.Zero
                    detectDragGestures(onDragStart = { offset ->
                        origin = latestGeometry.first; movement = Offset.Zero
                        touchStart = coordinates[0]?.takeIf { it.isAttached }?.localToWindow(offset) ?: offset
                    }) { change, delta ->
                        change.consume()
                        // The dragged corner itself moves as the window resizes. Measure in window
                        // coordinates so that layout movement cannot cancel half of the finger travel.
                        movement = coordinates[0]?.takeIf { it.isAttached }?.localToWindow(change.position)?.minus(touchStart) ?: (movement + delta)
                        val bounds = latestGeometry.second
                        val resized = FloatingVideoGeometry.resize(origin, corner, movement.x, movement.y, bounds)
                        vm.floatingVideoScale.value = resized.width / latestBaseWidth.coerceAtLeast(1f)
                        vm.floatingVideoPosition.value = ((resized.left - bounds.margin) / (bounds.width - resized.width - 2 * bounds.margin).coerceAtLeast(1f)).coerceIn(0f, 1f) to
                            ((resized.top - bounds.margin) / (bounds.height - resized.height - 2 * bounds.margin).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                }) {
                    val x = if (corner.left) 6.dp.toPx() else size.width - 6.dp.toPx()
                    val y = if (corner.top) 6.dp.toPx() else size.height - 6.dp.toPx()
                    val length = 9.dp.toPx(); val color = Color.White.copy(alpha = .7f)
                    drawLine(color, Offset(x, y), Offset(x + if (corner.left) length else -length, y), 2.dp.toPx())
                    drawLine(color, Offset(x, y), Offset(x, y + if (corner.top) length else -length), 2.dp.toPx())
                }
            }
        }
    }
}
