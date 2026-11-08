// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.bilidesk.android.data.accountNetworkAvailability
import dev.bilidesk.android.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private val vm by viewModels<AppViewModel>()
    private var pip by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by Graph.store.settings.collectAsStateWithLifecycle()
            BiliTheme(settings) { BiliApp(vm, pip, onPip = { runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } }) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                accountNetworkAvailability(applicationContext).collectLatest { available ->
                    if (available) { delay(600); vm.onNetworkRecovered() }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onStart() { super.onStart(); Graph.player.resumeVideoTrack(); vm.refreshUser(); Graph.musicSync.setForeground(true) }
    private fun handleIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra("open_player", false) == true -> vm.openPlayer()
            intent?.getBooleanExtra("open_downloads", false) == true -> vm.navigate("downloads")
            intent?.action == Intent.ACTION_VIEW -> intent.dataString?.let(vm::resolveLink)
            intent?.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::resolveLink)
        }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); pip = isInPictureInPictureMode }
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode && !isChangingConfigurations) Graph.player.pauseForBackground(); Graph.musicSync.setForeground(false) }
}
