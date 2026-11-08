// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.sync

/** Operations publish promptly; uninterrupted playback only needs a twenty-minute safety checkpoint. */
data class SyncTiming(val editDelayMs: Long = 800, val progressMs: Long = 20 * 60000L, val pollMs: Long = 20000,
    val backgroundPollMs: Long = 30000, val resumePollMs: Long = 5000, val resumeWindowMs: Long = 20000,
    val minReadGapMs: Long = 2000, val minUploadGapMs: Long = 1000, val retryMs: Long = 5000)

internal data class AutoSyncAction(val pull: Boolean, val push: Boolean)

/** All accesses are confined to the coordinator's main dispatcher. Taking a request never drops a later edit. */
internal class AutoSyncPolicy(private val timing: SyncTiming, private val now: () -> Long) {
    private var pushAt: Long? = null
    private var pullAt: Long? = null
    private var lastPush: Long? = null
    private var lastPull: Long? = null
    private var burstUntil = 0L
    private var blockedUntil = 0L
    private var hardBlock = false
    private var failures = 0
    private var pushUrgent = false

    fun changed(urgent: Boolean) {
        pushUrgent = pushUrgent || urgent
        val time = now()
        val due = if (urgent) time + timing.editDelayMs else maxOf(time, (lastPush ?: time) + timing.progressMs)
        pushAt = minOf(pushAt ?: Long.MAX_VALUE, maxOf(due, (lastPush ?: Long.MIN_VALUE / 2) + timing.minUploadGapMs))
    }
    fun requestPull(burst: Boolean = false) {
        if (burst) burstUntil = now() + timing.resumeWindowMs
        pullAt = minOf(pullAt ?: Long.MAX_VALUE, maxOf(now(), (lastPull ?: Long.MIN_VALUE / 2) + timing.minReadGapMs))
    }
    fun networkRecovered() { if (!hardBlock) { blockedUntil = 0; failures = 0 }; requestPull(true) }
    fun ensurePoll(active: Boolean, foreground: Boolean) {
        if (active && pullAt == null) {
            val interval = if (foreground && now() < burstUntil) timing.resumePollMs else if (foreground) timing.pollMs else timing.backgroundPollMs
            pullAt = (lastPull ?: now()) + interval
        }
    }
    fun take(active: Boolean): AutoSyncAction? {
        val time = now()
        if (time < blockedUntil) return null
        val pull = active && (pullAt?.let { it <= time } == true)
        val push = pushAt?.let { it <= time } == true
        if (!pull && !push) return null
        if (pull) pullAt = null
        if (push) { pushAt = null; pushUrgent = false }
        return AutoSyncAction(pull, push)
    }
    fun waitMillis(active: Boolean): Long {
        val next = listOfNotNull(pushAt, pullAt.takeIf { active }).minOrNull() ?: return 15000
        return (maxOf(next, blockedUntil) - now()).coerceIn(10, 15000)
    }
    fun success(pulled: Boolean, pushed: Boolean) {
        if (pulled) lastPull = now()
        if (pushed) {
            lastPush = now()
            pushAt?.let { pushAt = maxOf(it, now() + if (pushUrgent) timing.minUploadGapMs else timing.progressMs) }
        }
        failures = 0; blockedUntil = 0; hardBlock = false
    }
    fun failed(action: AutoSyncAction, status: Int = 0, retryAfter: Long = 0) {
        failures = (failures + 1).coerceAtMost(10)
        hardBlock = status in setOf(401, 403, 429, 507)
        val delay = when (status) {
            401, 403 -> 30 * 60000L
            429 -> maxOf(15 * 60000L, retryAfter.coerceAtMost(60 * 60000L))
            507 -> 60 * 60000L
            else -> (timing.retryMs * (1L shl (failures - 1))).coerceAtMost(5 * 60000L)
        }
        blockedUntil = now() + delay
        if (action.push) pushAt = blockedUntil
        if (action.pull) pullAt = blockedUntil
    }
    fun restoreBlock(remaining: Long, hard: Boolean) {
        if (hard && remaining > 0) { blockedUntil = now() + remaining.coerceAtMost(60 * 60000L); hardBlock = true }
    }
    val blockedMillis get() = (blockedUntil - now()).coerceAtLeast(0)
    val isHardBlocked get() = hardBlock
}
