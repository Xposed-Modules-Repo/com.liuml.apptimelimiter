package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPlanInterruptionPolicyTest {
    @Test
    fun `initial prompt remains handled after activity handoff`() {
        assertEquals(
            SessionPlanInterruptionAction.DO_NOT_RESHOW,
            SessionPlanInterruptionPolicy.resolve(
                isReplanPrompt = false,
                hasRunningPlan = false,
            ),
        )
    }

    @Test
    fun `interrupted replan restores original warning instead of reopening dialog`() {
        assertEquals(
            SessionPlanInterruptionAction.RESTORE_PLAN_WARNING,
            SessionPlanInterruptionPolicy.resolve(
                isReplanPrompt = true,
                hasRunningPlan = true,
            ),
        )
    }

    @Test
    fun `stale replan without a plan is not restored`() {
        assertEquals(
            SessionPlanInterruptionAction.DO_NOT_RESHOW,
            SessionPlanInterruptionPolicy.resolve(
                isReplanPrompt = true,
                hasRunningPlan = false,
            ),
        )
    }
}
