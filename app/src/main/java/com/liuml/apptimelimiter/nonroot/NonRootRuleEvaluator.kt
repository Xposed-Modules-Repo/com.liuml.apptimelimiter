package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.core.LimitBlockReason
import com.liuml.apptimelimiter.core.QuotaBoundaryPolicy
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.RuleDecisionSnapshot

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
    val grouped: Boolean = false,
)

data class NonRootRuleDecision(
    val blockingReason: NonRootBlockReason?,
    val reachedKinds: Set<QuotaKind>,
    val nextThresholdMillis: Long?,
    val sessionPlanAllowed: Boolean,
    val sessionPlanIsNextThreshold: Boolean,
    val restrictionSnapshot: RuleDecisionSnapshot = RuleDecisionSnapshot(),
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
        val unified = RuleDecisionSnapshot(
            scheduleBlocked = snapshot.scheduleBlocked,
            cooldownRemainingMillis = snapshot.cooldownRemainingMillis,
            appDailyRemainingMillis = remaining(snapshot.appDailyLimitMillis, snapshot.appDailyUsedMillis)
                .takeIf { snapshot.appDailyEnabled },
            appPerLaunchRemainingMillis = remaining(snapshot.appPerSessionLimitMillis, snapshot.sessionUsedMillis)
                .takeIf { snapshot.appPerSessionEnabled },
            groupDailyRemainingMillis = remaining(snapshot.groupDailyLimitMillis, snapshot.groupDailyUsedMillis)
                .takeIf { snapshot.groupDailyEnabled },
            groupPerLaunchRemainingMillis = remaining(snapshot.groupPerSessionLimitMillis, snapshot.groupSessionUsedMillis)
                .takeIf { snapshot.groupPerSessionEnabled },
            planRemainingMillis = snapshot.planRemainingMillis.takeIf { snapshot.planActive },
            grouped = snapshot.grouped,
        )
        val blockingReason = when (unified.gate.blockingReason) {
            LimitBlockReason.SCHEDULE -> NonRootBlockReason.SCHEDULE
            LimitBlockReason.COOLDOWN -> NonRootBlockReason.COOLDOWN
            LimitBlockReason.QUOTA -> NonRootBlockReason.QUOTA
            null -> if (unified.primaryReason != null) NonRootBlockReason.SESSION_PLAN else null
        }
        return NonRootRuleDecision(
            blockingReason = blockingReason,
            reachedKinds = unified.reachedKinds,
            nextThresholdMillis = unified.nextThresholdMillis,
            sessionPlanAllowed = blockingReason == null,
            sessionPlanIsNextThreshold = unified.planIsNextThreshold,
            restrictionSnapshot = unified,
        )
    }

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
