package com.liuml.apptimelimiter.core

data class PerLaunchRestDecision(
    val shouldStartNewCycle: Boolean,
    val backgroundGapMillis: Long,
)

/**
 * Treats a sufficiently long, genuine background interval as an effective rest.
 *
 * The configured post-limit cooldown is reused as the minimum rest interval. This keeps a short
 * Activity handoff or an accidental app switch in the same per-launch cycle, while avoiding a
 * process that survived in the background from carrying an old partial session indefinitely.
 */
object PerLaunchRestPolicy {
    @Suppress("UNUSED_PARAMETER") // Retained argument no longer makes stale UI an authority.
    fun evaluate(
        hasPerLaunchQuota: Boolean,
        configuredCooldownMillis: Long,
        backgroundedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
        blockingStateActive: Boolean,
        activeCooldownRemainingMillis: Long,
    ): PerLaunchRestDecision {
        val validBackgroundTimestamp = backgroundedAtElapsedMillis > 0L &&
            nowElapsedMillis >= backgroundedAtElapsedMillis
        val gapMillis = if (validBackgroundTimestamp) {
            nowElapsedMillis - backgroundedAtElapsedMillis
        } else {
            0L
        }
        // A retained restriction page is not evidence of a current restriction. The caller
        // re-evaluates daily quotas and schedules after resetting only the continuous cycle.
        val requiredRest = SessionRestPolicy.intervalMillis(
            configuredCooldownMillis > 0L, configuredCooldownMillis,
        )
        val shouldReset = hasPerLaunchQuota &&
            validBackgroundTimestamp &&
            gapMillis >= requiredRest &&
            activeCooldownRemainingMillis <= 0L
        return PerLaunchRestDecision(
            shouldStartNewCycle = shouldReset,
            backgroundGapMillis = gapMillis,
        )
    }
}
