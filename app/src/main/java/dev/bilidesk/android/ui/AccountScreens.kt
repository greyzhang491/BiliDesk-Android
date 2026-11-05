// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import dev.bilidesk.android.BuildConfig
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import dev.bilidesk.android.download.downloadSize
import kotlinx.coroutines.*

@Composable fun LoginScreen(vm: AppViewModel) {
    var mode by remember { mutableStateOf("扫码登录") }
    Column(Modifier.fillMaxSize()) {
        Tabs(listOf("扫码登录", "官方网页登录"), mode) { mode = it }
        if (mode == "官方网页登录") { OfficialLogin(vm); return }
        var qr by remember { mutableStateOf<Bitmap?>(null) }; var status by remember { mutableStateOf("正在获取二维码…") }; var tick by remember { mutableIntStateOf(0) }; var active by remember { mutableStateOf(false) }
        LaunchedEffect(tick) {
            qr = null; active = true; status = "正在获取二维码…"
            try {
                val (url, key) = Graph.api.createQr()
                qr = withContext(Dispatchers.Default) {
                    val bits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 2))
                    Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { for (y in 0 until 600) for (x in 0 until 600) setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
                }
                status = "使用哔哩哔哩 App 扫码"
                repeat(90) {
                    delay(2500)
                    try {
                        when (Graph.api.pollQr(key)) {
                            0 -> { status = "正在同步账号…"; active = false; vm.loggedIn(); return@LaunchedEffect }
                            86090 -> status = "扫码成功，请在哔哩哔哩中确认"
                            86038 -> { status = "二维码已过期，请点击刷新"; active = false; return@LaunchedEffect }
                            86101 -> status = "使用哔哩哔哩 App 扫码"
                            else -> status = "等待确认…"
                        }
                    } catch (e: Exception) { if (e is CancellationException) throw e; status = "网络暂时不稳定，正在重试…" }
                }
                status = "二维码已过期，请点击刷新"
            } catch (e: Exception) { if (e is CancellationException) throw e; status = e.message ?: "二维码获取失败" }
            finally { active = false }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("接上你的热爱", style = MaterialTheme.typography.headlineSmall)
            Text("登录哔哩哔哩账号，同步你的内容", Modifier.padding(top = 8.dp, bottom = 26.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(245.dp).clickable { tick++ }) { Box(contentAlignment = Alignment.Center) { qr?.let { Image(it.asImageBitmap(), "哔哩哔哩登录二维码", Modifier.padding(12.dp).fillMaxSize()) } ?: CircularProgressIndicator(Modifier.size(30.dp)) } }
            Text(status, Modifier.padding(top = 22.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Text(" 刷新二维码") }
            Text("只有一部手机？切换到「官方网页登录」。", Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("凭据通过 Android Keystore 加密保存在本机。", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun OfficialLogin(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    fun importSession() {
        val raw = CookieManager.getInstance().getCookie("https://www.bilibili.com/").orEmpty()
        val values = raw.split(';').mapNotNull { item -> val index = item.indexOf('='); if (index <= 0) null else item.substring(0, index).trim() to item.substring(index + 1).trim() }.toMap()
        if (values["SESSDATA"].isNullOrBlank()) return
        if (!checking) {
            checking = true
            scope.launch {
                try { withContext(Dispatchers.IO) { Graph.cookies.putLogin(values) }; vm.loggedIn() }
                catch (error: Exception) { if (error is CancellationException) throw error; checking = false; vm.notices.tryEmit("本机登录信息保存失败，请稍后重试") }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.stopLoading(); webView?.destroy(); webView = null } }
    Column {
        Text("在哔哩哔哩官方页面完成登录。", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        AndroidView(factory = { ctx -> WebView(ctx).apply {
            webView = this
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object: WebChromeClient() { override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress } }
            webViewClient = object: WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    val trusted = request.url.scheme == "https" && (host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "geetest.com" || host.endsWith(".geetest.com"))
                    if (!trusted && request.isForMainFrame) { browse(context, request.url.toString()); return true }
                    return false
                }
                override fun onPageFinished(view: WebView?, url: String?) { importSession() }
            }
            loadUrl("https://passport.bilibili.com/login")
        } }, modifier = Modifier.weight(1f).fillMaxWidth())
        TextButton(onClick = { checking = false; importSession() }, Modifier.fillMaxWidth()) { Text("已完成登录，返回 BiliDesk") }
    }
}

@Composable fun DownloadsScreen(vm: AppViewModel) {
    val tasks by Graph.downloads.tasks.collectAsStateWithLifecycle()
    val caches by Graph.store.caches.collectAsStateWithLifecycle()
    var deletion by remember { mutableStateOf<CacheEntry?>(null) }
    val scope = rememberCoroutineScope()
    if (deletion != null) AlertDialog(onDismissRequest = { deletion = null }, title = { Text("删除这份缓存？") }, text = { Text(deletion!!.video.title) }, confirmButton = { TextButton(onClick = { val entry = deletion!!; deletion = null; scope.launch(Dispatchers.IO) { Graph.store.deleteCache(entry) } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("保留") } })
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CacheStorageControls() }
        item { MenuRow(Icons.Default.Settings, "缓存管理设置", "自动缓存开关、容量上限与保留时间") { vm.navigate("settings") } }
        if (tasks.isEmpty() && caches.isEmpty()) item { Text("在播放器中点击「缓存」可手动下载，离线也能看。", fontSize = 13.sp) }
        if (tasks.isNotEmpty()) item { Text("下载任务", style = MaterialTheme.typography.titleMedium) }
        items(tasks.filter { it.state != "缓存完成" }, key = { "task-${it.key}" }) { task -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(16.dp)) {
            Text(task.video.title, fontSize = 13.sp, maxLines = 2)
            Text(if (task.audioOnly) "仅音频" else "视频 · ${Quality.label(task.requestedQuality)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.running && task.totalBytes <= 0 && task.state.startsWith("正在下载")) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            else LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            if (task.downloadedBytes > 0 || task.bytesPerSecond > 0) Text("${downloadSize(task.downloadedBytes)}${if (task.totalBytes > 0) " / ${downloadSize(task.totalBytes)}" else ""} · ${downloadSize(task.bytesPerSecond)}/s", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.error ?: "${task.state}  ${(task.progress * 100).toInt()}%", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { if (task.running) Graph.downloads.pause(task.key) else Graph.downloads.enqueue(task.video, task.episode, task.automatic, task.requestedQuality, task.audioOnly) }) { Text(if (task.running) "暂停" else if (task.paused) "继续" else "重试") }
                if (task.state != "已取消") TextButton(onClick = { Graph.downloads.cancel(task.key) }) { Text("取消") }
            }
        } } }
        item { Text("已缓存 · ${caches.size}", style = MaterialTheme.typography.titleMedium) }
        items(caches, key = { "cache-${it.key}" }) { entry -> Column {
            CompactVideo(entry.video, "${if (entry.type == "audio") "音频" else "视频 ${if (entry.quality > 0) Quality.label(entry.quality) else ""}"} · ${if (entry.automatic) "自动缓存" else "手动下载"} · ${entry.pageTitle} · ${"%.1f".format(entry.bytes / 1048576.0)} MB") { vm.openCache(entry) }
            TextButton(onClick = { deletion = entry }, Modifier.align(Alignment.End)) { Text("删除缓存", fontSize = 11.sp) }
        } }
    }
}

@Composable fun MessagesScreen(vm: AppViewModel) {
    var type by remember { mutableStateOf("回复") }
    Column { Tabs(listOf("回复", "@我", "点赞", "私信"), type) { type = it }; key(type) {
        var messages by remember { mutableStateOf<List<MessageItem>?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var tick by remember { mutableIntStateOf(0) }
        val context = LocalContext.current
        LaunchedEffect(tick) { try { messages = Graph.api.messages(type); error = null } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } }
        when { error != null -> EmptyState("消息暂时无法加载", error!!, { tick++ }); messages == null -> LoadingState(); messages!!.isEmpty() -> EmptyState("还没有消息", "新的${type}会显示在这里"); else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text(" 刷新") } }
            itemsIndexed(messages!!) { _, msg -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.clickable { if (msg.talkerId > 0) vm.navigate("conversation", "${msg.talkerId}", msg.title) else if (msg.url.isNotBlank()) browse(context, msg.url) }) { Column(Modifier.padding(18.dp)) { Text(msg.title, style = MaterialTheme.typography.titleMedium); Text(msg.text.ifBlank { "在官方消息中心查看详情" }, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        } }
    } }
}

@Composable fun ConversationScreen(vm: AppViewModel, talker: Long) {
    var messages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }; var text by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(true) }; var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { try { messages = Graph.api.messageHistory(talker); error = null } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { busy = false } }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { itemsIndexed(messages) { _, message -> Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.title == "我") Alignment.End else Alignment.Start) { Text(message.title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Surface(shape = RoundedCornerShape(14.dp), color = if (message.title == "我") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) { Text(message.text, Modifier.padding(14.dp), fontSize = 13.sp) } } } }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("发送文字消息") }, maxLines = 4, shape = RoundedCornerShape(16.dp))
            IconButton(enabled = text.isNotBlank() && !busy, onClick = { val outgoing = text.trim(); busy = true; scope.launch { try { Graph.api.sendMessage(talker, outgoing); text = ""; tick++ } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message; busy = false } } }) { Icon(Icons.Default.Send, "发送") }
        }
    }
}

@Composable fun SettingsScreen(vm: AppViewModel) {
    val s by Graph.store.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var categoryDialog by remember { mutableStateOf(false) }; var showLogout by remember { mutableStateOf(false) }
    fun save(next: Settings) = Graph.store.update(next)
    if (categoryDialog) {
        var value by remember { mutableStateOf(s.sponsorCategories) }
        AlertDialog(onDismissRequest = { categoryDialog = false }, title = { Text("跳过哪些片段") }, text = { Column { Text("用逗号分隔类别：sponsor（赞助）、selfpromo（自我推广）、interaction（互动）、intro（片头）、outro（片尾）。", fontSize = 12.sp); OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().padding(top = 12.dp)) } }, confirmButton = { TextButton(onClick = { save(s.copy(sponsorCategories = value)); categoryDialog = false }) { Text("保存") } })
    }
    if (showLogout) AlertDialog(onDismissRequest = { showLogout = false }, title = { Text("退出当前账号？") }, text = { Text("本机历史和已缓存视频会保留。") }, confirmButton = { TextButton(onClick = { showLogout = false; vm.logout() }) { Text("退出登录") } }, dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } })
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SettingsHeading("外观") }
        item { ChoiceSetting("主题", listOf("跟随系统", "深色", "浅色"), s.theme) { save(s.copy(theme = it)) } }
        item { FeedDisplaySettings(s, Graph.store::change) }
        item { SettingsHeading("播放") }
        item { ChoiceSetting("默认清晰度", Quality.options.map(Quality::label), Quality.options.indexOf(s.quality).coerceAtLeast(0)) { save(s.copy(quality = Quality.options[it])) } }
        item { SwitchSetting("优先 HEVC 编码", "同清晰度下优先选择更省流量的 HEVC", s.preferHevc) { save(s.copy(preferHevc = it)) } }
        item { ChoiceSetting("线路选择", listOf("自动测速", "跟随服务端"), s.cdnMode) { save(s.copy(cdnMode = it)) } }
        item { SwitchSetting("后台播放 / 听视频", "锁屏后继续播放，支持系统媒体控制", s.background) { save(s.copy(background = it)) } }
        item { HoldSpeedSettings(s, Graph.store::change) }
        item { SettingsHeading("缓存管理") }
        item { CacheSettingsControls(s, Graph.store::change) }
        item { CacheStorageControls() }
        item { MenuRow(Icons.Default.Download, "管理手动下载", "手动下载不会被自动清理") { vm.navigate("downloads") } }
        item { SettingsHeading("弹幕") }
        item { DanmakuSettingsControls(s, Graph.store::change) }
        item { SettingsHeading("赞助片段跳过") }
        item { SwitchSetting("启用片段跳过", "使用上游同款第三方服务 bsbsb.top；开启后会向其发送当前 BV 号。标注由社区提供，失败不影响播放，下次打开视频生效。", s.sponsor) { save(s.copy(sponsor = it)) } }
        item { MenuRow(Icons.Default.SkipNext, "跳过类别", s.sponsorCategories) { categoryDialog = true } }
        item { SettingsHeading("账号与应用") }
        item { MenuRow(Icons.Default.Info, "关于与开源许可", "BiliDesk Android ${BuildConfig.VERSION_NAME}") { vm.navigate("about") } }
        item { if (user != null) OutlinedButton(onClick = { showLogout = true }, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("退出登录") } else OutlinedButton(onClick = { vm.navigate("login") }, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("登录账号") } }
    }
}
@Composable fun SettingsHeading(title: String) { Text(title, Modifier.padding(top = 16.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
@Composable fun SwitchSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 16.dp)) { Text(title, fontSize = 14.sp); Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onCheckedChange = null) }
}
@Composable fun ChoiceSetting(title: String, options: List<String>, selected: Int, onChange: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box { MenuRow(Icons.Default.Tune, title, options.getOrElse(selected) { options.first() }) { menu = true }; DropdownMenu(menu, onDismissRequest = { menu = false }) { options.forEachIndexed { i, text -> DropdownMenuItem(text = { Text(text) }, onClick = { onChange(i); menu = false }) } } }
}
@Composable fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, change: (Float) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 14.sp); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Slider(pending, { pending = it }, valueRange = range, onValueChangeFinished = { change(pending) }) }
}

@Composable fun AboutScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp)) {
        BiliDeskLogo(Modifier.size(54.dp), "BiliDesk 图标")
        Text("BiliDesk Android", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineSmall)
        Text("${BuildConfig.VERSION_NAME} · 开发预览版", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("干净、流畅，专注每一次播放。", Modifier.padding(vertical = 24.dp), fontSize = 15.sp)
        Text("本项目参考并移植 ABING1111/BiliDesk 的 API、WBI 签名、分流播放、弹幕解析与赞助片段处理流程，使用 Kotlin、Jetpack Compose 和 AndroidX Media3 实现安卓界面与播放器。", fontSize = 13.sp, lineHeight = 23.sp)
        Text("这是第三方非官方开源客户端，与哔哩哔哩及上游作者没有官方关联。内容版权归平台及各创作者所有。播放权限以平台返回结果为准。", Modifier.padding(top = 18.dp), fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("本项目以 GPL-3.0-only 许可发布。完整许可、上游来源与依赖说明随源码提供。", Modifier.padding(top = 18.dp), fontSize = 12.sp)
        TextButton(onClick = { browse(context, "https://github.com/ABING1111/BiliDesk") }) { Text("查看原项目") }
        TextButton(onClick = { browse(context, "https://www.gnu.org/licenses/gpl-3.0.html") }) { Text("阅读 GPL-3.0 许可") }
        Text("预览版尚需真实安卓设备联调；平台接口、账号权限和地区限制可能影响部分功能。", Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}
