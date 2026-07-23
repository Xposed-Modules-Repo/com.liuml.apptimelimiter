package com.liuml.apptimelimiter.core

/** Pure calculations for the process-local, foreground-only session plan. */
object SessionPlanPolicy {
    const val MIN_DURATION_MILLIS = 60_000L
    const val MAX_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
    const val WARNING_LEAD_MILLIS = 5_000L

    fun sanitizeDurationMillis(durationMillis: Long): Long =
        durationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)

    fun selectedDurationMillis(
        requestedDurationMillis: Long?,
        allowDebugShortChoice: Boolean = false,
    ): Long? = requestedDurationMillis?.let { requested ->
        if (allowDebugShortChoice && requested == DEBUG_DURATION_MILLIS) {
            requested
        } else {
            sanitizeDurationMillis(requested)
        }
    }

    fun remainingMillis(
        committedRemainingMillis: Long,
        foregroundStartedAtElapsedMillis: Long?,
        nowElapsedMillis: Long,
    ): Long {
        val elapsed = foregroundStartedAtElapsedMillis
            ?.let { (nowElapsedMillis - it).coerceAtLeast(0L) }
            ?: 0L
        return (committedRemainingMillis - elapsed).coerceAtLeast(0L)
    }

    /** The plan owns the warning only when it expires strictly before another timed rule. */
    fun isEarliest(planRemainingMillis: Long, otherRemainingMillis: Long?): Boolean =
        otherRemainingMillis == null || planRemainingMillis < otherRemainingMillis

    /**
     * A session plan is optional freedom within the user's existing allowance, never a way to
     * bypass the earliest app or group time quota.
     */
    fun fitsWithinQuota(
        requestedDurationMillis: Long,
        earliestQuotaRemainingMillis: Long?,
    ): Boolean = earliestQuotaRemainingMillis == null ||
        requestedDurationMillis <= earliestQuotaRemainingMillis.coerceAtLeast(0L)

    /** A plan may be offered when no timed quota exists or the earliest quota still has time left. */
    fun canOfferPlan(earliestQuotaRemainingMillis: Long?): Boolean =
        earliestQuotaRemainingMillis == null || earliestQuotaRemainingMillis > 0L

    data class ExpiryEffects(
        val startsCooldown: Boolean,
        val limitHitIncrement: Int,
    )

    val EXPIRY_EFFECTS = ExpiryEffects(
        startsCooldown = false,
        limitHitIncrement = 0,
    )

    private const val DEBUG_DURATION_MILLIS = 10_000L
}
