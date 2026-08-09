package com.liuml.apptimelimiter.diagnostics

class DiagnosticEventLimiter(
    private val maxEntries: Int = 192,
) {
    private val lastAcceptedAt = LinkedHashMap<String, Long>(maxEntries, 0.75f, true)

    @Synchronized
    fun shouldAccept(
        key: String,
        nowMillis: Long,
        windowMillis: Long,
    ): Boolean {
        val cleanKey = key.take(MAX_KEY_LENGTH)
        val previous = lastAcceptedAt[cleanKey]
        if (
            previous != null &&
            nowMillis >= previous &&
            nowMillis - previous < windowMillis.coerceAtLeast(0L)
        ) {
            return false
        }
        lastAcceptedAt[cleanKey] = nowMillis
        while (lastAcceptedAt.size > maxEntries.coerceAtLeast(1)) {
            lastAcceptedAt.remove(lastAcceptedAt.keys.first())
        }
        return true
    }

    private companion object {
        const val MAX_KEY_LENGTH = 512
    }
}
