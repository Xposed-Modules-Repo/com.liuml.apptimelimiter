package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule
import java.time.ZonedDateTime

data class RuleSimulationInput(
    val rule: AppRule,
    val group: AppGroup? = null,
    val dailyUsedMillis: Long = 0L,
    val sessionUsedMillis: Long = 0L,
    val groupDailyUsedMillis: Long = 0L,
    val groupSessionUsedMillis: Long = 0L,
    val cooldownRemainingMillis: Long = 0L,
    val planRemainingMillis: Long? = null,
    val now: ZonedDateTime,
)

enum class RuleSimulationReason { ALLOWED, SCHEDULE, COOLDOWN, DAILY_QUOTA, SESSION_QUOTA, PLAN }

data class RuleSimulationResult(
    val allowed: Boolean,
    val reason: RuleSimulationReason,
    val remainingMillis: Long?,
    val nextScheduleTransition: ZonedDateTime?,
)

/** Read-only preview shared by settings and future rule-debug screens. */
object RuleSimulationPolicy {
    fun evaluate(input: RuleSimulationInput): RuleSimulationResult {
        val rule = input.rule
        val group = input.group
        val constraints = buildList {
            if (rule.scheduleEnabled) add(ScheduleConstraint(rule.scheduleMode, rule.scheduleWindows))
            if (group?.scheduleEnabled == true) add(ScheduleConstraint(group.scheduleMode, group.scheduleWindows))
        }
        val schedule = ScheduleEvaluator.evaluateAll(constraints, input.now)
        if (!schedule.allowed) return RuleSimulationResult(false, RuleSimulationReason.SCHEDULE, null, schedule.nextTransition)
        if (input.cooldownRemainingMillis > 0L) return RuleSimulationResult(false, RuleSimulationReason.COOLDOWN, input.cooldownRemainingMillis, schedule.nextTransition)

        val dailyRemaining = listOfNotNull(
            remainingMillis(rule.dailyLimitSeconds, input.dailyUsedMillis).takeIf { rule.dailyEnabled },
            group?.takeIf { it.dailyEnabled }?.let { remainingMillis(it.dailyLimitSeconds, input.groupDailyUsedMillis) },
        ).minOrNull()
        if (dailyRemaining == 0L) return RuleSimulationResult(false, RuleSimulationReason.DAILY_QUOTA, 0L, schedule.nextTransition)

        val sessionRemaining = listOfNotNull(
            remainingMillis(rule.perLaunchLimitSeconds, input.sessionUsedMillis).takeIf { rule.perLaunchEnabled },
            group?.takeIf { it.perLaunchEnabled }?.let { remainingMillis(it.perLaunchLimitSeconds, input.groupSessionUsedMillis) },
        ).minOrNull()
        if (sessionRemaining == 0L) return RuleSimulationResult(false, RuleSimulationReason.SESSION_QUOTA, 0L, schedule.nextTransition)

        val remaining = listOfNotNull(dailyRemaining, sessionRemaining, input.planRemainingMillis?.coerceAtLeast(0L)).minOrNull()
        if (input.planRemainingMillis != null && input.planRemainingMillis <= 0L) {
            return RuleSimulationResult(false, RuleSimulationReason.PLAN, 0L, schedule.nextTransition)
        }
        return RuleSimulationResult(true, RuleSimulationReason.ALLOWED, remaining, schedule.nextTransition)
    }

    private fun remainingMillis(limitSeconds: Long, usedMillis: Long): Long {
        val safeSeconds = limitSeconds.coerceIn(0L, Long.MAX_VALUE / 1000L)
        val limitMillis = safeSeconds * 1000L
        return (limitMillis - usedMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
