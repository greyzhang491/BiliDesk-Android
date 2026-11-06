// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.AccountStatus
import dev.bilidesk.android.data.User

@Composable fun LibraryScreen(vm: AppViewModel, user: User?) {
    val account by vm.account.collectAsStateWithLifecycle()
    val caches by Graph.store.caches.collectAsStateWithLifecycle()
    val history by Graph.store.history.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().clickable { if (user == null) vm.requireLogin {} else if (!account.checking) vm.retryAccount() }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        BiliDeskLogo(Modifier.size(53.dp), "个人头像")
                        if (!user?.avatar.isNullOrBlank()) AsyncImage(user!!.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(user?.name ?: "登录哔哩哔哩", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (user != null) Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("LV ${user.level}", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium) }
                            Text("UID ${user.mid}${if (user.vip) "  ·  大会员" else ""}", Modifier.padding(start = 8.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(account.message ?: if (user == null) "同步收藏和观看记录" else "已登录", Modifier.padding(top = 7.dp), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (account.status in setOf(AccountStatus.RETRY_PENDING, AccountStatus.STORAGE_UNAVAILABLE)) TextButton(onClick = { vm.retryAccount() }, contentPadding = PaddingValues(0.dp)) { Text("重试同步", fontSize = 11.sp) }
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { MenuRow(Icons.Outlined.PeopleOutline, "我的关注", "查看关注的 UP 主与投稿") { vm.requireLogin { vm.navigate("following") } } } }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LibraryTile(Icons.Outlined.StarBorder, "收藏", "账号收藏夹", Modifier.weight(1f), Pink) { vm.requireLogin { vm.navigate("folders") } }
                    VerticalDivider(Modifier.height(36.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    LibraryTile(Icons.Outlined.History, "历史", "${history.size} 条记录", Modifier.weight(1f), Color(0xFF55A9DE)) { vm.navigate("feed", "localHistory", "观看历史") }
                    VerticalDivider(Modifier.height(36.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    LibraryTile(Icons.Outlined.DownloadForOffline, "缓存", "${caches.size} 个视频", Modifier.weight(1f), Color(0xFF58B99C)) { vm.navigate("downloads") }
                }
            }
        }
        item {
            LibraryGroup("常用服务") {
                MenuRow(Icons.Outlined.WatchLater, "稍后再看", "把想看的先收好") { vm.requireLogin { vm.navigate("feed", "later", "稍后再看") } }
                LibraryDivider()
                MenuRow(Icons.Outlined.Movie, "我的追番", "继续未完的故事") { vm.requireLogin { vm.navigate("feed", "myBangumi", "我的追番") } }
                LibraryDivider()
                MenuRow(Icons.Outlined.NotificationsNone, "消息", "回复、点赞、@我与私信") { vm.requireLogin { vm.navigate("messages") } }
            }
        }
        item {
            LibraryGroup("偏好与帮助") {
                MenuRow(Icons.Outlined.Settings, "设置", "播放、弹幕和外观") { vm.navigate("settings") }
                LibraryDivider()
                MenuRow(Icons.Outlined.Info, "关于 BiliDesk", "开源与致谢") { vm.navigate("about") }
            }
        }
    }
}

@Composable private fun LibraryGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(start = 4.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { Column(content = content) }
    }
}

@Composable private fun LibraryDivider() { HorizontalDivider(Modifier.padding(start = 62.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp) }

@Composable fun LibraryTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.primary else tint
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .1f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(25.dp), tint = color) }
        Text(title, Modifier.padding(top = 9.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable fun MenuRow(icon: ImageVector, title: String, subtitle: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
    }
}
