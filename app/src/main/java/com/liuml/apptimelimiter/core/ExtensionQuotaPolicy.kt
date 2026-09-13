package com.liuml.apptimelimiter.core

data class ExtensionQuotaState(
    val dayToken: String = "",
    val dailyUsedCount: Int = 0,
    val freeUsedCount: Int = 0,
    val sessionId: String = "",
    val sessionUsedCount: Int = 0,
)

data class ExtensionQuotaDecision(
    val allowed: Boolean,
    val requiresAd: Boolean,
    val nextState: ExtensionQuotaState,
    val remainingCount: Int,
    val remainingSessionCount: Int,
    val remainingFreeCount: Int,
)

object ExtensionQuotaPolicy {
    const val DEFAULT_DAILY_LIMIT = 10
    const val DEFAULT_SESSION_LIMIT = 3
    const val DEFAULT_FREE_DAILY_LIMIT = 3
    const val MAX_DAILY_LIMIT = 50
    const val MAX_SESSION_LIMIT = 10
    const val MAX_FREE_DAILY_LIMIT = 50

    /** Migrates only the former hard-coded default, never an already configured new quota. */
    fun shouldMigrateLegacyDefaults(
        legacyDailyLimit: Int,
        hasSessionLimit: Boolean,
        hasFreeDailyLimit: Boolean,
    ): Boolean = legacyDailyLimit == 3 && !hasSessionLimit && !hasFreeDailyLimit

    fun normalizeDailyLimit(value: Int): Int =
        if (value <= 0) DEFAULT_DAILY_LIMIT else value.coerceAtMost(MAX_DAILY_LIMIT)

    fun normalizeSessionLimit(value: Int): Int =
        if (value <= 0) DEFAULT_SESSION_LIMIT else value.coerceAtMost(MAX_SESSION_LIMIT)

    /**
     * Free extensions are a fixed product rule, not a user preference. Retain the old
     * parameter only so restored backups and still-running Hook processes remain compatible.
     */
    fun normalizeFreeDailyLimit(@Suppress("UNUSED_PARAMETER") value: Int, dailyLimit: Int): Int =
        DEFAULT_FREE_DAILY_LIMIT.coerceAtMost(normalizeDailyLimit(dailyLimit))

    fun claimFree(
        state: ExtensionQuotaState,
        dayToken: String,
        sessionId: String,
        dailyLimit: Int,
    ): ExtensionQuotaDecision {
        return claim(
            state = state,
            dayToken = dayToken,
            sessionId = sessionId,
            dailyLimit = dailyLimit,
            sessionLimit = DEFAULT_SESSION_LIMIT,
            freeDailyLimit = DEFAULT_FREE_DAILY_LIMIT,
            rewarded = false,
        )
    }

    fun claimFree(
        state: ExtensionQuotaState,
        dayToken: String,
        sessionId: String,
        dailyLimit: Int,
        sessionLimit: Int,
        freeDailyLimit: Int,
    ): ExtensionQuotaDecision = claim(
        state, dayToken, sessionId, dailyLimit, sessionLimit, freeDailyLimit, rewarded = false,
    )

    fun claimRewarded(
        state: ExtensionQuotaState,
        dayToken: String,
        sessionId: String,
        dailyLimit: Int,
        sessionLimit: Int,
        freeDailyLimit: Int,
    ): ExtensionQuotaDecision = claim(
        state, dayToken, sessionId, dailyLimit, sessionLimit, freeDailyLimit, rewarded = true,
    )

    private fun claim(
        state: ExtensionQuotaState,
        dayToken: String,
        sessionId: String,
        dailyLimit: Int,
        sessionLimit: Int,
        freeDailyLimit: Int,
        rewarded: Boolean,
    ): ExtensionQuotaDecision {
        val daily = normalizeDailyLimit(dailyLimit)
        val session = normalizeSessionLimit(sessionLimit)
        val free = normalizeFreeDailyLimit(freeDailyLimit, daily)
        val forDay = if (state.dayToken == dayToken) state else ExtensionQuotaState(dayToken = dayToken)
        val current = if (forDay.sessionId == sessionId) forDay else forDay.copy(
            sessionId = sessionId,
            sessionUsedCount = 0,
        )
        if (current.dailyUsedCount >= daily || current.sessionUsedCount >= session) {
            return decision(false, false, current, daily, session, free)
        }
        if (!rewarded && current.freeUsedCount >= free) {
            return decision(false, true, current, daily, session, free)
        }
        val next = current.copy(
            dailyUsedCount = current.dailyUsedCount + 1,
            freeUsedCount = current.freeUsedCount + if (rewarded) 0 else 1,
            sessionUsedCount = current.sessionUsedCount + 1,
        )
        return decision(true, false, next, daily, session, free)
    }

    private fun decision(
        allowed: Boolean,
        requiresAd: Boolean,
        state: ExtensionQuotaState,
        dailyLimit: Int,
        sessionLimit: Int,
        freeLimit: Int,
    ) = ExtensionQuotaDecision(
        allowed = allowed,
        requiresAd = requiresAd,
        nextState = state,
        remainingCount = (dailyLimit - state.dailyUsedCount).coerceAtLeast(0),
        remainingSessionCount = (sessionLimit - state.sessionUsedCount).coerceAtLeast(0),
        remainingFreeCount = (freeLimit - state.freeUsedCount).coerceAtLeast(0),
    )
}
