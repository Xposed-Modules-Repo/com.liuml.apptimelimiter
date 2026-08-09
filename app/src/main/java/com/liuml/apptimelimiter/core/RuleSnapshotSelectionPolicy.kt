package com.liuml.apptimelimiter.core

object RuleSnapshotSelectionPolicy {
    fun shouldUseShared(
        sharedGeneration: Long,
        sharedVersion: Long,
        cachedGeneration: Long?,
        cachedVersion: Long?,
    ): Boolean {
        if (cachedGeneration == null || cachedVersion == null) return true
        return sharedGeneration > cachedGeneration ||
            (sharedGeneration == cachedGeneration && sharedVersion >= cachedVersion)
    }

    /**
     * XSharedPreferences can contain a newer shared cooldown written by another group member,
     * while the target process cache can contain the last provider snapshot. Prefer the record
     * with the later fixed end time so an old cache cannot hide an active shared cooldown.
     */
    fun shouldUseSharedCooldown(
        sharedEndsAtMillis: Long,
        cachedEndsAtMillis: Long,
    ): Boolean = sharedEndsAtMillis.coerceAtLeast(0L) >=
        cachedEndsAtMillis.coerceAtLeast(0L)
}
