package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookStatusPresentationPolicyTest {
    @Test
    fun `non root mode hides every hook prompt`() {
        ManagedAppHookState.entries.forEach { state ->
            assertFalse(HookStatusPresentationPolicy.shouldShowAppIssue(true, state))
        }
        assertEquals(
            emptySet<String>(),
            HookStatusPresentationPolicy.scopeReminderPackages(
                nonRootModeEnabled = true,
                candidatePackages = setOf("a"),
                states = mapOf("a" to ManagedAppHookState.NOT_IN_SCOPE),
            ),
        )
    }

    @Test
    fun `xposed mode only surfaces definite failures`() {
        assertTrue(
            HookStatusPresentationPolicy.shouldShowAppIssue(
                false,
                ManagedAppHookState.NOT_IN_SCOPE,
            ),
        )
        assertTrue(
            HookStatusPresentationPolicy.shouldShowAppIssue(
                false,
                ManagedAppHookState.RUNNING_FAILED,
            ),
        )
        assertFalse(
            HookStatusPresentationPolicy.shouldShowAppIssue(
                false,
                ManagedAppHookState.IN_SCOPE_IDLE,
            ),
        )
        assertFalse(
            HookStatusPresentationPolicy.shouldShowAppIssue(
                false,
                ManagedAppHookState.COMPATIBILITY_PENDING,
            ),
        )
    }
}
