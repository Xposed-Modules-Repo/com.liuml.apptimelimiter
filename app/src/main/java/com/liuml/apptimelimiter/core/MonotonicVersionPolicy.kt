package com.liuml.apptimelimiter.core

object MonotonicVersionPolicy {
    fun next(
        previousVersion: Long,
        wallClockMillis: Long,
    ): Long {
        val incremented = if (previousVersion == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            previousVersion + 1L
        }
        return maxOf(wallClockMillis.coerceAtLeast(0L), incremented)
    }
}
