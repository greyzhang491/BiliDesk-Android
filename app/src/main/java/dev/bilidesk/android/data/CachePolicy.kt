// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

data class CacheBudgetItem(val key: String, val bytes: Long, val automatic: Boolean, val accessedAt: Long)

object CachePolicy {
    const val MIB = 1024L * 1024
    const val IMAGE_LIMIT = 64L * MIB
    fun evictions(items: List<CacheBudgetItem>, limitBytes: Long, maxAgeMs: Long, now: Long, reservedBytes: Long = 0, protectedKeys: Set<String> = emptySet()): Set<String> {
        val automatic = items.filter { it.automatic }
        val removable = automatic.filter { it.key !in protectedKeys }.sortedBy { it.accessedAt }
        val deleted = removable.filter { now - it.accessedAt >= maxAgeMs.coerceAtLeast(0) }.mapTo(linkedSetOf()) { it.key }
        var remaining = automatic.filter { it.key !in deleted }.sumOf { it.bytes.coerceAtLeast(0) }
        for (item in removable) {
            if (remaining + reservedBytes.coerceAtLeast(0) <= limitBytes.coerceAtLeast(0)) break
            if (deleted.add(item.key)) remaining -= item.bytes.coerceAtLeast(0)
        }
        return deleted
    }
}
