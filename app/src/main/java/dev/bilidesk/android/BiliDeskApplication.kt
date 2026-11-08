// SPDX-License-Identifier: GPL-3.0-only
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.bilidesk.android

import android.app.Application
import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.PlayerEngine
import dev.bilidesk.android.download.Downloads
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import dev.bilidesk.android.sync.MusicSync
import dev.bilidesk.android.sync.SyncUploadWorker

class BiliDeskApplication: Application(), ImageLoaderFactory {
    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient { Graph.api.http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS).readTimeout(7, java.util.concurrent.TimeUnit.SECONDS).callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", BILI_UA).header("Referer", "https://www.bilibili.com/").build()) }.build() }
        .diskCache { DiskCache.Builder().directory(File(cacheDir, "image_cache")).maxSizeBytes(CachePolicy.IMAGE_LIMIT).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(.12).build() }.build()
    override fun onCreate() {
        super.onCreate()
        Graph.store = Store(this)
        Graph.cookies = SecureCookies(this)
        Graph.api = BiliApi(Graph.cookies, Graph.store)
        Graph.player = PlayerEngine(this, Graph.api, Graph.store)
        Graph.downloads = Downloads(this, Graph.api, Graph.store)
        Graph.musicSync = MusicSync(this, Graph.player, Graph.store,
            pendingWork = { SyncUploadWorker.enqueue(this) }, cancelWork = { SyncUploadWorker.cancel(this) })
    }
}
object Graph {
    lateinit var store: Store
    lateinit var cookies: SecureCookies
    lateinit var api: BiliApi
    lateinit var player: PlayerEngine
    lateinit var downloads: Downloads
    lateinit var musicSync: MusicSync
}
