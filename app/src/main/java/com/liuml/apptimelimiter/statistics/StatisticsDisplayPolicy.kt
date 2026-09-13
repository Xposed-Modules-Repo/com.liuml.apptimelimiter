package com.liuml.apptimelimiter.statistics

/** Keeps every statistics section on the same filtered package set. */
object StatisticsDisplayPolicy {
    fun filter(
        summaries: Collection<AppUsageSummary>,
        systemPackages: Set<String>,
        includeSystemApps: Boolean,
    ): List<AppUsageSummary> = summaries
        .asSequence()
        .filter { includeSystemApps || it.packageName !in systemPackages }
        .filter { it.packageName.isNotBlank() }
        .sortedWith(
            compareByDescending<AppUsageSummary> { it.durationMillis.coerceAtLeast(0L) }
                .thenByDescending { it.lastUsedAtMillis.coerceAtLeast(0L) }
                .thenBy { it.packageName },
        )
        .toList()

    fun ratio(durationMillis: Long, totalMillis: Long): Float =
        if (durationMillis <= 0L || totalMillis <= 0L) 0f
        else (durationMillis.toDouble() / totalMillis.toDouble()).toFloat().coerceIn(0f, 1f)
}
