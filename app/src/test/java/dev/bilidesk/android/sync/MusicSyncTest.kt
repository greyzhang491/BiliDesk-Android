// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import dev.bilidesk.android.data.*
import dev.bilidesk.android.player.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class MusicSyncTest {
    private val a = MusicTrack(Video(bvid = "BV156xx6wEff", title = "UI DEMO A"), 11, "上集")
    private val b = MusicTrack(a.video, 12, "下集")
    private val c = MusicTrack(Video(bvid = "BV17x411w7KC", title = "UI DEMO B"), 21)
    private val empty = MusicLibraryState()
    private fun library(vararg tracks: MusicTrack) = MusicLibraryState(listOf(MusicPlaylist("list-a", "我的歌单", tracks.toList())))
    private fun initial(lib: MusicLibraryState) = SyncDocument().captureLibrary(empty, lib, SyncClock("first", now = { 1000 }).let { it::next })

    @Test fun deviceRenamingMergesSeparatelyWithoutChangingPlaybackRecencyOrQueue() {
        val checkpoint = SyncCheckpoint(MusicQueueState(listOf(a, b), a.key, MusicMode.SHUFFLE, listOf(b.key)), 14995, "原手机", SyncStamp(1000, 0, "phone"))
        val original = SyncDocument().withCheckpoint(checkpoint)
        val label = SyncDeviceName("phone", "随身手机", SyncStamp(3000, 0, "tablet"))
        val renamed = original.copy(deviceNames = mapOf("phone" to label))
        val merged = original.merge(renamed)
        assertEquals(checkpoint, merged.allCheckpoints()["phone"])
        assertEquals(label, merged.deviceNames["phone"])
        assertEquals(merged, SyncDocument.from(merged.json())); assertEquals(label.stamp, merged.latest())
        // An older client omits the optional field; merging its checkpoint must keep the shared label.
        val legacy = original.json().apply { remove("deviceNames") }
        assertEquals(label, merged.merge(SyncDocument.from(legacy)).deviceNames["phone"])
    }
    @Test fun concurrentDeviceNamesConvergeAndRejectMalformedRemoteNames() {
        val phone = SyncDeviceName("phone", "工作手机", SyncStamp(2000, 0, "phone"))
        val tablet = SyncDeviceName("phone", "随身手机", SyncStamp(3000, 0, "tablet"))
        val a = SyncDocument(deviceNames = mapOf("phone" to phone)); val b = SyncDocument(deviceNames = mapOf("phone" to tablet))
        assertEquals(a.merge(b), b.merge(a)); assertEquals("随身手机", a.merge(b).deviceNames["phone"]!!.name)
        val malformed = a.json().apply { getJSONArray("deviceNames").getJSONObject(0).put("name", "错误\n名字") }
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.from(malformed) }
    }

    @Test fun concurrentSongAdditionsMergeAndConvergeInEitherDirection() {
        val base = library(a); val doc = initial(base)
        val phone = doc.captureLibrary(base, library(a, b), SyncClock("phone", doc.latest(), { 2000 }).let { it::next })
        val tablet = doc.captureLibrary(base, library(a, c), SyncClock("tablet", doc.latest(), { 2000 }).let { it::next })
        val merged = phone.merge(tablet)
        assertEquals(setOf(a.key, b.key, c.key), merged.library().playlists.single().tracks.map { it.key }.toSet())
        assertEquals(merged.json().toString(), tablet.merge(phone).json().toString())
        assertEquals(merged.json().toString(), merged.merge(merged).json().toString())
    }
    @Test fun songDeletionIsNotUndoneByAnUnrelatedOfflineRename() {
        val base = library(a, b); val doc = initial(base)
        val phone = doc.captureLibrary(base, library(a), SyncClock("phone", doc.latest(), { 3000 }).let { it::next })
        val tablet = doc.captureLibrary(base, base.rename("list-a", "轻音乐"), SyncClock("tablet", doc.latest(), { 4000 }).let { it::next })
        val merged = phone.merge(tablet).library().playlists.single()
        assertEquals("轻音乐", merged.name); assertEquals(listOf(a.key), merged.tracks.map { it.key })
    }
    @Test fun deletedPlaylistCannotBeResurrectedByAStaleDevice() {
        val base = library(a); val doc = initial(base)
        val deleted = doc.captureLibrary(base, empty, SyncClock("phone", doc.latest(), { 2000 }).let { it::next })
        val offline = doc.captureLibrary(base, library(a, b), SyncClock("tablet", doc.latest(), { 3000 }).let { it::next })
        assertTrue(deleted.merge(offline).library().playlists.isEmpty())
        assertNotNull(SyncDocument.from(deleted.merge(offline).json()).playlists["list-a"]!!.deleted)
    }
    @Test fun separatePartsAndSameNamedPlaylistsRemainDistinct() {
        val first = library(a, b); val second = MusicLibraryState(listOf(MusicPlaylist("list-b", "我的歌单", listOf(c))))
        val doc = initial(first).merge(initial(second))
        assertEquals(2, doc.library().playlists.size)
        assertEquals(listOf(11L, 12L), doc.library().playlists.first { it.id == "list-a" }.tracks.map { it.cid })
    }
    @Test fun unchangedLocalProjectionDoesNotCreateNewEditsOrDeleteUnseenRemoteSongs() {
        val base = library(a); val doc = initial(base)
        val remote = doc.captureLibrary(base, library(a, b), SyncClock("remote", doc.latest(), { 2000 }).let { it::next })
        val captured = remote.captureLibrary(base, base) { error("No local change should create a revision") }
        assertEquals(listOf(a.key, b.key), captured.library().playlists.single().tracks.map { it.key })
    }
    @Test fun clockDoesNotGoBackwardsAndObservesRemoteRevision() {
        var now = 500L; val clock = SyncClock("a", now = { now }); val one = clock.next(); now = 100
        val two = clock.next(); assertTrue(two > one)
        val remote = SyncStamp(1000, 10, "z"); clock.observe(remote); assertTrue(clock.next() > remote)
    }
    @Test fun checkpointKeepsQueueNextUpShuffleAndPositionAtomic() {
        val queue = MusicQueueState(listOf(a, b, c), b.key, MusicMode.SHUFFLE, listOf(c.key), listOf(a.key, c.key), listOf(a.key))
        val cp = SyncCheckpoint(queue, 195321, "我的手机", SyncStamp(100, 0, "phone"))
        val doc = SyncDocument().withCheckpoint(cp)
        val restored = SyncDocument.from(doc.json()).checkpoint!!
        assertEquals(queue, restored.queue); assertEquals(195321, restored.position); assertEquals(12L, restored.queue.current!!.cid)
        assertEquals(cp, doc.merge(SyncDocument()).checkpoint)
    }
    @Test fun eachDeviceCheckpointSurvivesEvenWhenAnotherDeviceHasANewerLocalQueue() {
        val phone = SyncCheckpoint(MusicQueueState(listOf(a), a.key), 42000, "手机", SyncStamp(100, 0, "phone"))
        val tablet = SyncCheckpoint(MusicQueueState(listOf(b), b.key), 9000, "平板", SyncStamp(200, 0, "tablet"))
        val doc = SyncDocument().withCheckpoint(phone).merge(SyncDocument().withCheckpoint(tablet))
        assertEquals(2, SyncDocument.from(doc.json()).allCheckpoints().size)
        assertEquals(42000, doc.allCheckpoints()["phone"]!!.position)
    }
    @Test fun avOnlySongKeepsItsDurableQueueKeyAfterMetadataAddsABvNumber() {
        val original = MusicTrack(Video(aid = 100001, title = "AV歌曲"), 101)
        val hydrated = original.copy(video = original.video.copy(bvid = "BV156xx6wEff"))
        val queue = MusicQueueState(listOf(hydrated, b), hydrated.key, MusicMode.SHUFFLE, listOf(hydrated.key), listOf(b.key), listOf(hydrated.key))
        val cp = SyncCheckpoint(queue, 3000, "手机", SyncStamp(1000, 0, "phone"))
        val result = SyncDocument.from(initial(library(hydrated)).withCheckpoint(cp).json())
        assertEquals(queue, result.checkpoint!!.queue)
        assertEquals(original.key, result.library().playlists.single().tracks.single().key)
        assertEquals("BV156xx6wEff", result.checkpoint!!.queue.current!!.video.bvid)
    }
    @Test fun cloudCodecOnlyIncludesWhitelistedMetadata() {
        val video = a.video.copy(cover = "https://example.invalid/private?signature=DO-NOT-SYNC", authorAvatar = "DO-NOT-SYNC", subtitle = "DO-NOT-SYNC")
        val doc = initial(library(a.copy(video = video))).withCheckpoint(SyncCheckpoint(MusicQueueState(listOf(a.copy(video = video)), a.key), 13, "设备", SyncStamp(1000, 1, "a")))
        assertFalse(doc.json().toString().contains("DO-NOT-SYNC"))
        val restored = SyncDocument.from(doc.json())
        assertEquals(a.video.title, restored.checkpoint!!.queue.current!!.video.title)
        assertEquals("", restored.checkpoint!!.queue.current!!.video.cover)
    }
    @Test fun encryptedRoundTripUsesDifferentNoncesAndHidesTitles() {
        val key = SyncCrypto.newKey(); val doc = initial(library(a, b))
        val first = SyncCrypto.encode(doc, key); val second = SyncCrypto.encode(doc, key)
        assertFalse(first.contentEquals(second)); assertFalse(first.toString(Charsets.ISO_8859_1).contains(a.displayTitle))
        assertEquals(doc.json().toString(), SyncCrypto.decode(first, key).json().toString())
    }
    @Test fun wrongKeyAndTamperingAreRejectedWithoutProducingPartialData() {
        val key = SyncCrypto.newKey(); val original = SyncCrypto.encode(initial(library(a)), key)
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decode(original, SyncCrypto.newKey()) }
        val modified = original.copyOf(); modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.decode(modified, key) }
    }
    @Test fun malformedKeysUnsupportedVersionsAndDuplicateSongsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.key("short") }
        val doc = initial(library(a)).json(); doc.put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.from(doc) }
        doc.put("version", 1); val songs = doc.getJSONArray("playlists").getJSONObject(0).getJSONArray("songs"); songs.put(songs.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { SyncDocument.from(doc) }
    }
    @Test fun oversizedPlainAndIncompressibleDocumentsAreBounded() {
        val key = SyncCrypto.newKey()
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.encrypt(ByteArray(SyncCrypto.MAX_PLAIN + 1), key) }
        val random = ByteArray(SyncCrypto.MAX_ENCRYPTED + 4096).also { SecureRandom().nextBytes(it) }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.encrypt(random, key) }
    }
    @Test fun progressMergeIsBoundedAndRetainsLatestPartOfAVideo() {
        val first = SyncProgress(HistoryEntry(a.video, 11, 10000, 100), SyncStamp(100, 0, "phone"))
        val second = SyncProgress(HistoryEntry(a.video, 12, 20000, 200), SyncStamp(200, 0, "tablet"))
        val merged = SyncDocument(progress = mapOf(a.video.key to first)).merge(SyncDocument(progress = mapOf(a.video.key to second)))
        assertEquals(12L, SyncDocument.from(merged.json()).progress[a.video.key]!!.entry.cid)
        assertEquals(20000, merged.progress[a.video.key]!!.entry.positionMs)
    }
}
