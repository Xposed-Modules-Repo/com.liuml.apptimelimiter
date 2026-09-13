package com.liuml.apptimelimiter.core

/**
 * Keeps milestone arithmetic independent from UI and process lifetime. A day has at most
 * forty-eight half-hour milestones, so corrupt or unusually large usage values stay bounded.
 */
object UsageMilestonePolicy {
    const val INTERVAL_MILLIS = 30L * 60L * 1000L
    const val MAX_MILESTONE_INDEX = 48

    fun reachedIndex(usedMillis: Long): Int =
        (usedMillis.coerceAtLeast(0L) / INTERVAL_MILLIS)
            .coerceAtMost(MAX_MILESTONE_INDEX.toLong())
            .toInt()

    fun nextDelayMillis(usedMillis: Long): Long? {
        val reached = reachedIndex(usedMillis)
        if (reached >= MAX_MILESTONE_INDEX) return null
        val nextBoundary = (reached + 1L) * INTERVAL_MILLIS
        return (nextBoundary - usedMillis.coerceAtLeast(0L)).coerceAtLeast(1L)
    }

    fun isValidMilestoneIndex(index: Int): Boolean = index in 1..MAX_MILESTONE_INDEX

    fun eventId(packageName: String, dayToken: String, index: Int): String =
        "usage_milestone:$dayToken:${packageName.take(120)}:$index"
}
