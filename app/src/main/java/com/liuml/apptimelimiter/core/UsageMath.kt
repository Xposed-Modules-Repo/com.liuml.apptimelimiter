package com.liuml.apptimelimiter.core

object UsageMath {
    fun remainingMillis(limitMillis: Long, committedMillis: Long, activeSegmentMillis: Long): Long =
        (limitMillis - committedMillis - activeSegmentMillis).coerceAtLeast(0L)

    fun isLimitReached(limitMillis: Long, committedMillis: Long, activeSegmentMillis: Long): Boolean =
        remainingMillis(limitMillis, committedMillis, activeSegmentMillis) == 0L

    fun warningDelayMillis(remainingMillis: Long, warningLeadMillis: Long): Long =
        (remainingMillis - warningLeadMillis).coerceAtLeast(0L)

    fun addExtensionMillis(currentMillis: Long, perClickMillis: Long, maximumMillis: Long): Long =
        (currentMillis + perClickMillis).coerceAtMost(maximumMillis)

    fun earliestRemainingMillis(remainingValues: Iterable<Long>): Long? =
        remainingValues.minOrNull()?.coerceAtLeast(0L)
}
