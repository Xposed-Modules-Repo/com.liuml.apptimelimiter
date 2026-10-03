package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import org.junit.Assert.assertEquals
import org.junit.Test

class HookReloadTargetPolicyTest {
    private fun target(state: HookVerificationState) = TargetProtectionStatus(
        packageName = "example.${state.name.lowercase()}", hasSavedRule = true,
        scopeState = ScopeState.IN_SCOPE, hookState = state, accessibilityEnabled = false,
        usageAccessGranted = false, shizukuState = ShizukuExecutionState.DISABLED,
        controller = EffectiveController.XPOSED, health = ProtectionHealth.REPAIR_REQUIRED,
        message = ProtectionProductMessage.HOOK_FAILED,
    )

    @Test fun `healthy idle and irrelevant targets are excluded`() {
        val expected = listOf(HookVerificationState.OUTDATED, HookVerificationState.FAILED, HookVerificationState.PENDING_REOPEN)
            .map { target(it).packageName }.sorted()
        assertEquals(expected, HookReloadTargetPolicy.packages(HookVerificationState.entries.map(::target)))
    }

    @Test fun `scope omissions and removed rules cannot be repaired by force stop`() {
        val failed = target(HookVerificationState.FAILED)
        assertEquals(emptyList<String>(), HookReloadTargetPolicy.packages(listOf(
            failed.copy(scopeState = ScopeState.NOT_IN_SCOPE),
            failed.copy(scopeState = ScopeState.NOT_APPLICABLE),
            failed.copy(hasSavedRule = false),
        )))
        assertEquals(listOf(failed.packageName), HookReloadTargetPolicy.packages(listOf(failed, failed)))
    }
}
