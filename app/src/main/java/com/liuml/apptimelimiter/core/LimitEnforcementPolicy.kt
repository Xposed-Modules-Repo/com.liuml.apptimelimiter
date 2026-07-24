package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.LimitEnforcementMode

enum class LimitBlockReason {
    SCHEDULE,
    COOLDOWN,
    QUOTA,
}

data class LimitGateSnapshot(
    val scheduleBlocked: Boolean,
    val cooldownRemainingMillis: Long,
    val quotaReached: Boolean,
)

data class LimitGateDecision(
    val blockingReason: LimitBlockReason?,
    val sessionPlanAllowed: Boolean,
    val startsCooldownWhenNewlyBlocked: Boolean,
)

object LimitEnforcementPolicy {
    fun parseMode(raw: String?): LimitEnforcementMode =
        raw?.let { runCatching { LimitEnforcementMode.valueOf(it) }.getOrNull() }
            ?: LimitEnforcementMode.FORCE_EXIT

    fun firstBlockingReason(
        scheduleBlocked: Boolean,
        cooldownActive: Boolean,
        quotaReached: Boolean,
    ): LimitBlockReason? = evaluate(
        LimitGateSnapshot(
            scheduleBlocked = scheduleBlocked,
            cooldownRemainingMillis = if (cooldownActive) 1L else 0L,
            quotaReached = quotaReached,
        ),
    ).blockingReason

    fun evaluate(snapshot: LimitGateSnapshot): LimitGateDecision {
        val reason = when {
            snapshot.scheduleBlocked -> LimitBlockReason.SCHEDULE
            snapshot.cooldownRemainingMillis > 0L -> LimitBlockReason.COOLDOWN
            snapshot.quotaReached -> LimitBlockReason.QUOTA
            else -> null
        }
        return LimitGateDecision(
            blockingReason = reason,
            sessionPlanAllowed = reason == null,
            startsCooldownWhenNewlyBlocked = reason == LimitBlockReason.QUOTA,
        )
    }

    fun shouldStartCooldown(
        decision: LimitGateDecision,
        newlyBlocked: Boolean,
        configuredDurationMillis: Long,
        existingRemainingMillis: Long,
    ): Boolean =
        decision.startsCooldownWhenNewlyBlocked &&
            newlyBlocked &&
            configuredDurationMillis > 0L &&
            existingRemainingMillis <= 0L

    fun shouldInstallMediaHooks(mode: LimitEnforcementMode): Boolean =
        mode == LimitEnforcementMode.EXTERNAL_BREAK_PAGE
}

object ActivityCallbackPolicy {
    fun isCurrent(
        capturedGeneration: Long,
        currentGeneration: Long,
        hostIsCurrent: Boolean,
        hostIsUsable: Boolean,
        exitScheduled: Boolean,
    ): Boolean =
        capturedGeneration == currentGeneration &&
            hostIsCurrent &&
            hostIsUsable &&
            !exitScheduled
}

object RestPagePolicy {
    data class QuotaState(
        val reason: LimitBlockReason,
        val cooldownEndsAtMillis: Long,
    )

    fun quotaState(
        claimedCooldownEndsAtMillis: Long,
        nowMillis: Long,
    ): QuotaState = if (claimedCooldownEndsAtMillis > nowMillis) {
        QuotaState(LimitBlockReason.COOLDOWN, claimedCooldownEndsAtMillis)
    } else {
        QuotaState(LimitBlockReason.QUOTA, 0L)
    }

    fun shouldStartNewPerLaunchCycle(
        mode: LimitEnforcementMode,
        previousReason: LimitBlockReason?,
        cooldownEndsAtMillis: Long,
        nowMillis: Long,
        reachedKinds: Set<QuotaKind>,
    ): Boolean =
        mode == LimitEnforcementMode.EXTERNAL_BREAK_PAGE &&
            previousReason == LimitBlockReason.COOLDOWN &&
            cooldownEndsAtMillis > 0L &&
            nowMillis >= cooldownEndsAtMillis &&
            reachedKinds.any {
                it == QuotaKind.APP_PER_LAUNCH || it == QuotaKind.GROUP_PER_LAUNCH
            }
}
