// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.ByteArrayInputStream
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import dev.bilidesk.android.Graph
import dev.bilidesk.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CookieJar
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PlaybackState(
    val detail: VideoDetail? = null, val pageIndex: Int = 0, val plan: PlayPlan? = null,
    val loading: Boolean = false, val error: String? = null, val position: Long = 0,
    val duration: Long = 0, val playing: Boolean = false, val buffer: Long = 0,
    val danmaku: List<Danmaku> = emptyList(), val notice: String? = null,
    val local: Boolean = false, val dmError: Boolean = false, val music: Boolean = false, val musicVisual: Boolean = false, val cacheType: String = "video"
)

@UnstableApi
class PlayerEngine(private val context: Context, private val api: BiliApi, private val store: Store, musicPreferenceName: String = "music_player") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prepareJob: Job? = null
    private var dmJob: Job? = null
    private var sponsorJob: Job? = null
    private var generation = 0
    private var segments: List<Segment> = emptyList()
    private val loadedSegments = mutableSetOf<Int>()
    private var lastHistoryAt = 0L
    private var lastCloudAt = 0L
    private var renewals = 0
    private var stablePlaybackAt = 0L
    private var requestedQuality = store.settings.value.quality
    private val mediaHttp = api.http.newBuilder().cookieJar(CookieJar.NO_COOKIES).connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).addNetworkInterceptor(MediaRangeInterceptor()).build()
    private var triedAvcFallback = false
    private var primaryAudioKey = ""
    private var primaryAudioItem: MediaItem? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state = _state.asStateFlow()
    val music = MusicController(context, api, this, musicPreferenceName)
    private val sessionOwner = lazy { MusicSessionPlayer(this) }
    val sessionPlayer get() = sessionOwner.value
    fun refreshSession() { if (sessionOwner.isInitialized()) sessionOwner.value.refresh() }
    val exo: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(1_000_000L).build())
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 30000, 500, 1000).setBackBuffer(5000, true).setTargetBufferBytes(32 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object: Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { stablePlaybackAt = if (isPlaying) android.os.SystemClock.elapsedRealtime() else 0L; _state.value = _state.value.copy(playing = isPlaying); if (isPlaying) startService() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.value = _state.value.copy(loading = playbackState == Player.STATE_BUFFERING)
                        if (playbackState == Player.STATE_ENDED) {
                            saveHistory()
                            val s = _state.value
                            if (s.music) { music.next(false); return }
                            val episode = currentEpisode()
                            if (!s.local && s.detail?.video?.kind == "video" && episode != null && episode.duration in 1..180 && store.settings.value.shortCache)
                                Graph.downloads.enqueue(s.detail.video, episode, automatic = true)
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val s = _state.value
                        val codec = s.plan?.video?.codec.orEmpty()
                        val audioOnly = s.music && !s.musicVisual && s.plan?.audio != null
                        if (!audioOnly && !s.local && PlaybackFallbackPolicy.needsAvc(codec, error.errorCode, error) && !triedAvcFallback) {
                            triedAvcFallback = true
                            fallbackToAvc(exo.currentPosition.coerceAtLeast(0))
                            return
                        }
                        if (!s.local && currentEpisode() != null && error.errorCode in 2000..2999 && renewals++ < 1) {
                            renewMedia()
                        } else _state.value = s.copy(loading = false, playing = false, error = if (error.errorCode in 2000..2999) "视频连接暂时中断，恢复网络后可重试；进度已保留" else "播放失败（${error.errorCode}），请重试或选择其他画质")
                    }
                })
            }
    }
    init {
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value.detail == null) continue
                val p = exo.currentPosition.coerceAtLeast(0)
                val duration = exo.duration.takeIf { it > 0 } ?: _state.value.duration
                _state.value = _state.value.copy(position = p, duration = duration, buffer = exo.bufferedPosition.coerceAtLeast(0), playing = exo.isPlaying)
                if (!_state.value.music && store.settings.value.danmaku && !_state.value.local && _state.value.detail?.video?.kind != "live") ensureDanmaku(p)
                if (!_state.value.music && store.settings.value.sponsor && exo.isPlaying) {
                    val wanted = store.settings.value.sponsorCategories.split(',').map { it.trim() }.toSet()
                    val cid = currentEpisode()?.cid ?: 0
                    SponsorRules.select(segments, cid, wanted).firstOrNull { p >= it.startMs && p < it.endMs && it.endMs < duration }?.let {
                        exo.seekTo(it.endMs); _state.value = _state.value.copy(notice = "已跳过赞助片段")
                    }
                }
                if (stablePlaybackAt > 0 && android.os.SystemClock.elapsedRealtime() - stablePlaybackAt > 10000) renewals = 0
                val now = System.currentTimeMillis()
                if (exo.isPlaying && now - lastHistoryAt > 5000) { lastHistoryAt = now; saveHistory() }
                if (exo.isPlaying && now - lastCloudAt > 30000) { lastCloudAt = now; reportHistory() }
            }
        }
    }
    fun open(video: Video) {
        music.snapshot()
        openMedia(video, false)
    }
    fun openMusic(track: MusicTrack, position: Long = 0, forceRestart: Boolean = false) { openMedia(track.video, true, track.cid, position, forceRestart) }
    private fun openMedia(video: Video, asMusic: Boolean, musicCid: Long = 0, position: Long? = null, forceRestart: Boolean = false) {
        val existing = _state.value
        if (asMusic && !existing.music && existing.detail?.video?.key == video.key && existing.error == null && exo.mediaItemCount > 0 && (musicCid == 0L || currentEpisode()?.cid == musicCid)) {
            _state.value = existing.copy(music = true, musicVisual = false)
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            existing.plan?.let { attach(selectMusicAudio(it), exo.currentPosition) } ?: run { exo.setPlaybackSpeed(1f); exo.play() }
            currentEpisode()?.let { music.prepared(existing.detail, it.cid) }
            startService(); return
        }
        if (_state.value.detail?.video?.key == video.key && _state.value.music == asMusic && _state.value.error == null && (!asMusic || (musicCid == 0L && _state.value.pageIndex == 0 || currentEpisode()?.cid == musicCid))) {
            if (forceRestart || exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play(); return
        }
        if (asMusic) {
            val cached = store.caches.value.filter { it.video.key == video.key && (musicCid == 0L || it.cid == musicCid) && store.cacheFile(it).isFile }.sortedBy { if (it.type == "audio") 0 else 1 }.firstOrNull()
            if (cached != null) {
                saveHistory(); prepareJob?.cancel(); dmJob?.cancel(); sponsorJob?.cancel(); generation++
                val episode = Episode(cached.cid, cached.pageTitle, cached.video.duration, cached.video.bvid, cached.video.aid)
                val detail = VideoDetail(cached.video, "", listOf(episode))
                _state.value = PlaybackState(music = true)
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                playLocal(cached, detail, 0, position ?: 0)
                music.prepared(detail, cached.cid)
                return
            }
        }
        saveHistory(); reportHistory()
        prepareJob?.cancel(); dmJob?.cancel(); sponsorJob?.cancel()
        val token = ++generation
        exo.pause(); exo.stop(); exo.clearMediaItems(); segments = emptyList(); loadedSegments.clear()
        store.pinCache(null)
        _state.value = PlaybackState(detail = if (asMusic) VideoDetail(video, "", emptyList()) else null, loading = true, music = asMusic)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, asMusic).build()
        prepareJob = scope.launch {
            try {
                if (video.kind == "live") {
                    val detail = VideoDetail(video, "直播中", emptyList())
                    _state.value = _state.value.copy(detail = detail)
                    val plan = api.livePlay(video.targetId)
                    if (token == generation) attach(plan, 0)
                } else {
                    val detail = api.detail(video)
                    if (detail.pages.isEmpty()) throw ApiException(-3, "这个视频没有可播放的分集")
                    val resume = store.history.value.firstOrNull { it.video.key == video.key }
                    val index = detail.pages.indexOfFirst { it.cid == if (asMusic) musicCid else resume?.cid }.coerceAtLeast(0)
                    _state.value = _state.value.copy(detail = detail, pageIndex = index)
                    prepare(index, position ?: resume?.positionMs?.takeIf { it < detail.pages[index].duration * 1000 - 5000 } ?: 0, token)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(detail = _state.value.detail ?: VideoDetail(video, "", emptyList()), loading = false, error = e.message ?: "视频加载失败") }
        }
    }
    private suspend fun prepare(index: Int, position: Long, token: Int, quality: Int = store.settings.value.quality, useCache: Boolean = true) {
        val detail = _state.value.detail ?: return
        val episode = detail.pages[index]
        triedAvcFallback = false
        renewals = 0
        requestedQuality = quality
        loadedSegments.clear(); segments = emptyList(); dmJob?.cancel(); sponsorJob?.cancel()
        _state.value = _state.value.copy(pageIndex = index, loading = true, error = null, danmaku = emptyList(), local = false, dmError = false)
        val cache = if (useCache) store.acquireCache(detail.video.key, episode.cid, forMusic = _state.value.music) else { store.pinCache(null); null }
        if (cache != null) {
            playLocal(cache, detail, index, position)
        } else {
            var plan = api.play(detail.video, episode, quality, forceAvc = _state.value.musicVisual)
            if (_state.value.music) plan = selectMusicAudio(plan)
            if (token != generation) return
            attach(plan, position)
        }
        if (token != generation) return
        if (_state.value.music) { music.prepared(detail, episode.cid); return }
        val sponsorBvid = episode.bvid.ifBlank { detail.video.bvid }
        if (store.settings.value.sponsor && sponsorBvid.isNotBlank()) sponsorJob = scope.launch { val raw = api.sponsors(sponsorBvid); if (token == generation) segments = raw }
        ensureDanmaku(position)
    }
    private fun fallbackToAvc(position: Long) {
        val detail = _state.value.detail ?: return
        val episode = currentEpisode() ?: return
        val token = generation
        val quality = requestedQuality
        _state.value = _state.value.copy(loading = true, error = null)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            try {
                val plan = api.play(detail.video, episode, quality, forceAvc = true)
                if (token != generation) return@launch
                attach(plan, position)
                _state.value = _state.value.copy(notice = "已切换为 H.264 兼容播放")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation) _state.value = _state.value.copy(loading = false, playing = false, error = e.message ?: "视频编码不兼容，暂时无法播放")
            }
        }
    }
    private fun renewMedia() {
        val detail = _state.value.detail ?: return
        val episode = currentEpisode() ?: return
        val token = generation
        val position = exo.currentPosition.coerceAtLeast(0)
        val autoplay = exo.playWhenReady
        _state.value = _state.value.copy(loading = true, error = null)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            try {
                var plan = api.play(detail.video, episode, requestedQuality, forceAvc = triedAvcFallback || _state.value.musicVisual)
                if (_state.value.music) plan = selectMusicAudio(plan)
                if (token == generation) attach(plan, position, autoplay)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (token == generation) _state.value = _state.value.copy(loading = false, error = "视频连接暂时中断，进度已保留；请重试")
            }
        }
    }
    private fun selectMusicAudio(plan: PlayPlan): PlayPlan = plan.copy(audio = MusicAudioPolicy.select(plan.audioOptions.ifEmpty { listOfNotNull(plan.audio) }, store.settings.value.music.audioQuality) ?: plan.audio)
    private fun attach(plan: PlayPlan, position: Long, autoplay: Boolean = true) {
        val detail = _state.value.detail ?: return
        val audioOnly = _state.value.music && !_state.value.musicVisual
        if (audioOnly && plan.audio == null) {
            exo.stop(); exo.clearMediaItems()
            _state.value = _state.value.copy(plan = plan, loading = false, playing = false, local = false, error = "此内容没有独立音轨，可切换原视频播放；将消耗视频流量")
            return
        }
        val referer = detail.video.url
        val http = OkHttpDataSource.Factory(mediaHttp).setUserAgent(BILI_UA).setDefaultRequestProperties(mapOf("Referer" to referer))
        val streams = listOf(plan.video) + plan.videoOptions + listOfNotNull(plan.audio)
        val backups = streams.associate { it.url to it.backups }
        val resilient: DataSource.Factory = if (store.settings.value.cdnMode == 0) DataSource.Factory { ResilientMediaDataSource(http, backups) } else http
        val sourceFactory = DefaultDataSource.Factory(context, resilient)
        val metadata = MediaMetadata.Builder().setTitle(detail.video.title).setArtist(detail.video.author).setArtworkUri(detail.video.cover.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()
        fun item(stream: Stream) = MediaItem.Builder().setUri(stream.url).setMediaId(detail.video.key).setMimeType(stream.mime).setMediaMetadata(metadata).build()
        fun indexedSource(streams: List<Stream>, mediaItem: MediaItem): androidx.media3.exoplayer.source.MediaSource? {
            val xml = DashStreams.xml(streams, plan.durationMs) ?: return null
            val manifest = DashManifestParser().parse(Uri.parse(streams.first().url), ByteArrayInputStream(xml.toByteArray()))
            return DashMediaSource.Factory(sourceFactory).createMediaSource(manifest, mediaItem).apply { updateMediaItem(mediaItem) }
        }
        fun videoSource() = if (plan.video.mime.contains("mpegURL")) HlsMediaSource.Factory(sourceFactory).createMediaSource(item(plan.video))
                    else indexedSource(DashStreams.videos(plan), item(plan.video)) ?: ProgressiveMediaSource.Factory(sourceFactory, DefaultExtractorsFactory()).createMediaSource(item(plan.video))
        val audio = plan.audio?.let { stream ->
            val key = "${detail.video.key}:${currentEpisode()?.cid}:${stream.url}"
            if (primaryAudioKey != key || primaryAudioItem == null) { primaryAudioKey = key; primaryAudioItem = item(stream) }
            indexedSource(listOf(stream), primaryAudioItem!!) ?: ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(primaryAudioItem!!)
        }
        // Do not even instantiate a video source while listening. Audio remains the primary
        // timeline when the user explicitly opens the picture, preserving media-session identity.
        val source = when { audioOnly -> audio!!; audio != null -> MergingMediaSource(audio, videoSource()); else -> videoSource() }
        exo.setMediaSource(source, position)
        exo.setPlaybackSpeed(if (_state.value.music) 1f else store.settings.value.playbackSpeed)
        exo.prepare(); exo.playWhenReady = autoplay
        _state.value = _state.value.copy(plan = plan, error = null, local = false, duration = plan.durationMs, notice = if (plan.preview) "当前账号正在播放试看内容" else null)
        startService()
    }
    private fun startService() { runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) } }
    fun currentEpisode() = _state.value.detail?.pages?.getOrNull(_state.value.pageIndex)
    fun page(index: Int) {
        val detail = _state.value.detail ?: return
        if (index !in detail.pages.indices) return
        saveHistory(); reportHistory(); prepareJob?.cancel(); val token = ++generation
        exo.pause()
        prepareJob = scope.launch { try { prepare(index, 0, token) } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(loading = false, error = e.message) } }
    }
    fun quality(q: Int) {
        if (_state.value.local || _state.value.detail?.video?.kind == "live") return
        val position = exo.currentPosition
        prepareJob?.cancel(); val token = ++generation
        prepareJob = scope.launch { try { prepare(_state.value.pageIndex, position, token, q) } catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(loading = false, error = e.message) } }
    }
    fun line(url: String) { val plan = _state.value.plan ?: return; attach(DashStreams.preferLine(plan, url), exo.currentPosition, exo.playWhenReady) }
    fun retry() {
        val state = _state.value
        val d = state.detail ?: return
        if (state.local && d.pages.isNotEmpty()) {
            val position = exo.currentPosition.coerceAtLeast(0)
            prepareJob?.cancel(); val token = ++generation
            prepareJob = scope.launch {
                try { prepare(state.pageIndex, position, token, useCache = false) }
                catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(loading = false, error = e.message ?: "联网重试失败") }
            }
        } else if (d.video.kind == "live" || d.pages.isEmpty()) { _state.value = PlaybackState(); open(d.video) }
        else quality(requestedQuality)
    }
    fun onNetworkRecovered() {
        val state = _state.value
        if (!state.local && !state.loading && state.error != null && exo.playerError?.errorCode in 2000..2999) {
            renewals = 0; renewMedia()
        }
    }
    fun seek(position: Long) { exo.seekTo(position.coerceIn(0, exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
    fun speed(speed: Float) { exo.setPlaybackSpeed(speed); store.update(store.settings.value.copy(playbackSpeed = speed)) }
    fun toggle() { if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play(); startService() } }
    fun clearNotice() { _state.value = _state.value.copy(notice = null) }
    private fun ensureDanmaku(position: Long) {
        val cid = currentEpisode()?.cid ?: return
        if (cid <= 0 || !store.settings.value.danmaku || dmJob?.isActive == true) return
        val segment = (position / 360000).toInt() + 1
        if (segment in loadedSegments) return
        loadedSegments.add(segment)
        val token = generation
        dmJob = scope.launch {
            try {
                val list = api.danmaku(cid, segment)
                if (generation == token) _state.value = _state.value.copy(danmaku = (_state.value.danmaku + list).filter { it.timeMs >= (segment - 2) * 360000L }.distinctBy { Triple(it.timeMs, it.text, it.mode) }.sortedBy { it.timeMs }, dmError = false)
            } catch (e: Exception) { if (e is CancellationException) throw e; if (token == generation) _state.value = _state.value.copy(dmError = true) }
        }
    }
    fun reloadDanmaku() { loadedSegments.clear(); ensureDanmaku(exo.currentPosition) }
    fun playCache(cache: CacheEntry) {
        if (cache.type == "audio") { music.play(cache.video, cache.cid, cache.pageTitle); return }
        music.snapshot()
        if (!store.cacheFile(cache).isFile) {
            _state.value = _state.value.copy(error = "这份缓存已被清理，请联网重新播放")
            return
        }
        saveHistory(); prepareJob?.cancel(); dmJob?.cancel(); sponsorJob?.cancel(); generation++
        loadedSegments.clear(); segments = emptyList()
        _state.value = PlaybackState()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        val episode = Episode(cache.cid, cache.pageTitle, cache.video.duration, cache.video.bvid, cache.video.aid)
        val detail = VideoDetail(cache.video, "已缓存，可离线播放", listOf(episode))
        playLocal(cache, detail, 0, 0)
    }
    private fun playLocal(cache: CacheEntry, detail: VideoDetail, index: Int, position: Long) {
        val asMusic = _state.value.music; val visual = _state.value.musicVisual && cache.type != "audio"
        store.pinCache(cache.key)
        scope.launch(Dispatchers.IO) { store.touchCache(cache.key) }
        val metadata = MediaMetadata.Builder().setTitle(detail.video.title).setArtist(detail.video.author).build()
        exo.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(store.cacheFile(cache))).setMediaMetadata(metadata).setMediaId(cache.key).build(), position)
        exo.setPlaybackSpeed(if (asMusic) 1f else store.settings.value.playbackSpeed); exo.prepare(); exo.playWhenReady = true
        _state.value = PlaybackState(detail = detail, pageIndex = index, local = true, duration = detail.pages[index].duration * 1000, loading = true, music = asMusic, musicVisual = visual, cacheType = cache.type)
        startService()
    }
    fun saveHistory() {
        val s = _state.value; val d = s.detail ?: return; val e = d.pages.getOrNull(s.pageIndex) ?: return
        val p = exo.currentPosition.coerceAtLeast(0)
        scope.launch(Dispatchers.IO) { runCatching { store.record(d.video, e.cid, p) } }
    }
    fun reportHistory() {
        val s = _state.value; val e = currentEpisode() ?: return
        val aid = e.aid.takeIf { it > 0 } ?: s.detail?.video?.aid ?: return
        val p = exo.currentPosition / 1000
        scope.launch { runCatching { api.report(aid, e.cid, p) } }
    }
    fun setMusicVideo(show: Boolean) {
        val state = _state.value
        if (!state.music || state.musicVisual == show) return
        _state.value = state.copy(musicVisual = show)
        val audioCache = state.local && state.cacheType == "audio"
        if (show && audioCache) {
            val token = ++generation; val position = exo.currentPosition
            prepareJob?.cancel(); prepareJob = scope.launch {
                try { prepare(state.pageIndex, position, token, useCache = false) }
                catch (e: Exception) { if (e is CancellationException) throw e; _state.value = _state.value.copy(local = state.local, cacheType = state.cacheType, error = null, loading = false, musicVisual = false, notice = "原视频画面暂时无法读取，继续播放音频") }
            }
        }
        else if (!state.local) state.plan?.let { attach(it, exo.currentPosition.coerceAtLeast(0), autoplay = exo.playWhenReady || state.error != null) }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !show).build()
    }
    fun musicQuality(quality: MusicAudioQuality) {
        store.update(store.settings.value.copy(music = store.settings.value.music.copy(audioQuality = quality)))
        val state = _state.value
        if (!state.music || state.local) return
        val plan = state.plan ?: return
        val selected = selectMusicAudio(plan)
        if (selected.audio?.url != plan.audio?.url) { renewals = 0; attach(selected, exo.currentPosition.coerceAtLeast(0), exo.playWhenReady) }
    }
    fun resumeVideoTrack() { if (_state.value.detail != null) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, _state.value.music && !_state.value.musicVisual).build() }
    fun pauseForBackground() {
        if (_state.value.detail == null) return
        if (_state.value.music) { if (_state.value.musicVisual) setMusicVideo(false) }
        else if (!store.settings.value.background) { exo.pause(); saveHistory(); reportHistory() }
        else exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
    }
    fun stop() { music.snapshot(); saveHistory(); reportHistory(); prepareJob?.cancel(); dmJob?.cancel(); sponsorJob?.cancel(); generation++; exo.stop(); exo.clearMediaItems(); store.pinCache(null); _state.value = PlaybackState() }
    /** Final owner teardown, after activities and media sessions have detached. */
    fun release() { stop(); music.release(); scope.cancel(); exo.release() }
}
