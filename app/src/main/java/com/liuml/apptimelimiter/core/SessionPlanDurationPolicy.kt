package com.liuml.apptimelimiter.core

object SessionPlanDurationPolicy {
    const val MIN_TOTAL_MINUTES = 1
    const val MAX_TOTAL_MINUTES = 60
    const val DEFAULT_TOTAL_MINUTES = 5
    const val MAX_TOTAL_MILLIS = MAX_TOTAL_MINUTES * 60_000L

    fun durationAllowed(durationMillis: Long, maxAllowedMillis: Long?): Boolean =
        durationMillis in 1L..MAX_TOTAL_MILLIS &&
            (maxAllowedMillis == null || durationMillis <= maxAllowedMillis)

    fun maxSelectableMinutes(maxAllowedMillis: Long?): Int =
        if (maxAllowedMillis == null) {
            MAX_TOTAL_MINUTES
        } else {
            (maxAllowedMillis.coerceAtLeast(0L) / 60_000L)
                .coerceAtMost(MAX_TOTAL_MINUTES.toLong())
                .toInt()
        }

    fun defaultSelectableMinutes(maxAllowedMillis: Long?): Int? =
        maxSelectableMinutes(maxAllowedMillis)
            .takeIf { it >= MIN_TOTAL_MINUTES }
            ?.let { minOf(DEFAULT_TOTAL_MINUTES, it) }

    fun defaultSliderMinutes(maxAllowedMillis: Long?): Int =
        defaultSelectableMinutes(maxAllowedMillis) ?: MIN_TOTAL_MINUTES

    fun normalizeSliderMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_TOTAL_MINUTES, MAX_TOTAL_MINUTES)

    fun minutesAllowed(minutes: Int, maxAllowedMillis: Long?): Boolean =
        durationAllowed(normalizeSliderMinutes(minutes) * 60_000L, maxAllowedMillis)

    fun normalizeSelectableMinutes(minutes: Int, maxAllowedMillis: Long?): Int? =
        maxSelectableMinutes(maxAllowedMillis)
            .takeIf { it >= MIN_TOTAL_MINUTES }
            ?.let { minutes.coerceIn(MIN_TOTAL_MINUTES, it) }
}
