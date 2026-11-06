// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun FollowingScreen(vm: AppViewModel) {
    val user by vm.user.collectAsStateWithLifecycle()
    val account = user
    if (account == null) { LoginGate(vm, "登录后查看我的关注"); return }
    key(account.mid) { FollowingContent(vm, account.mid) }
}

@Composable private fun FollowingContent(vm: AppViewModel, account: Long) {
    var creators by remember { mutableStateOf<List<CreatorProfile>>(emptyList()) }
    var next by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedRefresh by remember { mutableStateOf(false) }
    var total by remember { mutableLongStateOf(-1) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val epoch by vm.networkEpoch.collectAsStateWithLifecycle()
    fun load(reset: Boolean = false) {
        if (busy || !reset && !more) return
        busy = true; refreshing = reset; error = null; failedRefresh = false
        scope.launch {
            try {
                val page = if (reset) 1 else next
                val result = Graph.api.followingUsers(page)
                if (vm.user.value?.mid != account) return@launch
                val before = if (reset) emptyList() else creators
                creators = (before + result.creators).distinctBy { it.mid }
                total = result.total; next = page + 1; more = result.more && creators.size > before.size
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = networkMessage(failure); failedRefresh = reset
            } finally { busy = false; refreshing = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) { Graph.api.relationChanges.collect { if (it.account == account.toString()) dirty = true } }
    LaunchedEffect(dirty, busy) { if (dirty && !busy) { dirty = false; load(true) } }
    LaunchedEffect(epoch) { if (epoch > 0 && error != null) load(failedRefresh) }
    val nearEnd by remember { derivedStateOf { (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= scroll.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, next, busy, error, more) { if (nearEnd && creators.isNotEmpty() && more && !busy && error == null) load() }
    PullToRefreshBox(refreshing, { load(true) }, Modifier.fillMaxSize()) {
        LazyColumn(state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(if (total >= 0) "我的关注 · $total" else "我的关注", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
            items(creators, key = { it.mid }) { creator ->
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.weight(1f).clickable { vm.openCreator(creator) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UserAvatar(creator.avatar, creator.name, Modifier.size(48.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(creator.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(creator.sign.ifBlank { "查看投稿与搜索" }, fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        CreatorFollowButton(creator.mid, vm, initial = true)
                    }
                }
            }
            item {
                when {
                    busy -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    error != null -> EmptyState("关注列表暂时无法加载", error!!, { load(failedRefresh) })
                    creators.isEmpty() -> EmptyState("还没有关注的 UP 主", "关注喜欢的创作者后，可在这里查看他们的投稿", { load(true) })
                    else -> Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { Text(if (more) "继续滑动，自动加载" else "已显示全部关注", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
