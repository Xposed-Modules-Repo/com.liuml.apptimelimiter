package com.liuml.apptimelimiter.update

/** Process-local entry identities survive recomposition/rotation, not another genuine opening. */
object MainEntryUpdatePolicy {
    private var nextId = 0L
    private var claimedId = 0L
    @Synchronized fun nextEntry(): Long = ++nextId
    @Synchronized fun isKnown(entryId: Long): Boolean = entryId > 0L && entryId <= nextId
    @Synchronized fun claim(entryId: Long): Boolean {
        if (entryId <= 0L || entryId <= claimedId) return false
        claimedId = entryId
        return true
    }
}
