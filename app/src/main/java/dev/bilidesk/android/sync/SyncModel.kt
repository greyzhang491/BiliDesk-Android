// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import org.json.JSONArray
import org.json.JSONObject

/** Hybrid logical revisions keep observed edits ordered even if a device's clock moves back. */
data class SyncStamp(val time: Long, val tick: Long, val device: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp) = compareValuesBy(this, other, SyncStamp::time, SyncStamp::tick, SyncStamp::device)
    fun json() = JSONArray(listOf(time, tick, device))
    companion object {
        fun from(a: JSONArray): SyncStamp {
            require(a.length() == 3 && a.getLong(0) >= 0 && a.getLong(1) >= 0)
            return SyncStamp(a.getLong(0), a.getLong(1), a.getString(2).also { require(it.matches(Regex("[a-zA-Z0-9-]{1,64}"))) })
        }
    }
}

data class SyncSong(val track: MusicTrack, val order: Int, val removed: Boolean, val stamp: SyncStamp) {
    fun json() = JSONObject().put("track", syncTrack(track)).put("order", order).put("removed", removed).put("stamp", stamp.json())
}
data class SyncPlaylist(val id: String, val name: String, val nameStamp: SyncStamp, val created: SyncStamp,
    val deleted: SyncStamp? = null, val songs: Map<String, SyncSong> = emptyMap()) {
    fun json() = JSONObject().put("id", id).put("name", name).put("nameStamp", nameStamp.json()).put("created", created.json())
        .put("deleted", deleted?.json()).put("songs", JSONArray(songs.toSortedMap().values.map(SyncSong::json)))
    fun project() = MusicPlaylist(id, name, songs.values.filterNot { it.removed }.sortedWith(compareBy({ it.order }, { it.stamp }, { it.track.key })).map { it.track })
    fun merge(other: SyncPlaylist): SyncPlaylist {
        require(id == other.id)
        val named = if (nameStamp >= other.nameStamp) this else other
        return named.copy(created = minOf(created, other.created), deleted = listOfNotNull(deleted, other.deleted).maxOrNull(),
            songs = (songs.keys + other.songs.keys).associateWith { key -> listOfNotNull(songs[key], other.songs[key]).maxBy { it.stamp } })
    }
}

/** A checkpoint is atomic: a position must never be paired with another song or another queue. */
data class SyncCheckpoint(val queue: MusicQueueState, val position: Long, val deviceName: String, val stamp: SyncStamp) {
    fun json() = JSONObject().put("queue", syncQueue(queue, position)).put("deviceName", deviceName).put("stamp", stamp.json())
}
data class SyncProgress(val entry: HistoryEntry, val stamp: SyncStamp) {
    fun json() = JSONObject().put("video", syncVideo(entry.video)).put("cid", entry.cid).put("position", entry.positionMs).put("at", entry.at).put("stamp", stamp.json())
}

/** Device labels have their own revision; renaming never advances a playback checkpoint. */
data class SyncDeviceName(val device: String, val name: String, val stamp: SyncStamp) {
    fun json() = JSONObject().put("device", device).put("name", name).put("stamp", stamp.json())
    companion object {
        fun validName(value: String): String = value.trim().also { name ->
            require(name.length in 1..40 && name.none { it.isISOControl() }) { "设备名称请填写1～40个字" }
        }
        fun from(j: JSONObject) = SyncDeviceName(j.getString("device").also { require(it.matches(Regex("[a-zA-Z0-9-]{1,64}"))) },
            validName(j.getString("name")), SyncStamp.from(j.getJSONArray("stamp")))
    }
}

data class SyncDocument(val playlists: Map<String, SyncPlaylist> = emptyMap(), val checkpoint: SyncCheckpoint? = null,
    val progress: Map<String, SyncProgress> = emptyMap(), val checkpoints: Map<String, SyncCheckpoint> = emptyMap(),
    val deviceNames: Map<String, SyncDeviceName> = emptyMap()) {
    fun json() = JSONObject().put("version", 1).put("playlists", JSONArray(playlists.toSortedMap().values.map(SyncPlaylist::json)))
        .put("checkpoint", checkpoint?.json()).put("progress", JSONArray(progress.toSortedMap().values.map(SyncProgress::json)))
        .put("checkpoints", JSONArray(allCheckpoints().toSortedMap().values.map(SyncCheckpoint::json)))
        .put("deviceNames", JSONArray(deviceNames.toSortedMap().values.map(SyncDeviceName::json)))
    fun allCheckpoints(): Map<String, SyncCheckpoint> = checkpoints + listOfNotNull(checkpoint).filter { (checkpoints[it.stamp.device]?.stamp ?: it.stamp) <= it.stamp }.associateBy { it.stamp.device }
    fun withCheckpoint(value: SyncCheckpoint): SyncDocument {
        val all = allCheckpoints() + (value.stamp.device to value)
        return copy(checkpoint = all.values.maxByOrNull { it.stamp }, checkpoints = all)
    }
    fun library(): MusicLibraryState {
        val live = playlists.values.filter { it.deleted == null }.sortedWith(compareBy({ it.created }, { it.id })).map { it.project() }
        require(live.size <= 200 && live.all { it.tracks.size <= 5000 }) { "合并后的歌单超过数量限制，本机歌单未被覆盖" }
        return MusicLibraryState(live)
    }
    fun merge(other: SyncDocument): SyncDocument {
        val lists = (playlists.keys + other.playlists.keys).associateWith { id ->
            val a = playlists[id]; val b = other.playlists[id]
            if (a != null && b != null) a.merge(b) else a ?: b!!
        }
        val history = (progress.keys + other.progress.keys).associateWith { key -> listOfNotNull(progress[key], other.progress[key]).maxBy { it.stamp } }
            .values.sortedByDescending { it.stamp }.take(200).associateBy { it.entry.video.key }
        val a = allCheckpoints(); val b = other.allCheckpoints()
        val cps = (a.keys + b.keys).associateWith { key -> listOfNotNull(a[key], b[key]).maxBy { it.stamp } }
        require(cps.size <= 16) { "同步设备数量超过16台" }
        val names = (deviceNames.keys + other.deviceNames.keys).associateWith { id -> listOfNotNull(deviceNames[id], other.deviceNames[id]).maxBy { it.stamp } }
        require(names.size <= 16) { "同步设备数量超过16台" }
        return SyncDocument(lists, cps.values.maxByOrNull { it.stamp }, history, cps, names)
    }
    fun latest(): SyncStamp? = (playlists.values.flatMap { p -> listOfNotNull(p.nameStamp, p.created, p.deleted) + p.songs.values.map { it.stamp } } +
        progress.values.map { it.stamp } + allCheckpoints().values.map { it.stamp } + deviceNames.values.map { it.stamp }).maxOrNull()

    /** Apply only actual local changes since the previous projection, preserving unseen remote edits. */
    fun captureLibrary(before: MusicLibraryState, now: MusicLibraryState, next: () -> SyncStamp): SyncDocument {
        val result = playlists.toMutableMap()
        val previous = before.playlists.associateBy { it.id }
        val current = now.playlists.associateBy { it.id }
        require(current.size <= 200)
        for ((id, old) in previous) if (id !in current) {
            result[id]?.let { if (it.deleted == null) result[id] = it.copy(deleted = next()) }
        }
        for ((id, local) in current) {
            val old = previous[id]
            var item = result[id] ?: next().let { SyncPlaylist(id, local.name, it, it) }
            if (item.deleted != null) continue // Deletion is permanent for this UUID; recreating uses a fresh UUID.
            if (old != null && old.name != local.name) item = item.copy(name = local.name, nameStamp = next())
            val songs = item.songs.toMutableMap()
            val oldTracks = old?.tracks.orEmpty().associateBy { it.key }
            val localTracks = local.tracks.associateBy { it.key }
            oldTracks.keys.filter { it !in localTracks }.forEach { key -> songs[key]?.let { if (!it.removed) songs[key] = it.copy(removed = true, stamp = next()) } }
            local.tracks.forEachIndexed { index, track ->
                if (track.key !in oldTracks) {
                    val order = if (old == null) index else (songs.values.maxOfOrNull { it.order } ?: -1) + 1
                    songs[track.key] = SyncSong(track, order, false, next())
                }
            }
            result[id] = item.copy(songs = songs)
        }
        return copy(playlists = result)
    }

    companion object {
        fun from(j: JSONObject): SyncDocument {
            require(j.getInt("version") == 1) { "同步数据版本不受支持，请更新应用" }
            val lists = j.getJSONArray("playlists"); val history = j.getJSONArray("progress")
            require(lists.length() <= 1000 && history.length() <= 200)
            var count = 0
            val parsed = lists.objects().map { p ->
                val songs = p.getJSONArray("songs"); count += songs.length(); require(count <= 100000)
                val entries = songs.objects().map { s -> SyncSong(readTrack(s.getJSONObject("track")), s.getInt("order").also { require(it >= 0) }, s.getBoolean("removed"), SyncStamp.from(s.getJSONArray("stamp"))) }
                require(entries.size == songs.length())
                require(entries.map { it.track.key }.distinct().size == entries.size)
                SyncPlaylist(p.getString("id").also { require(it.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }, MusicLibraryState.validName(p.getString("name")),
                    SyncStamp.from(p.getJSONArray("nameStamp")), SyncStamp.from(p.getJSONArray("created")), p.optJSONArray("deleted")?.let(SyncStamp::from), entries.associateBy { it.track.key })
            }
            require(parsed.size == lists.length() && parsed.map { it.id }.distinct().size == parsed.size && parsed.count { it.deleted == null } <= 200)
            fun checkpoint(c: JSONObject): SyncCheckpoint {
                val queue = c.getJSONObject("queue"); require(queue.getJSONArray("tracks").length() <= 5000)
                val tracks = queue.getJSONArray("tracks").objects().map(::readTrack)
                require(tracks.size == queue.getJSONArray("tracks").length() && tracks.map { it.key }.distinct().size == tracks.size)
                val keys = tracks.map { it.key }.toSet()
                require(queue.getString("current").let { it.isEmpty() && tracks.isEmpty() || it in keys })
                listOf("next", "remaining", "history").forEach { field ->
                    val values = queue.getJSONArray(field); require(values.length() <= 10000)
                    require((0 until values.length()).all { values.getString(it) in keys })
                }
                val normalized = JSONObject(queue.toString()).put("tracks", JSONArray(tracks.map(MusicTrack::json)))
                return SyncCheckpoint(MusicQueueState.from(normalized), queue.getLong("position").also { require(it >= 0) }, c.getString("deviceName").take(40), SyncStamp.from(c.getJSONArray("stamp")))
            }
            val cp = j.optJSONObject("checkpoint")?.let(::checkpoint)
            val cps = j.optJSONArray("checkpoints")?.objects()?.map(::checkpoint).orEmpty()
            require(cps.size <= 16 && cps.size == (j.optJSONArray("checkpoints")?.length() ?: 0) && cps.map { it.stamp.device }.distinct().size == cps.size)
            val progress = history.objects().map { h ->
                val video = readVideo(h.getJSONObject("video")); val cid = h.getLong("cid"); val position = h.getLong("position"); val at = h.getLong("at")
                require(cid > 0 && position >= 0 && at >= 0)
                SyncProgress(HistoryEntry(video, cid, position, at), SyncStamp.from(h.getJSONArray("stamp")))
            }
            require(progress.size == history.length() && progress.map { it.entry.video.key }.distinct().size == progress.size)
            val rawNames = j.optJSONArray("deviceNames")
            val names = rawNames?.objects()?.map(SyncDeviceName::from).orEmpty()
            require(names.size <= 16 && names.size == (rawNames?.length() ?: 0) && names.map { it.device }.distinct().size == names.size)
            return SyncDocument(parsed.associateBy { it.id }, cp, progress.associateBy { it.entry.video.key }, cps.associateBy { it.stamp.device }, names.associateBy { it.device })
        }
    }
}

class SyncClock(private val device: String, initial: SyncStamp? = null, private val now: () -> Long = System::currentTimeMillis) {
    private var last = initial ?: SyncStamp(0, 0, device)
    fun observe(stamp: SyncStamp?) { if (stamp != null && stamp > last) last = stamp }
    fun next(): SyncStamp { val time = maxOf(now().coerceAtLeast(0), last.time); last = SyncStamp(time, if (time == last.time) last.tick + 1 else 0, device); return last }
}

// Only durable content identifiers and display text cross the sync boundary. No CDN URLs or cookies.
private fun syncVideo(v: Video) = JSONObject().put("bvid", v.bvid).put("aid", v.aid).put("title", v.title.take(500)).put("author", v.author.take(100)).put("mid", v.mid).put("duration", v.duration)
private fun readVideo(j: JSONObject): Video {
    val v = Video.from(j)
    require((v.bvid.matches(Regex("BV[a-zA-Z0-9]{10}")) || v.aid > 0) && v.kind == "video" && v.title.length <= 500 && v.author.length <= 100)
    return Video(bvid = v.bvid, aid = v.aid, title = v.title, author = v.author, mid = v.mid, duration = v.duration)
}
private fun trackKey(video: Video, cid: Long, key: String): String {
    require(cid >= 0 && key.matches(Regex("(?:BV[a-zA-Z0-9]{10}|av[0-9]+):[0-9]+")) && key.substringAfterLast(':').toLong() == cid)
    val identity = key.substringBeforeLast(':')
    require(identity == video.key || video.aid > 0 && identity == "av${video.aid}")
    return key
}
private fun syncTrack(t: MusicTrack) = JSONObject().put("video", syncVideo(t.video)).put("cid", t.cid).put("title", t.title.take(500)).put("key", trackKey(t.video, t.cid, t.key))
private fun readTrack(j: JSONObject): MusicTrack {
    val video = readVideo(j.getJSONObject("video")); val cid = j.getLong("cid"); val title = j.getString("title").also { require(it.length <= 500) }
    // Fetching metadata can add a BV number to a formerly AV-only song. Its durable queue key stays unchanged.
    return MusicTrack(video, cid, title, trackKey(video, cid, j.optString("key", "${video.key}:$cid")))
}
private fun syncQueue(q: MusicQueueState, p: Long) = q.json(p).put("tracks", JSONArray(q.tracks.map(::syncTrack)))
