// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import kotlinx.coroutines.*

@Composable fun MusicPlayerScreen(vm: AppViewModel, onBack: () -> Unit = { if (!vm.back()) vm.root("music") }) {
    ImmersivePlayback(true)
    fun back() { onBack() }
    BackHandler { back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF151824)).safeDrawingPadding()) {
        val queueWidth = (maxWidth * .32f).coerceIn(260.dp, 360.dp)
        if (maxWidth >= 700.dp && maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(queueWidth).fillMaxHeight().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "收起音乐播放器", tint = Color.White) }; Text("音乐", color = Color.White, fontSize = 18.sp) }
                MusicQueuePanel(vm, Modifier.weight(1f).fillMaxWidth(), dark = true)
            }
            MusicPlayerPane(vm, Modifier.weight(1f).fillMaxHeight(), embedded = true)
        } else MusicPlayerPane(vm, Modifier.fillMaxSize(), onBack = ::back)
    }
}

@Composable fun MusicPlayerPane(vm: AppViewModel, modifier: Modifier = Modifier, embedded: Boolean = false, onBack: () -> Unit = { if (!vm.back()) vm.root("music") }) {
    val engine = Graph.player; val controller = engine.music
    val playback by engine.state.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val settings by Graph.store.settings.collectAsStateWithLifecycle()
    val library by controller.library.state.collectAsStateWithLifecycle()
    val appearance = settings.music
    val user by vm.user.collectAsStateWithLifecycle()
    val track = queue.current
    val video = if (playback.music) playback.detail?.video ?: track?.video else track?.video
    val saveTrack = if (track != null && playback.music && engine.currentEpisode() != null && video != null) MusicTrack(video, engine.currentEpisode()!!.cid, track.displayTitle) else track
    val lyrics = if (status.lyricVideoKey == video?.key) status.lyrics else emptyList()
    val scope = rememberCoroutineScope()
    var pane by rememberSaveable(queue.currentKey) { mutableIntStateOf(0) }
    var showQueue by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var favorited by remember(video?.key, user?.mid) { mutableStateOf(false) }
    var download by remember { mutableStateOf<Pair<Video, Episode>?>(null) }
    var baseColor by remember(video?.cover) { mutableStateOf(Color(0xFF343B65)) }
    val accent = if (appearance.coverColor) lerp(baseColor, Color.White, .65f) else Color(android.graphics.Color.parseColor(appearance.accent))
    val background by animateColorAsState(lerp(if (appearance.coverColor) baseColor else accent, Color.Black, appearance.darkness), tween(800), label = "封面背景")
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(pane, playback.music) { if (playback.music) engine.setMusicVideo(pane == 2) }
    LaunchedEffect(playback.musicVisual) { if (pane == 2 && !playback.musicVisual && !playback.loading && playback.error == null) pane = 0 }
    DisposableEffect(engine) { onDispose { engine.setMusicVideo(false) } }
    LaunchedEffect(video?.aid, user?.mid) { if (video != null && video.aid > 0 && user != null) runCatching { Graph.api.folders(video.aid).any { it.selected } }.onSuccess { favorited = it } }
    LaunchedEffect(Unit) { if (vm.route.value.name == "music_player" || !embedded) vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(playback.notice) { if (playback.music) playback.notice?.let { snackbar.showSnackbar(it); engine.clearNotice() } }
    if (showSettings) MusicAppearanceSheet { showSettings = false }
    if (save && saveTrack != null) SaveTracksDialog(listOf(saveTrack), { save = false }, { save = false; vm.notices.tryEmit("已加入歌单") })
    if (batch && video != null) MusicBatchDialog(video, vm) { batch = false }
    if (favorite && video != null) MusicFavoriteDialog(video, vm) { favorite = false; scope.launch { runCatching { Graph.api.folders(video.aid).any { it.selected } }.onSuccess { favorited = it } } }
    download?.let { (v, ep) -> DownloadOptionsDialog(v, ep, { download = null }, audioDefault = true, onQueued = { vm.notices.tryEmit("已加入缓存队列") }) }
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("播放队列") }, text = { MusicQueuePanel(vm, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 450.dp), onSelect = { showQueue = false }) }, confirmButton = { TextButton(onClick = { showQueue = false }) { Text("完成") } })
    fun switchFace() {
        if (!playback.music) { controller.resume(); return }
        pane = if (pane != 0) 0 else if (lyrics.isNotEmpty()) 1 else 2
    }
    fun cache() {
        val v = video ?: return
        val ep = if (playback.music) engine.currentEpisode() else null
        if (ep != null) download = v to ep
        else scope.launch { try { val d = Graph.api.detail(v); d.pages.firstOrNull()?.let { download = d.video to it } } catch (e: Exception) { if (e is CancellationException) throw e; vm.notices.tryEmit(e.message ?: "无法读取歌曲") } }
    }
    Box(modifier.background(Brush.verticalGradient(listOf(background, Color(0xFF11131D))))) {
        if (appearance.style == MusicStyle.IMMERSIVE && video?.cover?.isNotBlank() == true) AsyncImage(video.cover, null, Modifier.matchParentSize().blur(38.dp).alpha((1f - appearance.darkness) * .35f), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!embedded) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "收起音乐播放器", tint = Color.White) }
                Column(Modifier.weight(1f)) { Text("音乐", color = Color.White, fontSize = 16.sp); Text(appearance.style.label, fontSize = 10.sp, color = accent) }
                IconButton(onClick = { vm.navigate("music_library", label = "我的歌单") }) { Icon(Icons.Default.LibraryMusic, "我的歌单", tint = Color.White) }
                IconButton(onClick = { vm.navigate("music_search") }) { Icon(Icons.Default.Search, "搜索歌曲", tint = Color.White) }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Palette, "音乐播放器设置", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }, enabled = video != null) { Icon(Icons.Default.MoreVert, "当前歌曲选项", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("加入歌单") }, onClick = { menu = false; save = true })
                        DropdownMenuItem(text = { Text("合集 / 分P选曲") }, onClick = { menu = false; batch = true })
                        DropdownMenuItem(text = { Text("收藏到B站收藏夹") }, onClick = { menu = false; vm.requireLogin { favorite = true } })
                        DropdownMenuItem(text = { Text("缓存当前歌曲") }, onClick = { menu = false; cache() })
                        DropdownMenuItem(text = { Text(if (pane == 2) "返回纯音频" else "原视频画面（使用视频流量）") }, onClick = { menu = false; if (!playback.music) controller.resume(); pane = if (pane == 2) 0 else 2 })
                    }
                }
            }
            if (status.loadingPlaylist) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                Text("正在读取歌单 · ${status.loadedSongs} 首", Modifier.weight(1f).padding(start = 8.dp), color = Color.White, fontSize = 12.sp)
                TextButton(onClick = controller::cancelPlaylist) { Text("取消", color = Color.White) }
            }
            status.error?.let { Text(it, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Color.White.copy(alpha = .8f), fontSize = 12.sp) }
            if (track == null) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LibraryMusic, null, Modifier.size(84.dp), tint = Color.White.copy(alpha = .6f))
                    Text("选择一首歌开始播放", Modifier.padding(20.dp), color = Color.White)
                    FilledTonalButton(onClick = { vm.navigate("music_search") }) { Text("去搜歌") }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (pane) {
                        1 -> LyricPage(lyrics, if (playback.music) playback.position else 0, onSeek = engine::seek, modifier = Modifier.fillMaxSize(), appearance = appearance, accent = accent)
                        2 -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            MusicVideoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9))
                            if (status.lyricsLoading) Text("正在读取字幕", Modifier.padding(12.dp), color = Color.White.copy(alpha = .65f), fontSize = 11.sp)
                            if (lyrics.isNotEmpty()) TextButton(onClick = { pane = 1 }) { Text("显示歌词", color = Color.White) }
                        }
                        else -> if (appearance.style == MusicStyle.RECORD) VinylRecord(video?.cover.orEmpty(), playback.music && playback.playing && appearance.animation, ::switchFace, Modifier.fillMaxSize(), onColor = { baseColor = it }, size = appearance.artworkSize)
                            else MusicCover(video?.cover.orEmpty(), appearance, ::switchFace, Modifier.fillMaxSize()) { baseColor = it }
                    }
                }
                Row(Modifier.fillMaxWidth().widthIn(max = 600.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = ::switchFace) { Text(if (pane == 0) if (lyrics.isNotEmpty()) "歌词" else "原视频" else "唱片", color = Color.White.copy(alpha = .75f), fontSize = 11.sp) }
                }
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track.displayTitle.ifBlank { video?.title.orEmpty() }, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(video?.author.orEmpty(), Modifier.padding(top = 7.dp, bottom = 8.dp), color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1)
                    if (playback.music && playback.error != null) {
                        val needsPicture = !playback.local && playback.plan != null && playback.plan?.audio == null
                        TextButton(onClick = { if (needsPicture) pane = 2 else engine.retry() }) { Text("${playback.error} · ${if (needsPicture) "显示原视频" else "重试"}", color = Color.White, fontSize = 11.sp) }
                    }
                    MusicTransport(vm, favorited || library.playlists.any { list -> list.tracks.any { it.key == saveTrack?.key || it.key == track.key } }, onFavorite = { save = true }, onQueue = { showQueue = true }, accent = accent)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 54.dp))
    }
}

@Composable private fun VinylRecord(cover: String, playing: Boolean, onClick: () -> Unit, modifier: Modifier, onColor: (Color) -> Unit, size: Float = .85f) {
    var angle by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) { withFrameNanos { now -> angle = (angle + (now - last) / 1_000_000_000f * 12f) % 360f; last = now } }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth * size, maxHeight * size, 400.dp)
        Box(Modifier.size(diameter).graphicsLayer { rotationZ = angle }.clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF3A3C44), Color(0xFF090A0F)))).border(1.dp, Color.White.copy(alpha = .12f), CircleShape).clickable(onClick = onClick).semantics { contentDescription = "音乐唱片，点击切换歌词或原视频" }, contentAlignment = Alignment.Center) {
            for (fraction in listOf(.94f, .86f, .78f, .7f)) Box(Modifier.fillMaxSize(fraction).border(1.dp, Color.White.copy(alpha = .045f), CircleShape))
            Box(Modifier.fillMaxSize(.59f).clip(CircleShape).background(Color(0xFF63709B)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.4f), tint = Color.White.copy(alpha = .7f))
                AsyncImage(ImageRequest.Builder(context).data(cover).allowHardware(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { result ->
                    val bitmap = (result.result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) scope.launch { val color = withContext(Dispatchers.Default) { coverColor(bitmap) }; onColor(color) }
                })
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF12151F)).border(2.dp, Color.White.copy(alpha = .3f), CircleShape))
            }
        }
    }
}

@Composable private fun MusicCover(cover: String, appearance: MusicPreferences, onClick: () -> Unit, modifier: Modifier, onColor: (Color) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scale = appearance.artworkSize * if (appearance.style == MusicStyle.MINIMAL) .65f else 1f
        val diameter = minOf(maxWidth * scale, maxHeight * scale, 430.dp)
        AsyncImage(ImageRequest.Builder(context).data(cover).allowHardware(false).build(), "歌曲封面，点击切换歌词或原视频",
            Modifier.size(diameter).clip(RoundedCornerShape(if (appearance.style == MusicStyle.MINIMAL) 18.dp else 30.dp)).background(Brush.linearGradient(listOf(Color(0xFF66739F), Color(0xFF403947)))).clickable(onClick = onClick), contentScale = ContentScale.Crop,
            onSuccess = { result -> (result.result.drawable as? BitmapDrawable)?.bitmap?.let { bitmap -> scope.launch { onColor(withContext(Dispatchers.Default) { coverColor(bitmap) }) } } })
    }
}

private fun coverColor(bitmap: Bitmap): Color = runCatching {
    var red = 0L; var green = 0L; var blue = 0L; var count = 0
    val dx = (bitmap.width / 24).coerceAtLeast(1); val dy = (bitmap.height / 24).coerceAtLeast(1)
    for (y in 0 until bitmap.height step dy) for (x in 0 until bitmap.width step dx) {
        val pixel = bitmap.getPixel(x, y)
        if (android.graphics.Color.alpha(pixel) > 100) { red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); count++ }
    }
    Color((red / count.coerceAtLeast(1) * .48).toInt().coerceIn(24, 115), (green / count.coerceAtLeast(1) * .48).toInt().coerceIn(24, 115), (blue / count.coerceAtLeast(1) * .48).toInt().coerceIn(30, 125))
}.getOrDefault(Color(0xFF343B65))

@Composable private fun LyricPage(cues: List<LyricCue>, position: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, appearance: MusicPreferences, accent: Color) {
    val list = rememberLazyListState(); val active = Lyrics.current(cues, position)
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(active, follow, cues) { if (follow && active >= 0) list.animateScrollToItem(active, -(list.layoutInfo.viewportSize.height / 3)) }
    Box(modifier.semantics { contentDescription = "同步滚动歌词" }) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); follow = false } }, state = list, contentPadding = PaddingValues(vertical = 90.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            itemsIndexed(cues, key = { index, cue -> "$index:${cue.startMs}" }) { index, cue ->
                Text(Lyrics.displayText(cue.text), Modifier.fillMaxWidth().clickable { onSeek(cue.startMs); follow = true }.padding(horizontal = 12.dp), color = if (index == active) accent else Color.White.copy(alpha = .45f), fontSize = (appearance.lyricSize - if (index == active) 0 else 3).sp, lineHeight = (appearance.lyricSize * 1.5f).sp, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, textAlign = if (appearance.lyricsLeft) TextAlign.Start else TextAlign.Center)
            }
        }
        if (!follow) TextButton(onClick = { follow = true }, Modifier.align(Alignment.BottomEnd)) { Text("回到当前", color = Color.White, fontSize = 11.sp) }
    }
}

@Composable private fun MusicVideoSurface(modifier: Modifier) {
    val engine = Graph.player
    val playback by engine.state.collectAsStateWithLifecycle()
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { tag = "bilidesk_music_video"; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; PlayerViewHost.attach(this, engine.exo) } }, update = { PlayerViewHost.attach(it, engine.exo); it.keepScreenOn = playback.playing }, onRelease = { PlayerViewHost.release(it) }, modifier = modifier.background(Color.Black).semantics { contentDescription = "音乐原视频画面" })
}

@Composable private fun MusicTransport(vm: AppViewModel, favorited: Boolean, onFavorite: () -> Unit, onQueue: () -> Unit, accent: Color) {
    val engine = Graph.player; val controller = engine.music
    val playback by engine.state.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    var modeMenu by remember { mutableStateOf(false) }
    var dragging by remember(queue.currentKey) { mutableStateOf<Float?>(null) }
    val duration = if (playback.music) playback.duration.coerceAtLeast(0) else 0
    val position = if (playback.music) playback.position else 0
    Slider(value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f, onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { engine.seek((it * duration).toLong()) }; dragging = null }, enabled = playback.music && duration > 0,
        thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(accent)) },
        track = { slider -> Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val fraction = ((slider.value - slider.valueRange.start) / (slider.valueRange.endInclusive - slider.valueRange.start)).coerceIn(0f, 1f)
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = .2f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
            drawLine(accent, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = size.height, cap = StrokeCap.Round)
        } }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "音乐进度" })
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel((dragging?.let { it * duration }?.toLong() ?: position) / 1000), color = Color.White.copy(alpha = .55f), fontSize = 10.sp); Text(timeLabel(duration / 1000), color = Color.White.copy(alpha = .55f), fontSize = 10.sp) }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = { controller.previous() }, enabled = queue.current != null) { Icon(Icons.Default.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(35.dp)) }
        FilledIconButton(onClick = { controller.resume() }, Modifier.size(66.dp), enabled = queue.current != null, colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent.copy(alpha = .22f))) { Icon(if (playback.music && playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playback.music && playback.playing) "暂停音乐" else "播放音乐", tint = Color.White, modifier = Modifier.size(34.dp)) }
        IconButton(onClick = { controller.next() }, enabled = queue.current != null) { Icon(Icons.Default.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(35.dp)) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                IconButton(onClick = { modeMenu = true }, Modifier.size(48.dp)) { Icon(when (queue.mode) { MusicMode.SHUFFLE -> Icons.Default.Shuffle; MusicMode.ONE -> Icons.Default.RepeatOne; MusicMode.LOOP -> Icons.Default.Repeat; else -> Icons.Default.FormatListNumbered }, "播放模式 ${queue.mode.label}", tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(24.dp)) }
                DropdownMenu(modeMenu, { modeMenu = false }) { MusicMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label) }, onClick = { controller.mode(mode); modeMenu = false }) } }
            }
            Text(queue.mode.label, color = Color.White.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onFavorite, Modifier.size(48.dp)) { Icon(if (favorited) Icons.Default.Star else Icons.Default.StarBorder, "收藏歌曲", Modifier.size(24.dp), tint = if (favorited) accent else Color.White.copy(alpha = .8f)) }
            Text(if (favorited) "已收藏" else "收藏", color = if (favorited) accent else Color.White.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onQueue, Modifier.size(48.dp)) { Icon(Icons.Default.QueueMusic, "音乐播放队列", Modifier.size(24.dp), tint = Color.White.copy(alpha = .8f)) }
            Text("队列", color = Color.White.copy(alpha = .6f), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable fun MusicMiniPlayer(vm: AppViewModel) {
    val engine = Graph.player
    val state by engine.state.collectAsStateWithLifecycle()
    val video = state.detail?.video ?: return
    Surface(Modifier.fillMaxWidth().clickable { vm.openMusicPlayer() }, color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(video.cover, null, Modifier.size(40.dp).clip(CircleShape).background(Pink.copy(alpha = .2f)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(engine.music.queue.value.current?.displayTitle ?: video.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(video.author, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            IconButton(onClick = { engine.music.resume() }) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "暂停音乐" else "播放音乐") }
            IconButton(onClick = { engine.music.next() }) { Icon(Icons.Default.SkipNext, "下一首") }
        }
    }
}
