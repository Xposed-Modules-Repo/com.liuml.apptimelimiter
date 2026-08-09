package com.liuml.apptimelimiter.statistics

object UsageSummaryMergePolicy {
    fun authoritativeLaunchCount(
        moduleLaunchCount: Int,
        systemLaunchCount: Int?,
    ): Int = maxOf(
        moduleLaunchCount.coerceAtLeast(0),
        systemLaunchCount?.coerceAtLeast(0) ?: 0,
    )

    /**
     * Per-app foreground intervals can overlap in split-screen or when a ROM omits a pause event.
     * Prefer the system-event interval union for the dashboard total. Without usage access, use
     * the module sum as a fallback but never exceed the elapsed part of the current day.
     */
    fun authoritativeTotalDuration(
        appDurationsMillis: Collection<Long>,
        systemUnionDurationMillis: Long?,
        maximumDayDurationMillis: Long,
    ): Long {
        val maximum = maximumDayDurationMillis.coerceAtLeast(0L)
        val normalized = appDurationsMillis.map { it.coerceAtLeast(0L) }
        val rawTotal = if (systemUnionDurationMillis != null) {
            maxOf(
                systemUnionDurationMillis.coerceAtLeast(0L),
                normalized.maxOrNull() ?: 0L,
            )
        } else {
            normalized.fold(0L) { total, duration ->
                if (duration > Long.MAX_VALUE - total) Long.MAX_VALUE else total + duration
            }
        }
        return rawTotal.coerceAtMost(maximum)
    }
}
