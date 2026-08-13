package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppRule

/** Keeps the definition of an actively managed package identical across all protection engines. */
object RuleActivationPolicy {
    fun hasEffectiveRule(
        rule: AppRule,
        assignedGroup: AppGroup?,
    ): Boolean = assignedGroup?.let { group ->
        group.enabled &&
            rule.packageName in group.packageNames &&
            (group.dailyEnabled || group.perLaunchEnabled || group.scheduleEnabled)
    } ?: (rule.enabled || rule.sessionPlanningEnabled)
}
