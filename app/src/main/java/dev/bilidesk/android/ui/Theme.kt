// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.bilidesk.android.data.Settings

val Pink = Color(0xFFFF6699)
val Mint = Color(0xFF75CCBC)
private val Dark = darkColorScheme(
    primary = Color(0xFFFF8AB1), onPrimary = Color(0xFF4A1730), primaryContainer = Color(0xFF38202A), onPrimaryContainer = Color(0xFFFFBBD2),
    secondary = Mint, secondaryContainer = Color(0xFF163C3A), onSecondaryContainer = Color(0xFFA8E9DC),
    background = Color(0xFF0D0D0E), surface = Color(0xFF17181A), surfaceVariant = Color(0xFF232527),
    onBackground = Color(0xFFE7E9EB), onSurface = Color(0xFFE7E9EB), onSurfaceVariant = Color(0xFFA2A7AE),
    outline = Color(0xFF484C53), outlineVariant = Color(0xFF2C2F33), surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF101112), surfaceBright = Color(0xFF35373A), surfaceContainerLowest = Color(0xFF0D0D0E),
    surfaceContainerLow = Color(0xFF191A1D), surfaceContainer = Color(0xFF202225), surfaceContainerHigh = Color(0xFF282A2E), surfaceContainerHighest = Color(0xFF32353A)
)
private val Light = lightColorScheme(
    primary = Color(0xFFD43F70), onPrimary = Color.White, primaryContainer = Color(0xFFFFECF1), onPrimaryContainer = Color(0xFF9E3156),
    secondary = Color(0xFF248AAE), secondaryContainer = Color(0xFFE8F6FB), onSecondaryContainer = Color(0xFF175571),
    background = Color(0xFFF6F7F8), surface = Color.White, surfaceVariant = Color(0xFFF1F2F3),
    onBackground = Color(0xFF18191C), onSurface = Color(0xFF18191C), onSurfaceVariant = Color(0xFF61666D),
    outline = Color(0xFFCED2D7), outlineVariant = Color(0xFFE9EBEE), surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFDDDFE3), surfaceBright = Color.White, surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainer = Color(0xFFF6F7F8), surfaceContainerHigh = Color(0xFFF1F2F3), surfaceContainerHighest = Color(0xFFE9EBEE)
)

@Composable fun BiliTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when(settings.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, dark, lifecycle) {
        val window = activity?.window
        val decor = window?.decorView
        fun apply() { if (window != null && decor != null) WindowCompat.getInsetsController(window, decor).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        } }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) apply() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
        lifecycle.addObserver(observer); apply()
        onDispose {
            if (decor?.viewTreeObserver?.isAlive == true) decor.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            lifecycle.removeObserver(observer)
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
        ), content = content)
}
