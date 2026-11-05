// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.ui

import android.view.ViewTreeObserver
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun ImmersivePlayback(enabled: Boolean, pip: Boolean = false) {
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, enabled, pip) {
        val window = activity?.window
        val decor = window?.decorView
        fun apply() {
            if (window == null || decor == null || pip) return
            WindowCompat.getInsetsController(window, decor).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            }
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) apply() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
        lifecycle.addObserver(observer)
        apply()
        onDispose {
            if (decor?.viewTreeObserver?.isAlive == true) decor.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            lifecycle.removeObserver(observer)
            if (!pip && window != null && decor != null) WindowCompat.getInsetsController(window, decor).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
