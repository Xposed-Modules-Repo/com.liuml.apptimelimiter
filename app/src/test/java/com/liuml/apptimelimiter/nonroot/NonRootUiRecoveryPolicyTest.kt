package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootUiRecoveryPolicyTest {
    @Test
    fun `visible unanswered plan is restored after service restart`() {
        assertTrue(
            NonRootUiRecoveryPolicy.shouldRestorePlanAfterServiceRestart(
                uiState = NonRootUiExecutionState.PLAN_VISIBLE,
                planPromptHandled = true,
                planActive = false,
            ),
        )
        assertFalse(
            NonRootUiRecoveryPolicy.shouldRestorePlanAfterServiceRestart(
                uiState = NonRootUiExecutionState.IDLE,
                planPromptHandled = true,
                planActive = false,
            ),
        )
        assertFalse(
            NonRootUiRecoveryPolicy.shouldRestorePlanAfterServiceRestart(
                uiState = NonRootUiExecutionState.PLAN_VISIBLE,
                planPromptHandled = true,
                planActive = true,
            ),
        )
    }

    @Test
    fun `plan detach retries only for the same unrestricted foreground session`() {
        assertTrue(
            NonRootUiRecoveryPolicy.shouldRetryPlanDetach(
                attemptsCompleted = 1,
                maxAttempts = 3,
                sameForegroundPackage = true,
                sameSession = true,
                restrictionUiActive = false,
                persistentRestrictionActive = false,
            ),
        )
        assertFalse(
            NonRootUiRecoveryPolicy.shouldRetryPlanDetach(
                attemptsCompleted = 1,
                maxAttempts = 3,
                sameForegroundPackage = false,
                sameSession = true,
                restrictionUiActive = false,
                persistentRestrictionActive = false,
            ),
        )
        assertFalse(
            NonRootUiRecoveryPolicy.shouldRetryPlanDetach(
                attemptsCompleted = 1,
                maxAttempts = 3,
                sameForegroundPackage = true,
                sameSession = true,
                restrictionUiActive = true,
                persistentRestrictionActive = false,
            ),
        )
    }

    @Test
    fun `plan detach retry is bounded and uses staged delays`() {
        assertFalse(
            NonRootUiRecoveryPolicy.shouldRetryPlanDetach(
                attemptsCompleted = 3,
                maxAttempts = 3,
                sameForegroundPackage = true,
                sameSession = true,
                restrictionUiActive = false,
                persistentRestrictionActive = false,
            ),
        )
        assertEquals(300L, NonRootUiRecoveryPolicy.retryDelayMillis(1))
        assertEquals(800L, NonRootUiRecoveryPolicy.retryDelayMillis(2))
    }
}
