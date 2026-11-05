// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.data.Settings

val Pink = Color(0xFFF58FAB)
val Mint = Color(0xFF88D6C2)
private val Dark = darkColorScheme(primary = Pink, onPrimary = Color(0xFF321623), secondary = Mint, background = Color(0xFF0D1118), surface = Color(0xFF121822), surfaceVariant = Color(0xFF1C2431), onBackground = Color(0xFFF3F2F7), onSurface = Color(0xFFF3F2F7), onSurfaceVariant = Color(0xFF9DA7B9), outline = Color(0xFF394253))
private val Light = lightColorScheme(primary = Color(0xFFBA466D), onPrimary = Color.White, secondary = Color(0xFF247B69), background = Color(0xFFF6F7FB), surface = Color.White, surfaceVariant = Color(0xFFEDF0F6), onBackground = Color(0xFF172030), onSurface = Color(0xFF172030), onSurfaceVariant = Color(0xFF677388), outline = Color(0xFFD4DCE7))

@Composable fun BiliTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when(settings.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
        ), content = content)
}
