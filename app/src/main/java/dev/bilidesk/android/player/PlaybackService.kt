// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.bilidesk.android.Graph
import dev.bilidesk.android.MainActivity

@UnstableApi
class PlaybackService: MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open_player", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, Graph.player.sessionPlayer).setSessionActivity(intent).build().also {
            // The activity uses the shared player directly, so no MediaController connection
            // triggers onGetSession. Register explicitly so Media3 posts the foreground notification.
            addSession(it)
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if ((!Graph.player.state.value.music && !Graph.store.settings.value.background) || !Graph.player.exo.isPlaying) { Graph.player.stop(); stopSelf() }
    }
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
