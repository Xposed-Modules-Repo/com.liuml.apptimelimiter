package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanPromptPolicyTest {
    @Test
    fun `initial prompt is not shown again after it has been handled in this process session`() {
        assertTrue(SessionPlanPromptPolicy.shouldShowInitialPrompt(promptHandled = false))
        assertFalse(SessionPlanPromptPolicy.shouldShowInitialPrompt(promptHandled = true))
    }
}
