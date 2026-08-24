package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.core.LimitBlockReason
import com.liuml.apptimelimiter.core.QuotaBoundaryPolicy
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
    val groupSessionUsedMillis: Long = sessionUsedMillis,
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
                reached(snapshot.appDailyLimitMillis, snapshot.appDailyUsedMillis)
            ) add(QuotaKind.APP_DAILY)
            if (
                snapshot.appPerSessionEnabled &&
                reached(snapshot.appPerSessionLimitMillis, snapshot.sessionUsedMillis)
            ) add(QuotaKind.APP_PER_LAUNCH)
            if (
                snapshot.groupDailyEnabled &&
                reached(snapshot.groupDailyLimitMillis, snapshot.groupDailyUsedMillis)
            ) add(QuotaKind.GROUP_DAILY)
            if (
                snapshot.groupPerSessionEnabled &&
                reached(snapshot.groupPerSessionLimitMillis, snapshot.groupSessionUsedMillis)
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
                    add(remaining(snapshot.appDailyLimitMillis, snapshot.appDailyUsedMillis))
                }
                if (snapshot.appPerSessionEnabled) {
                    add(remaining(snapshot.appPerSessionLimitMillis, snapshot.sessionUsedMillis))
                }
                if (snapshot.groupDailyEnabled) {
                    add(remaining(snapshot.groupDailyLimitMillis, snapshot.groupDailyUsedMillis))
                }
                if (snapshot.groupPerSessionEnabled) {
                    add(
                        remaining(
                            snapshot.groupPerSessionLimitMillis,
                            snapshot.groupSessionUsedMillis,
                        ),
                    )
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

    private fun reached(limitMillis: Long, usedMillis: Long): Boolean =
        remaining(limitMillis, usedMillis) == 0L

    private fun remaining(limitMillis: Long, usedMillis: Long): Long =
        QuotaBoundaryPolicy.normalizeRemainingMillis(limitMillis - usedMillis)
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
