// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

import org.junit.Assert.*
import org.junit.Test

class AutoSyncPolicyTest {
    private var time = 0L
    private val timing = SyncTiming()
    private fun policy() = AutoSyncPolicy(timing) { time }
    @Test fun editsUploadWithoutAPlayerOrForeground() {
        val p = policy(); p.changed(true)
        assertNull(p.take(false)); time = 800
        assertEquals(AutoSyncAction(false, true), p.take(false)); assertNull(p.take(false))
    }
    @Test fun pauseSoonAfterUploadIsNotDroppedByProgressThrottle() {
        val p = policy(); p.changed(true); time = 800; p.take(true); p.success(false, true)
        time = 1000; p.changed(false); time = 1200; p.changed(true)
        time = 1999; assertNull(p.take(true)); time = 2000
        assertEquals(AutoSyncAction(false, true), p.take(true))
    }
    @Test fun editsArrivingDuringAnUploadSurviveItsCompletion() {
        val p = policy(); p.changed(true); time = 800; p.take(true)
        time = 900; p.changed(true); time = 1200; p.success(false, true)
        time = 2199; assertNull(p.take(true)); time = 2200; assertTrue(p.take(true)!!.push)
    }
    @Test fun progressArrivingDuringAnUploadWaitsForTheNextCheckpointWindow() {
        val p = policy(); p.changed(true); time = 800; p.take(true)
        time = 900; p.changed(false); time = 1200; p.success(false, true)
        time = 1200 + timing.progressMs - 1; assertNull(p.take(true)); time++; assertTrue(p.take(true)!!.push)
    }
    @Test fun continuousProgressAndRemoteReadsWaitForTwentyMinuteSafetyUpload() {
        val p = policy(); p.changed(true); time = 800; assertTrue(p.take(true)!!.push); p.success(false, true)
        for (second in 5..1195 step 5) {
            time = second * 1000L; p.changed(false); p.ensurePoll(true, true)
            p.take(true)?.let { assertFalse("Progress/read caused an early upload at $second seconds", it.push); p.success(it.pull, false) }
        }
        time = 1200799; assertFalse(p.take(true)?.push == true)
        time = 1200800; assertTrue(p.take(true)!!.push)
    }
    @Test fun pollingDoesNotWriteOrReadWhenTheAppIsIdleInBackground() {
        val p = policy(); p.requestPull(true)
        assertNull(p.take(false)); assertEquals(AutoSyncAction(true, false), p.take(true))
        p.success(true, false); time = 100000; p.ensurePoll(false, false); assertNull(p.take(false))
    }
    @Test fun returningToMusicChecksPromptlyWithoutUnboundedDuplicateReads() {
        val p = policy(); p.requestPull(true); assertTrue(p.take(true)!!.pull); p.success(true, false)
        time = 300; repeat(10) { p.requestPull(true) }; assertNull(p.take(true))
        time = 2000; assertTrue(p.take(true)!!.pull); p.success(true, false)
        p.ensurePoll(true, true); time = 6999; assertNull(p.take(true)); time = 7000; assertTrue(p.take(true)!!.pull)
    }
    @Test fun aNetworkEventRecoversTransientFailuresButHonorsProviderLimits() {
        val p = policy(); p.failed(AutoSyncAction(true, true)); time = 1000; p.networkRecovered()
        assertEquals(AutoSyncAction(true, false), p.take(true))
        p.changed(true); p.failed(AutoSyncAction(true, true), 429, 20 * 60000)
        time = 10000; repeat(5) { p.networkRecovered(); p.changed(true) }; assertNull(p.take(true))
        time = 1201000; assertEquals(AutoSyncAction(true, true), p.take(true))
    }
    @Test fun authorizationAndQuotaFailuresRemainBackedOffAfterRestart() {
        val p = policy(); p.failed(AutoSyncAction(true, true), 401); assertEquals(1800000L, p.blockedMillis)
        val restored = policy(); restored.restoreBlock(p.blockedMillis, p.isHardBlocked); restored.requestPull(true); restored.changed(true)
        time = 1000; restored.networkRecovered(); assertNull(restored.take(true))
        time = 1800000; assertTrue(restored.take(true)!!.push)
        restored.failed(AutoSyncAction(false, true), 507); assertEquals(3600000L, restored.blockedMillis)
    }
    @Test fun twoActiveDevicesStayBelowSixHundredRequestsPerHalfHourWithEtags() {
        val p = policy(); p.requestPull(true); p.changed(true)
        var requests = 0; var lastRemoteRevision = -1L
        for (step in 0..17999) {
            time = step * 100L
            if (time % 15000L == 0L) p.changed(false)
            p.ensurePoll(true, true)
            p.take(true)?.let { action ->
                if (action.pull) { requests++; val remoteRevision = time / 30000; if (lastRemoteRevision != remoteRevision) { requests++; lastRemoteRevision = remoteRevision } }
                if (action.push) requests++
                p.success(action.pull, action.push)
            }
        }
        assertTrue("Two-device polling would exhaust the free-tier request budget: ${requests * 2}", requests * 2 < 600)
    }
}
