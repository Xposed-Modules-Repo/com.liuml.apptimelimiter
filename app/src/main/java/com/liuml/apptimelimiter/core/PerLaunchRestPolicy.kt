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
        val shouldReset = hasPerLaunchQuota &&
            configuredCooldownMillis > 0L &&
            validBackgroundTimestamp &&
            gapMillis >= configuredCooldownMillis &&
            !blockingStateActive &&
            activeCooldownRemainingMillis <= 0L
        return PerLaunchRestDecision(
            shouldStartNewCycle = shouldReset,
            backgroundGapMillis = gapMillis,
        )
    }
}
