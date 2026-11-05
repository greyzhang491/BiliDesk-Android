// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.media.AudioManager
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.Settings
import dev.bilidesk.android.data.timeLabel
import dev.bilidesk.android.player.DanmakuView
import dev.bilidesk.android.player.PlaybackState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import java.lang.ref.WeakReference

/** Lazy pages may retain old native views; only the active view owns the ExoPlayer surface. */
internal object PlayerViewHost {
    internal var touchObserver: ((String) -> Unit)? = null
    private var current: WeakReference<PlayerView>? = null
    val currentView get() = current?.get()
    fun attach(view: PlayerView, exo: ExoPlayer) {
        val previous = currentView
        if (previous === view && view.player === exo) return
        if (previous != null && previous.player !== exo) previous.player = null
        PlayerView.switchTargetView(exo, previous?.takeIf { it.player === exo }, view)
        current = WeakReference(view)
    }
    fun release(view: PlayerView) {
        if (currentView === view) current = null
        view.player = null
    }
}

private fun PlayerView.fitDanmakuToVideo(exo: ExoPlayer, fill: Boolean) {
    val dm = overlayFrameLayout?.findViewWithTag<DanmakuView>("bilidesk_danmaku") ?: return
    if (width <= 0 || height <= 0) return
    val size = exo.videoSize
    val ratio = if (size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
    val w = if (!fill && ratio > 0f) minOf(width.toFloat(), height * ratio).roundToInt().coerceAtLeast(1) else width
    val h = if (!fill && ratio > 0f) minOf(height.toFloat(), width / ratio).roundToInt().coerceAtLeast(1) else height
    val current = dm.layoutParams as FrameLayout.LayoutParams
    if (current.width != w || current.height != h || current.gravity != Gravity.CENTER) dm.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
}

/** Window-only brightness is restored when leaving playback; no system-settings permission. */
@Composable fun RestorePlaybackBrightness() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val original = activity?.window?.attributes?.screenBrightness
        onDispose { if (activity != null && original != null) activity.window.attributes = activity.window.attributes.apply { screenBrightness = original } }
    }
}

@Composable fun PlayerSurface(
    state: PlaybackState, settings: Settings, fullscreen: Boolean, pip: Boolean, fill: Boolean,
    onFullscreen: (Boolean) -> Unit, onBack: () -> Unit, onPip: () -> Unit, onSettings: (String) -> Unit, onFill: () -> Unit,
    shortMode: Boolean = false, onControlsVisible: (Boolean) -> Unit = {}, onGestureActive: (Boolean) -> Unit = {}
) {
    val activity = LocalActivity.current
    val exo = Graph.player.exo
    val context = LocalContext.current
    val latestSettings by rememberUpdatedState(settings)
    val latestFill by rememberUpdatedState(fill)
    val fullscreenRequest by rememberUpdatedState(onFullscreen)
    val settingsRequest by rememberUpdatedState(onSettings)
    val controlsCallback by rememberUpdatedState(onControlsVisible)
    val gestureCallback by rememberUpdatedState(onGestureActive)
    val verticalAllowed by rememberUpdatedState(fullscreen || LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    val press = remember { PressPlaybackGesture() }
    var lockedRate by remember { mutableStateOf<Float?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var adjustment by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(!shortMode) }
    var choosingLock by remember { mutableStateOf(false) }
    var lockChoice by remember { mutableStateOf<Float?>(null) }
    fun syncGesture() {
        lockedRate = press.lockedSpeed; seekPreview = press.targetPosition
        choosingLock = press.choosingLock; lockChoice = press.lockChoice
        gestureCallback(press.active)
    }
    fun apply(effect: PressEffect) { effect.speed?.let(exo::setPlaybackSpeed); effect.seekTo?.let(Graph.player::seek); syncGesture() }
    fun resetGesture() { apply(press.reset()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(shortMode, verticalAllowed) { if (shortMode) PlayerViewHost.currentView?.hideController() }
    DisposableEffect(lifecycle, state.detail?.video?.key) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) resetGesture() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); resetGesture() }
    }
    LaunchedEffect(settings.holdSpeedEnabled) { if (!settings.holdSpeedEnabled) resetGesture() }
    LaunchedEffect(adjustment) { if (adjustment != null) { delay(1300); adjustment = null } }
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "视频播放区域" }) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply {
            tag = "bilidesk_player"
            useController = true; controllerShowTimeoutMs = 4500
            setControllerAutoShow(!shortMode)
            setControllerAnimationEnabled(!shortMode)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setShowPreviousButton(false); setShowNextButton(false)
            setShowRewindButton(true); setShowFastForwardButton(true)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
                controlsVisible = it == View.VISIBLE; controlsCallback(controlsVisible)
            })
            if (shortMode) hideController()
            PlayerViewHost.attach(this, exo)
            setFullscreenButtonClickListener { fullscreenRequest(it) }
            overlayFrameLayout?.addView(DanmakuView(ctx).apply { tag = "bilidesk_danmaku"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitDanmakuToVideo(exo, latestFill) }
            val audio = ctx.getSystemService(AudioManager::class.java)
            val density = resources.displayMetrics.density
            var downX = 0f; var downY = 0f
            var adjusting: VerticalAdjustment? = null
            var shortEdgeAdjustment = false
            var brightnessStart = .5f; var volumeStart = .5f
            var volumeMax = 1
            val detector = GestureDetector(ctx, object: GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!shortMode) return false
                    if (verticalAllowed) {
                        if (isControllerFullyVisible) hideController() else showController()
                    } else {
                        if (exo.mediaItemCount > 0) Graph.player.toggle()
                        if (exo.isPlaying) hideController() else showController()
                    }
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!exo.isCurrentMediaItemLive) Graph.player.seek(exo.currentPosition + if (e.x < width / 2f) -10000 else 10000)
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    if (exo.isCurrentMediaItemLive || exo.mediaItemCount == 0 || adjusting != null) return
                    val s = latestSettings
                    apply(press.begin(exo.playbackParameters.speed, s.holdSpeed, s.holdSpeedEnabled && exo.isPlaying, shortMode, exo.currentPosition, exo.duration))
                    parent?.requestDisallowInterceptTouchEvent(true)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    hideController()
                }
            })
            // Time-bar children keep their own drags; only the video surface is handled here.
            setOnTouchListener { _, event ->
                PlayerViewHost.touchObserver?.let { observer -> observer("time=${event.eventTime - event.downTime} view=${System.identityHashCode(this)} active=${System.identityHashCode(PlayerViewHost.currentView)} action=${event.actionMasked} x=${event.x.toInt()} y=${event.y.toInt()} size=${width}x$height vertical=$verticalAllowed edge=$shortEdgeAdjustment held=${press.active} adjust=$adjusting") }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.x; downY = event.y; adjusting = null
                    shortEdgeAdjustment = shortMode && verticalAllowed && (downX < width * .2f || downX > width * .8f)
                    // A reused page must not keep the previous held/edge gesture's interception.
                    parent?.requestDisallowInterceptTouchEvent(shortEdgeAdjustment)
                }
                detector.onTouchEvent(event)
                val dx = event.x - downX; val dy = event.y - downY
                if (event.actionMasked == MotionEvent.ACTION_MOVE && press.active) {
                    apply(press.move(dx, dy, width.toFloat(), density))
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && verticalAllowed && (!shortMode || shortEdgeAdjustment) && (adjusting != null || abs(dy) > 24 * density && abs(dy) > abs(dx) * 1.3f)) {
                    if (adjusting == null) {
                        adjusting = VerticalGesturePolicy.target(downX, width.toFloat())
                        // Keep DOWN fast so taps, paging and held gestures are not delayed by
                        // system-service calls. Read only the value the confirmed edge gesture needs.
                        if (adjusting == VerticalAdjustment.BRIGHTNESS) {
                            brightnessStart = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(.5f)
                        } else {
                            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / volumeMax
                        }
                        parent?.requestDisallowInterceptTouchEvent(true); hideController()
                    }
                    when (adjusting) {
                        VerticalAdjustment.BRIGHTNESS -> {
                            val value = VerticalGesturePolicy.value(brightnessStart, dy, height.toFloat()).coerceAtLeast(.03f)
                            activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value } }
                            adjustment = "亮度 ${(value * 100).roundToInt()}%"
                        }
                        VerticalAdjustment.VOLUME -> {
                            val max = volumeMax
                            val value = (VerticalGesturePolicy.value(volumeStart, dy, height.toFloat()) * max).roundToInt()
                            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
                            adjustment = "音量 ${(value * 100f / max).roundToInt()}%"
                        }
                        null -> Unit
                    }
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val consumed = press.active || adjusting != null
                    if (press.active) apply(press.end(event.actionMasked == MotionEvent.ACTION_CANCEL))
                    adjusting = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (consumed) return@setOnTouchListener true
                }
                shortMode || press.active
            }
        } }, update = { view ->
            PlayerViewHost.attach(view, exo)
            view.useController = !pip; view.keepScreenOn = state.playing
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.setFullscreenButtonState(fullscreen)
            view.fitDanmakuToVideo(exo, fill)
            (view.overlayFrameLayout?.findViewWithTag<View>("bilidesk_danmaku") as? DanmakuView)?.let { dm -> dm.visibility = if (!pip && settings.danmaku) View.VISIBLE else View.GONE; dm.update(state.danmaku, settings, exo) }
        }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
            resetGesture()
            (view.overlayFrameLayout?.findViewWithTag<View>("bilidesk_danmaku") as? DanmakuView)?.release()
            PlayerViewHost.release(view)
        })
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Pink, strokeWidth = 2.dp)
        state.error?.let { error -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error, color = Color.White, fontSize = 12.sp)
            Row { TextButton(onClick = { Graph.player.retry() }) { Text(if (state.local) "联网重试" else "重试", color = Pink) }; TextButton(onClick = { state.detail?.video?.url?.let { browse(context, it) } }) { Text("官方网页", color = Color.White) } }
        } }
        if ((controlsVisible || state.error != null) && !pip) Row(Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color.Black.copy(alpha = .4f)).windowInsetsPadding(WindowInsets.displayCutout), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { resetGesture(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (fullscreen && !shortMode) "退出全屏" else "返回", tint = Color.White) }
            Text(if (shortMode && !fullscreen) "短视频" else state.detail?.video?.title ?: "正在打开视频", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 12.sp)
            if (fullscreen || shortMode) IconButton(onClick = onFill) { Icon(Icons.Default.AspectRatio, if (fill) "适应画面" else "填满画面", tint = Color.White) }
            IconButton(onClick = { resetGesture(); onPip() }) { Icon(Icons.Default.PictureInPictureAlt, "画中画", tint = Color.White) }
            IconButton(onClick = { resetGesture(); settingsRequest("danmaku") }) { Icon(Icons.Default.Subtitles, "弹幕设置", tint = if (settings.danmaku) Pink else Color.White) }
            IconButton(onClick = { resetGesture(); settingsRequest("playback") }) { Icon(Icons.Default.Tune, "播放设置", tint = Color.White) }
        }
        if (choosingLock) Surface(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(92.dp).semantics { contentDescription = "选择锁定倍速" }, shape = MaterialTheme.shapes.large, color = Color.Black.copy(alpha = .88f)) {
            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("松手确定", color = Color.White.copy(alpha = .65f), fontSize = 10.sp)
                (listOf<Float?>(null) + PressPlaybackGesture.LOCK_RATES).forEach { rate ->
                    Text(if (rate == null) "恢复原速" else speedLabel(rate), Modifier.fillMaxWidth().background(if (lockChoice == rate) Pink.copy(alpha = .25f) else Color.Transparent).padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (lockChoice == rate) Pink else Color.White, fontSize = 13.sp)
                }
            }
        }
        val badge = seekPreview?.let { "${timeLabel(it / 1000)} / ${timeLabel(state.duration / 1000)} · 松手跳转" }
            ?: adjustment
        // Text/background does not intercept the native play/pause control underneath.
        if (!choosingLock) badge?.let { Text(it, Modifier.align(Alignment.Center).padding(20.dp).background(Color.Black.copy(alpha = .75f), MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 10.dp), color = Color.White, fontSize = 13.sp) }
        if (lockedRate != null && state.playing && controlsVisible && !choosingLock && !pip) {
            Text("${speedLabel(lockedRate!!)} · 已锁定", Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp).background(Color.Black.copy(alpha = .6f), MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 5.dp), color = Color.White.copy(alpha = .8f), fontSize = 10.sp)
        }
    }
}
