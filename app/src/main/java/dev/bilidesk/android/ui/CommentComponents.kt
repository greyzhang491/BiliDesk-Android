// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.bilidesk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import dev.bilidesk.android.data.*

@Composable fun UserAvatar(url: String, name: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (onClick != null) Modifier.clickable(onClickLabel = "查看${name}的主页", onClick = onClick).semantics { contentDescription = "查看${name}的主页" } else Modifier), contentAlignment = Alignment.Center) {
        if (!loaded) Icon(Icons.Default.Person, null, Modifier.fillMaxSize().padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url.isNotBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable fun UserLevel(level: Int?) {
    if (level == null) return
    val color = when (level) { 0, 1 -> Color(0xFF81929D); 2 -> Color(0xFF5EAA84); 3 -> Color(0xFF4498BC); 4 -> Color(0xFF5388DC); 5 -> Color(0xFFE1973C); else -> Color(0xFFE56C69) }
    Text("LV$level", Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
}

@Composable fun UserBadge(label: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(label, Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = .1f)).padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 10.sp, lineHeight = 13.sp, color = color)
}

/** Only mapped mentions become user links; punctuation, newlines and emoji stay intact. */
@Composable fun CommentRichText(c: Comment, onUser: (Long, String, String) -> Unit, modifier: Modifier = Modifier, preview: Boolean = false, replyTo: Comment? = null, maxLines: Int = Int.MAX_VALUE) {
    val accent = MaterialTheme.colorScheme.primary
    val normal = if (preview) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val spans = remember(c.text, c.emotes, c.mentions) { CommentContent.spans(c.text, c.emotes, c.mentions) }
    val inline = c.emotes.associate { emote -> emote.token to InlineTextContent(Placeholder(if (emote.size > 1) 28.sp else 21.sp, if (emote.size > 1) 28.sp else 21.sp, PlaceholderVerticalAlign.TextCenter)) {
        var failed by remember(emote.url) { mutableStateOf(false) }
        if (failed) Text(emote.token, fontSize = 9.sp, color = normal)
        else AsyncImage(emote.url, emote.token, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
    } }
    fun AnnotatedString.Builder.user(name: String, mid: Long, avatar: String = "", color: Color = accent) {
        val style = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
        if (mid > 0 || name.startsWith("@")) withLink(LinkAnnotation.Clickable("user:$mid:$name", TextLinkStyles(style)) { onUser(mid, name, avatar) }) { append(name) }
        else withStyle(style) { append(name) }
    }
    val text = buildAnnotatedString {
        if (preview) { user(c.name, c.mid, c.avatar, normal); append("：") }
        if (replyTo != null && !c.text.trimStart().startsWith("回复 @") && !c.text.trimStart().startsWith("回复@")) { append("回复 "); user("@${replyTo.name}", replyTo.mid, replyTo.avatar); append("：") }
        var from = 0
        for (span in spans) {
            append(c.text.substring(from, span.start))
            when {
                span.emote != null -> appendInlineContent(span.emote.token, span.emote.token)
                span.mention -> user(c.text.substring(span.start, span.end), span.mid)
            }
            from = span.end
        }
        append(c.text.substring(from))
    }
    Text(text, modifier, color = normal, fontSize = if (preview) 13.sp else 15.sp, lineHeight = if (preview) 21.sp else 24.sp, inlineContent = inline, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable fun CommentPictures(pictures: List<CommentPicture>) {
    if (pictures.isEmpty()) return
    var selected by remember { mutableIntStateOf(-1) }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pictures.forEachIndexed { index, picture -> AsyncImage(picture.url, "评论图片 ${index + 1}", Modifier.size(86.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { selected = index }, contentScale = ContentScale.Crop) }
    }
    if (selected in pictures.indices) Dialog(onDismissRequest = { selected = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                AsyncImage(pictures[selected].url, "评论原图 ${selected + 1}", Modifier.fillMaxSize().padding(vertical = 56.dp), contentScale = ContentScale.Fit)
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = -1 }) { Icon(Icons.Default.Close, "关闭评论图片", tint = Color.White) }
                    Text("${selected + 1} / ${pictures.size}", Modifier.weight(1f), color = Color.White)
                    IconButton(enabled = selected > 0, onClick = { selected-- }) { Icon(Icons.Default.ChevronLeft, "上一张评论图片", tint = Color.White) }
                    IconButton(enabled = selected < pictures.lastIndex, onClick = { selected++ }) { Icon(Icons.Default.ChevronRight, "下一张评论图片", tint = Color.White) }
                }
            }
        }
    }
}
