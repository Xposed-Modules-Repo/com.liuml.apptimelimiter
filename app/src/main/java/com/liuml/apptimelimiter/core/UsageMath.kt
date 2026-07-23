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

    fun authoritativeDailyUsedMillis(localMillis: Long, systemMillis: Long): Long =
        maxOf(localMillis.coerceAtLeast(0L), systemMillis.coerceAtLeast(0L))

    fun activeIncludedAtMeasurementMillis(
        foregroundStartedAtElapsedMillis: Long,
        measuredAtElapsedMillis: Long,
        activeTodayMillis: Long,
    ): Long = (measuredAtElapsedMillis - foregroundStartedAtElapsedMillis)
        .coerceIn(0L, activeTodayMillis.coerceAtLeast(0L))

    fun activeAfterMeasurementMillis(
        nowElapsedMillis: Long,
        foregroundStartedAtElapsedMillis: Long,
        measuredAtElapsedMillis: Long,
    ): Long = (nowElapsedMillis - maxOf(
        foregroundStartedAtElapsedMillis,
        measuredAtElapsedMillis,
    )).coerceAtLeast(0L)

    fun committedSystemUsageMillis(systemUsageMillis: Long, activeIncludedMillis: Long): Long =
        (systemUsageMillis - activeIncludedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)

    fun projectedSystemUsageMillis(systemUsageMillis: Long, activeAfterMeasurementMillis: Long): Long {
        if (systemUsageMillis < 0L) return -1L
        val increment = activeAfterMeasurementMillis.coerceAtLeast(0L)
        return if (increment > Long.MAX_VALUE - systemUsageMillis) {
            Long.MAX_VALUE
        } else {
            systemUsageMillis + increment
        }
    }
}
