// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bilidesk.android.data.User

/** Shared app chrome; player routes keep their own immersive controls. */
@Composable internal fun RootToolbar(vm: AppViewModel, user: User?, library: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (library) Text("我的", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        else RootSearch(vm, user)
        IconButton(onClick = { vm.requireLogin { vm.navigate("messages") } }, Modifier.size(44.dp)) { Icon(Icons.Outlined.MailOutline, "消息", Modifier.size(22.dp)) }
        IconButton(onClick = { vm.navigate("settings") }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Tune, "设置", Modifier.size(22.dp)) }
    }
}

@Composable private fun RowScope.RootSearch(vm: AppViewModel, user: User?) {
    IconButton(onClick = { vm.root("library") }, Modifier.size(44.dp).semantics { contentDescription = "打开个人中心" }) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            BiliDeskLogo(Modifier.size(29.dp), "BiliDesk")
            if (!user?.avatar.isNullOrBlank()) AsyncImage(user!!.avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
    Spacer(Modifier.width(6.dp))
    Box(Modifier.weight(1f).height(48.dp).clickable(role = Role.Button) { vm.navigate("search") }.semantics { contentDescription = "搜索" }, contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().height(35.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("搜索感兴趣的视频", Modifier.padding(start = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun navigationIcon(name: String, selected: Boolean, filled: ImageVector): ImageVector = if (selected) filled else when (name) {
    "home" -> Icons.Outlined.Home
    "shorts" -> Icons.Outlined.SmartDisplay
    "explore" -> Icons.Outlined.GridView
    "music" -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.PersonOutline
}

@Composable internal fun AppBottomNavigation(route: String, destinations: List<Triple<String, String, ImageVector>>, onDestination: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
            Row(Modifier.fillMaxWidth().height(60.dp).selectableGroup()) {
                destinations.forEach { (name, label, icon) ->
                    val selected = route == name
                    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = { onDestination(name) }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(navigationIcon(name, selected, icon), null, Modifier.size(24.dp), tint = color)
                        Text(label, Modifier.padding(top = 4.dp), fontSize = 10.sp, lineHeight = 13.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}
