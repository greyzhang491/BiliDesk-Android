// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.CancellationException

/** A full-window profile keeps the comments composition and its exact scroll position alive. */
@Composable fun CreatorProfileDialog(seed: CreatorProfile, vm: AppViewModel, onDismiss: () -> Unit, onVideo: (Video) -> Unit, current: Video? = null, onSelection: ((Video, List<Video>, Int, String) -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        var chosen by remember(seed.mid, seed.name) { mutableStateOf(seed.takeIf { it.mid > 0 }) }
        LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
        Scaffold(containerColor = MaterialTheme.colorScheme.surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = { Text(if (chosen == null) "查找 @${seed.name}" else "用户主页", fontSize = 18.sp) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭用户主页") } }, actions = {
                chosen?.let { current -> IconButton(onClick = { browse(context, "https://space.bilibili.com/${current.mid}") }) { Icon(Icons.Default.OpenInNew, "在官方页面打开用户主页") } }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }) { padding ->
            val selectedProfile = chosen
            if (selectedProfile == null) MentionUserLookup(seed.name, Modifier.fillMaxSize().padding(padding)) { chosen = it }
            else key(selectedProfile.mid) { CreatorScreen(selectedProfile, vm, Modifier.fillMaxSize().padding(padding), onVideo, current = current, onSelection = onSelection) }
        }
    }
}

/** Nickname-only mentions need a real lookup; ambiguous names never fabricate a UID. */
@Composable private fun MentionUserLookup(name: String, modifier: Modifier, onUser: (CreatorProfile) -> Unit) {
    var users by remember(name) { mutableStateOf<List<CreatorProfile>?>(null) }
    var error by remember(name) { mutableStateOf<String?>(null) }
    var attempt by remember(name) { mutableIntStateOf(0) }
    LaunchedEffect(name, attempt) {
        users = null; error = null
        try {
            val result = Graph.api.searchCreators(name)
            val exact = result.filter { it.name == name }
            if (exact.size == 1) onUser(exact.single()) else users = result.sortedByDescending { it.name == name }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "用户查找失败" }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("这条评论没有提供被提及用户的UID。昵称可能重名或已更改，请核对头像和UID。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error); TextButton(onClick = { attempt++ }) { Text("重试查找用户") } }
        else if (users == null) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        else if (users!!.isEmpty()) item { Text("暂未找到这个用户，可稍后重试。", fontSize = 13.sp); TextButton(onClick = { attempt++ }) { Text("重新查找") } }
        else items(users!!, key = { it.mid }) { user ->
            Row(Modifier.fillMaxWidth().clickable { onUser(user) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(user.avatar, user.name, Modifier.size(48.dp))
                Column(Modifier.weight(1f)) { Text(user.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium); Text("UID ${user.mid}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); if (user.sign.isNotBlank()) Text(user.sign, fontSize = 12.sp, maxLines = 2) }
            }
        }
    }
}

@Composable fun CreatorScreen(seed: CreatorProfile, vm: AppViewModel, modifier: Modifier = Modifier, onVideo: (Video) -> Unit = vm::openVideo, current: Video? = null, onSelection: ((Video, List<Video>, Int, String) -> Unit)? = null) {
    var profile by remember(seed.mid) { mutableStateOf(seed) }
    var loaded by remember(seed.mid) { mutableStateOf(false) }
    var error by remember(seed.mid) { mutableStateOf<String?>(null) }
    var attempt by remember(seed.mid) { mutableIntStateOf(0) }
    LaunchedEffect(seed.mid, attempt) {
        loaded = false; error = null
        try { profile = Graph.api.creatorProfile(seed.mid).let { it.copy(avatar = it.avatar.ifBlank { seed.avatar }) } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "资料暂时无法加载" }
        finally { loaded = true }
    }
    val playback by Graph.player.state.collectAsStateWithLifecycle()
    val playing = (current ?: playback.detail?.video)?.takeIf { it.mid == seed.mid }
    var searchText by remember(seed.mid) { mutableStateOf("") }
    var query by remember(seed.mid) { mutableStateOf("") }
    var basePage by remember(seed.mid) { mutableIntStateOf(1) }
    var located by remember(seed.mid) { mutableStateOf<LocatedUpload?>(null) }
    var locationRevision by remember(seed.mid) { mutableIntStateOf(0) }
    var locating by remember(seed.mid) { mutableStateOf(false) }
    var locationMessage by remember(seed.mid) { mutableStateOf<String?>(null) }
    var reveal by remember(seed.mid) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    fun search() { keyboard?.hide(); query = searchText.trim(); basePage = 1; located = null; reveal = ""; locationMessage = null }
    fun locate() {
        if (playing == null || locating) return
        keyboard?.hide(); locating = true; locationMessage = null
        scope.launch {
            try {
                val match = withTimeoutOrNull(20000) {
                    val target = if (playing.publishedAt > 0) playing else Graph.api.detail(playing).video
                    CreatorVideoLocator { Graph.api.uploads(seed.mid, it) }.find(target)
                }
                if (match == null) locationMessage = "暂未在公开投稿中定位到此视频，可通过标题搜索；当前视频仍可直接播放"
                else { searchText = ""; query = ""; basePage = match.page; located = match; reveal = playing.key; locationRevision++ }
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; locationMessage = networkMessage(failure) }
            finally { locating = false }
        }
    }
    key(seed.mid, query, basePage, located, locationRevision) {
    RefreshableVideoFeed(modifier, onVideo, initialPage = basePage, initialNextPage = if (located == null) basePage else basePage + 1,
        initial = located?.content?.videos.orEmpty(), highlightKey = playing?.key.orEmpty(), revealKey = reveal,
        onSelection = onSelection?.let { select -> { video, videos, next -> select(video, videos, next, query) } },
        header = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                UserAvatar(profile.avatar, profile.name, Modifier.size(68.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(profile.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (profile.vip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { UserLevel(profile.level); if (profile.vip) UserBadge("大会员"); Text("UID ${profile.mid}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                CreatorFollowButton(profile.mid, vm)
            }
            if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message -> Column { Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error); TextButton(onClick = { attempt++ }) { Text("重试用户资料") } } }
            if (profile.official.isNotBlank()) Text(profile.official, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
            if (profile.sign.isNotBlank()) Text(profile.sign, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 21.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                profile.fans?.let { Text("${countLabel(it)} 粉丝", fontSize = 12.sp) }
                profile.following?.let { Text("${countLabel(it)} 关注", fontSize = 12.sp) }
                profile.videoCount?.let { Text("${countLabel(it)} 投稿", fontSize = 12.sp) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            playing?.let { video ->
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("正在播放 · ${video.title}", fontSize = 13.sp, maxLines = 2)
                        Row {
                            TextButton(onClick = { onSelection?.invoke(video, listOf(video), 1, "") ?: onVideo(video) }) { Text("回到当前视频") }
                            TextButton(onClick = ::locate, enabled = !locating) { Text(if (locating) "定位中…" else "定位当前视频") }
                        }
                    }
                }
            }
            OutlinedTextField(searchText, { searchText = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索这位 UP 主的投稿", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Default.ArrowForward, "搜索这位UP主的投稿") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
            locationMessage?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (query.isBlank()) "投稿视频" else "搜索：$query", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (basePage > 1) TextButton(onClick = { basePage--; located = null; reveal = "" }) { Text("较新投稿") }
                if (basePage > 1 || query.isNotBlank()) TextButton(onClick = { searchText = ""; search() }) { Text("全部投稿") }
            }
        }
    }, load = { Graph.api.uploads(seed.mid, it, query) })
    }
}
