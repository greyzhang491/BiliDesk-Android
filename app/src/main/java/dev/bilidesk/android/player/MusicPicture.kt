// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.*
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

data class MusicPictureState(val requested: Boolean = false, val loading: Boolean = false, val ready: Boolean = false, val error: String? = null)

/** A silent, on-demand picture follows the existing audio clock. It never replaces the audio source. */
class MusicPicture(private val context: Context, private val api: BiliApi, private val store: Store,
    private val audio: ExoPlayer, private val mediaHttp: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MusicPictureState())
    val state = _state.asStateFlow()
    var player: ExoPlayer? = null
        private set
    private var job: Job? = null
    private var generation = 0
    private var identity = ""
    private var reusablePlan: Pair<Long, PlayPlan>? = null
    private var failedUrl: String? = null
    private val audioListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { player?.playWhenReady = isPlaying }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) player?.seekTo(audio.currentPosition.coerceAtLeast(0))
        }
    }
    init { audio.addListener(audioListener) }
    fun show(detail: VideoDetail, episode: Episode, known: PlayPlan?, local: File? = null, fresh: Boolean = false) {
        close()
        val key = "${detail.video.key}:${episode.cid}"
        if (key != identity) { identity = key; reusablePlan = null; failedUrl = null }
        val epoch = generation
        var sourceUrl: String? = null
        val picture = ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 15000, 500, 1000).setTargetBufferBytes(12 * 1024 * 1024).build()).build()
        player = picture
        picture.volume = 0f
        picture.trackSelectionParameters = picture.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        _state.value = MusicPictureState(requested = true, loading = true)
        picture.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (epoch != generation) return
                _state.value = _state.value.copy(loading = playbackState == Player.STATE_BUFFERING)
            }
            override fun onRenderedFirstFrame() { if (epoch == generation) _state.value = _state.value.copy(ready = true, loading = false) }
            override fun onPlayerError(error: PlaybackException) {
                if (epoch != generation) return
                picture.pause()
                failedUrl = sourceUrl; reusablePlan = null
                _state.value = MusicPictureState(requested = true, error = "画面暂时无法加载")
            }
        })
        job = scope.launch {
            try {
                val source: MediaSource
                if (local != null) source = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.fromUri(Uri.fromFile(local)))
                else {
                    val cached = reusablePlan?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first in 0..300000 }?.second
                    val plan = cached?.takeIf { !fresh } ?: known?.takeIf { !fresh && it.video.url != failedUrl && (it.video.codec.startsWith("avc", true) || it.video.codec.isBlank()) }
                        ?: api.play(detail.video, episode, store.settings.value.quality, forceAvc = true)
                    if (epoch != generation) return@launch
                    sourceUrl = plan.video.url; reusablePlan = android.os.SystemClock.elapsedRealtime() to plan
                    val http = OkHttpDataSource.Factory(mediaHttp).setUserAgent(BILI_UA).setDefaultRequestProperties(mapOf("Referer" to detail.video.url))
                    val streams = listOf(plan.video) + plan.videoOptions
                    val backups = streams.associate { it.url to it.backups }
                    val resilient: DataSource.Factory = if (store.settings.value.cdnMode == 0) DataSource.Factory { ResilientMediaDataSource(http, backups) } else http
                    val factory = DefaultDataSource.Factory(context, resilient)
                    val item = MediaItem.Builder().setUri(plan.video.url).setMimeType(plan.video.mime).setMediaId("music-picture:${episode.cid}").build()
                    val xml = DashStreams.xml(DashStreams.videos(plan), plan.durationMs)
                    source = when {
                        plan.video.mime.contains("mpegURL") -> HlsMediaSource.Factory(factory).createMediaSource(item)
                        xml != null -> {
                            val manifest = DashManifestParser().parse(Uri.parse(plan.video.url), ByteArrayInputStream(xml.toByteArray()))
                            DashMediaSource.Factory(factory).createMediaSource(manifest, item)
                        }
                        else -> ProgressiveMediaSource.Factory(factory).createMediaSource(item)
                    }
                }
                if (epoch != generation) return@launch
                picture.setMediaSource(source, audio.currentPosition.coerceAtLeast(0)); picture.prepare(); picture.playWhenReady = audio.isPlaying
                while (isActive && epoch == generation) {
                    delay(400)
                    if (_state.value.error != null) break
                    picture.playWhenReady = audio.isPlaying
                    if (picture.playbackState == Player.STATE_READY) {
                        val drift = picture.currentPosition - audio.currentPosition
                        if (abs(drift) > 450) picture.seekTo(audio.currentPosition.coerceAtLeast(0))
                        picture.setPlaybackSpeed(audio.playbackParameters.speed * when { drift > 100 -> .98f; drift < -100 -> 1.02f; else -> 1f })
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (epoch == generation) { reusablePlan = null; _state.value = MusicPictureState(requested = true, error = "画面暂时无法加载") }
            }
        }
    }
    fun close() {
        generation++; job?.cancel(); job = null; player?.release(); player = null; _state.value = MusicPictureState()
    }
    fun release() { close(); reusablePlan = null; failedUrl = null; audio.removeListener(audioListener); scope.cancel() }
}
