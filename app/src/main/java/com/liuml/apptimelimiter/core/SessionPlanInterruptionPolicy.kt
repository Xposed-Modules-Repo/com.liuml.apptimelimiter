package com.liuml.apptimelimiter.core

enum class SessionPlanInterruptionAction {
    DO_NOT_RESHOW,
    RESTORE_PLAN_WARNING,
}

object SessionPlanInterruptionPolicy {
    fun resolve(
        isReplanPrompt: Boolean,
        hasRunningPlan: Boolean,
    ): SessionPlanInterruptionAction =
        if (isReplanPrompt && hasRunningPlan) {
            SessionPlanInterruptionAction.RESTORE_PLAN_WARNING
        } else {
            SessionPlanInterruptionAction.DO_NOT_RESHOW
        }
}
