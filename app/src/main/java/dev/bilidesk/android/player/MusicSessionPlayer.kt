// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.player

import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Resolves the next signed URL on demand while exposing standard lock-screen/headset commands. */
class MusicSessionPlayer(private val engine: PlayerEngine): ForwardingSimpleBasePlayer(engine.exo) {
    fun refresh() = invalidateState()
    override fun getState(): State {
        val state = super.getState()
        if (!engine.state.value.music) return state
        val queue = engine.music.queue.value
        val commands = state.availableCommands.buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
        return state.buildUpon().setAvailableCommands(commands).setRepeatMode(when (queue.mode) { MusicMode.ONE -> Player.REPEAT_MODE_ONE; MusicMode.LOOP -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF }).setShuffleModeEnabled(queue.mode == MusicMode.SHUFFLE).build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (engine.state.value.music) when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> { engine.music.next(); return Futures.immediateVoidFuture() }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> { engine.music.previous(); return Futures.immediateVoidFuture() }
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (!engine.state.value.music) return super.handleSetRepeatMode(repeatMode)
        engine.music.mode(when (repeatMode) { Player.REPEAT_MODE_ONE -> MusicMode.ONE; Player.REPEAT_MODE_ALL -> MusicMode.LOOP; else -> MusicMode.ORDER })
        return Futures.immediateVoidFuture()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (!engine.state.value.music) return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
        engine.music.mode(if (shuffleModeEnabled) MusicMode.SHUFFLE else MusicMode.ORDER)
        return Futures.immediateVoidFuture()
    }
}
