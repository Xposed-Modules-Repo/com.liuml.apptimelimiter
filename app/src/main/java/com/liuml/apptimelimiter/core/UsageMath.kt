package com.liuml.apptimelimiter.core

object UsageMath {
    fun remainingMillis(limitMillis: Long, committedMillis: Long, activeSegmentMillis: Long): Long {
        val limit = limitMillis.coerceAtLeast(0L)
        val committed = committedMillis.coerceAtLeast(0L)
        val active = activeSegmentMillis.coerceAtLeast(0L)
        if (committed >= limit) return 0L
        val afterCommitted = limit - committed
        return if (active >= afterCommitted) 0L else afterCommitted - active
    }

    fun isLimitReached(limitMillis: Long, committedMillis: Long, activeSegmentMillis: Long): Boolean =
        remainingMillis(limitMillis, committedMillis, activeSegmentMillis) == 0L

    fun warningDelayMillis(remainingMillis: Long, warningLeadMillis: Long): Long =
        (remainingMillis - warningLeadMillis).coerceAtLeast(0L)

    fun addExtensionMillis(currentMillis: Long, perClickMillis: Long, maximumMillis: Long): Long {
        val maximum = maximumMillis.coerceAtLeast(0L)
        val current = currentMillis.coerceIn(0L, maximum)
        val increment = perClickMillis.coerceAtLeast(0L)
        return if (increment >= maximum - current) maximum else current + increment
    }

    fun earliestRemainingMillis(remainingValues: Iterable<Long>): Long? =
        remainingValues.minOrNull()?.coerceAtLeast(0L)

    fun authoritativeDailyUsedMillis(localMillis: Long, systemMillis: Long): Long =
        maxOf(localMillis.coerceAtLeast(0L), systemMillis.coerceAtLeast(0L))

    fun saturatedAddMillis(leftMillis: Long, rightMillis: Long): Long {
        val left = leftMillis.coerceAtLeast(0L)
        val right = rightMillis.coerceAtLeast(0L)
        return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
    }

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

object UsageReportingPolicy {
    /**
     * Duration samples are needed for the user-visible module statistics and for group daily
     * accounting. Heartbeats and launch/limit counters are still sent separately.
     */
    fun shouldReportDuration(
        usageStatsEnabled: Boolean,
        groupDailyEnabled: Boolean,
    ): Boolean = usageStatsEnabled || groupDailyEnabled
}

object DailyUsageStatePolicy {
    /**
     * Daily usage belongs to the package and calendar day, not to a particular rule revision.
     * Editing a limit, theme, or another rule field must never erase time already accumulated
     * today. A new day is the only automatic reset boundary.
     */
    fun shouldReset(
        savedDayToken: Int,
        currentDayToken: Int,
    ): Boolean = savedDayToken != currentDayToken
}
