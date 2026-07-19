package com.liuml.apptimelimiter.core

object CooldownPolicy {
    fun remainingMillis(
        startedAtMillis: Long,
        durationMillis: Long,
        nowMillis: Long,
    ): Long {
        if (startedAtMillis <= 0L || durationMillis <= 0L) return 0L
        val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return (durationMillis - elapsedMillis).coerceAtLeast(0L)
    }
}
