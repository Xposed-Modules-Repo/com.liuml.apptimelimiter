package com.liuml.apptimelimiter.core

data class RewardedAdQuotaState(
    val dayToken: String = "",
    val dailyCount: Int = 0,
    val dailyExtensionMillis: Long = 0L,
    val sessionCount: Int = 0,
    val sessionExtensionMillis: Long = 0L,
)

data class RewardedAdDecision(
    val allowed: Boolean,
    val rewardMillis: Long,
    val nextState: RewardedAdQuotaState,
    val remainingDailyCount: Int,
    val remainingDailyMillis: Long,
)

object RewardedAdPolicy {
    const val MAX_REWARD_MILLIS = 60 * 60 * 1000L

    fun claim(
        state: RewardedAdQuotaState,
        dayToken: String,
        configuredExtensionMillis: Long,
        ruleRemainingMillis: Long,
    ): RewardedAdDecision {
        val current = if (state.dayToken == dayToken) state else state.copy(
            dayToken = dayToken,
            dailyCount = 0,
            dailyExtensionMillis = 0L,
        )
        val reward = minOf(
            configuredExtensionMillis.coerceAtLeast(0L),
            ruleRemainingMillis.coerceAtLeast(0L),
        )
        // ExtensionQuotaPolicy owns all product quotas. This repository only deduplicates
        // ad callbacks and stages a verified reward, so it must not impose a second cap.
        val allowed = reward > 0L
        val next = if (allowed) current.copy(
            dailyCount = current.dailyCount + 1,
            dailyExtensionMillis = current.dailyExtensionMillis + reward,
            sessionCount = current.sessionCount + 1,
            sessionExtensionMillis = current.sessionExtensionMillis + reward,
        ) else current
        return RewardedAdDecision(
            allowed = allowed,
            rewardMillis = if (allowed) reward else 0L,
            nextState = next,
            remainingDailyCount = Int.MAX_VALUE,
            remainingDailyMillis = Long.MAX_VALUE,
        )
    }
}
