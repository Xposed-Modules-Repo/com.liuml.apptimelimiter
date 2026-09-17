package com.liuml.apptimelimiter.core

/** A claimed attempt is not proof of success. Duplicates of pending/failed attempts fall back. */
class RestrictionExecutionLedger(private val maxEntries: Int = 128) {
    private val results = LinkedHashMap<String, RestrictionExecutionResult>()

    @Synchronized
    fun begin(key: String): RestrictionExecutionResult? {
        results[key]?.let { return it }
        results[key] = RestrictionExecutionResult.FALLBACK_REQUIRED
        while (results.size > maxEntries.coerceAtLeast(1)) results.remove(results.keys.first())
        return null
    }

    @Synchronized
    fun complete(key: String, result: RestrictionExecutionResult) {
        if (key !in results) return
        results[key] = if (result == RestrictionExecutionResult.EXECUTED) {
            RestrictionExecutionResult.ALREADY_EXECUTED
        } else RestrictionExecutionResult.FALLBACK_REQUIRED
    }
}
