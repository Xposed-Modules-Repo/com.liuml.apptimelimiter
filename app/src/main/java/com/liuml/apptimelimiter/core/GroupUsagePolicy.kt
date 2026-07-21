package com.liuml.apptimelimiter.core

object GroupUsagePolicy {
    fun authoritativeTotalMillis(
        packageNames: Collection<String>,
        systemDurations: Map<String, Long>,
        moduleDurations: Map<String, Long>,
    ): Long = packageNames.fold(0L) { total, packageName ->
        val used = maxOf(
            systemDurations[packageName]?.coerceAtLeast(0L) ?: 0L,
            moduleDurations[packageName]?.coerceAtLeast(0L) ?: 0L,
        )
        saturatedAdd(total, used)
    }

    private fun saturatedAdd(current: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment
}
