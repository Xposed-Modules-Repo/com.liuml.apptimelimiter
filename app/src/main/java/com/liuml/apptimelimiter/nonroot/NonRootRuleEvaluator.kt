package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.core.LimitBlockReason
import com.liuml.apptimelimiter.core.QuotaKind

enum class NonRootBlockReason {
    SCHEDULE,
    COOLDOWN,
    QUOTA,
    SESSION_PLAN,
}

data class NonRootRuleSnapshot(
    val scheduleBlocked: Boolean,
    val cooldownRemainingMillis: Long,
    val appDailyEnabled: Boolean,
    val appDailyUsedMillis: Long,
    val appDailyLimitMillis: Long,
    val appPerSessionEnabled: Boolean,
    val appPerSessionLimitMillis: Long,
    val groupDailyEnabled: Boolean,
    val groupDailyUsedMillis: Long,
    val groupDailyLimitMillis: Long,
    val groupPerSessionEnabled: Boolean,
    val groupPerSessionLimitMillis: Long,
    val sessionUsedMillis: Long,
    val planActive: Boolean,
    val planRemainingMillis: Long,
)

data class NonRootRuleDecision(
    val blockingReason: NonRootBlockReason?,
    val reachedKinds: Set<QuotaKind>,
    val nextThresholdMillis: Long?,
    val sessionPlanAllowed: Boolean,
    val sessionPlanIsNextThreshold: Boolean,
) {
    val persistentReason: LimitBlockReason?
        get() = when (blockingReason) {
            NonRootBlockReason.SCHEDULE -> LimitBlockReason.SCHEDULE
            NonRootBlockReason.COOLDOWN -> LimitBlockReason.COOLDOWN
            NonRootBlockReason.QUOTA -> LimitBlockReason.QUOTA
            NonRootBlockReason.SESSION_PLAN, null -> null
        }
}

object NonRootRuleEvaluator {
    fun evaluate(snapshot: NonRootRuleSnapshot): NonRootRuleDecision {
        val reachedKinds = buildSet {
            if (
                snapshot.appDailyEnabled &&
                snapshot.appDailyUsedMillis >= snapshot.appDailyLimitMillis
            ) add(QuotaKind.APP_DAILY)
            if (
                snapshot.appPerSessionEnabled &&
                snapshot.sessionUsedMillis >= snapshot.appPerSessionLimitMillis
            ) add(QuotaKind.APP_PER_LAUNCH)
            if (
                snapshot.groupDailyEnabled &&
                snapshot.groupDailyUsedMillis >= snapshot.groupDailyLimitMillis
            ) add(QuotaKind.GROUP_DAILY)
            if (
                snapshot.groupPerSessionEnabled &&
                snapshot.sessionUsedMillis >= snapshot.groupPerSessionLimitMillis
            ) add(QuotaKind.GROUP_PER_LAUNCH)
        }
        val blockingReason = when {
            snapshot.scheduleBlocked -> NonRootBlockReason.SCHEDULE
            snapshot.cooldownRemainingMillis > 0L -> NonRootBlockReason.COOLDOWN
            reachedKinds.isNotEmpty() -> NonRootBlockReason.QUOTA
            snapshot.planActive && snapshot.planRemainingMillis <= 0L ->
                NonRootBlockReason.SESSION_PLAN
            else -> null
        }
        val permanentRemaining = if (blockingReason == null) {
            buildList {
                if (snapshot.appDailyEnabled) {
                    add(snapshot.appDailyLimitMillis - snapshot.appDailyUsedMillis)
                }
                if (snapshot.appPerSessionEnabled) {
                    add(snapshot.appPerSessionLimitMillis - snapshot.sessionUsedMillis)
                }
                if (snapshot.groupDailyEnabled) {
                    add(snapshot.groupDailyLimitMillis - snapshot.groupDailyUsedMillis)
                }
                if (snapshot.groupPerSessionEnabled) {
                    add(snapshot.groupPerSessionLimitMillis - snapshot.sessionUsedMillis)
                }
            }.filter { it > 0L }.minOrNull()
        } else {
            null
        }
        val sessionPlanIsNextThreshold = blockingReason == null &&
            snapshot.planActive &&
            snapshot.planRemainingMillis > 0L &&
            (
                permanentRemaining == null ||
                    snapshot.planRemainingMillis < permanentRemaining
                )
        val remaining = listOfNotNull(
            permanentRemaining,
            snapshot.planRemainingMillis.takeIf {
                blockingReason == null && snapshot.planActive && it > 0L
            },
        ).minOrNull()
        return NonRootRuleDecision(
            blockingReason = blockingReason,
            reachedKinds = reachedKinds,
            nextThresholdMillis = remaining,
            sessionPlanAllowed = blockingReason == null,
            sessionPlanIsNextThreshold = sessionPlanIsNextThreshold,
        )
    }
}

object NonRootLimitHitPolicy {
    fun shouldRecord(
        reason: NonRootBlockReason,
        quotaIncidentIsNew: Boolean,
        scheduleIncidentIsNew: Boolean,
    ): Boolean = when (reason) {
        NonRootBlockReason.QUOTA -> quotaIncidentIsNew
        NonRootBlockReason.SCHEDULE -> scheduleIncidentIsNew
        NonRootBlockReason.COOLDOWN,
        NonRootBlockReason.SESSION_PLAN,
        -> false
    }
}
