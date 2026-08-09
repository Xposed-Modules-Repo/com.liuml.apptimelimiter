package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusPolicyTest {
    @Test
    fun `current generation heartbeat confirms LSPosed controller`() {
        val result = resolve(
            managedHookState = ManagedAppHookState.RUNNING_CURRENT,
            hookVersionCode = 35,
            hookModeGeneration = 9L,
        )
        assertEquals(ScopeState.IN_SCOPE, result.scopeState)
        assertEquals(HookVerificationState.RUNNING_CURRENT, result.hookState)
        assertEquals(EffectiveController.XPOSED, result.controller)
        assertEquals(ProtectionHealth.HEALTHY, result.health)
    }

    @Test
    fun `scope cannot be inferred when framework is disconnected`() {
        val result = resolve(
            frameworkConnected = false,
            managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
            hookVersionCode = 0,
            hookModeGeneration = 0L,
        )
        assertEquals(ScopeState.UNKNOWN, result.scopeState)
        assertEquals(ProtectionProductMessage.SCOPE_UNKNOWN, result.message)
        assertEquals(EffectiveController.WAITING_REOPEN, result.controller)
    }

    @Test
    fun `direct scope is healthy without starting target app`() {
        val result = resolve(
            managedHookState = ManagedAppHookState.IN_SCOPE_IDLE,
            hookVersionCode = 35,
            hookModeGeneration = 8L,
        )
        assertEquals(HookVerificationState.IN_SCOPE_IDLE, result.hookState)
        assertEquals(ProtectionProductMessage.LSPOSED_SCOPE_READY, result.message)
        assertEquals(EffectiveController.XPOSED, result.controller)
        assertEquals(ProtectionHealth.HEALTHY, result.health)
    }

    @Test
    fun `ordinary protection ignores absent Xposed and needs both permissions`() {
        val ready = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            frameworkConnected = false,
            managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
            accessibilityEnabled = true,
            usageAccessGranted = true,
        )
        assertEquals(HookVerificationState.NOT_APPLICABLE, ready.hookState)
        assertEquals(EffectiveController.ACCESSIBILITY, ready.controller)
        assertEquals(ProtectionHealth.HEALTHY, ready.health)

        val missingUsage = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            frameworkConnected = false,
            managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
            accessibilityEnabled = true,
            usageAccessGranted = false,
        )
        assertEquals(EffectiveController.INACTIVE, missingUsage.controller)
        assertEquals(
            ProtectionProductMessage.USAGE_ACCESS_MISSING,
            missingUsage.message,
        )
    }

    @Test
    fun `non root permission failure is one global issue instead of app issues`() {
        val disconnected = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            accessibilityEnabled = false,
            accessibilityConfigured = true,
        )
        assertEquals(ProtectionHealth.HEALTHY, disconnected.health)
        assertEquals(
            ProtectionProductMessage.ACCESSIBILITY_DISCONNECTED,
            disconnected.message,
        )

        val overview = ProtectionStatusPolicy.overview(
            ProtectionMode.ACCESSIBILITY,
            listOf(disconnected),
        )
        assertEquals(0, overview.issueCount)
        assertTrue(overview.hasGlobalIssue)
        assertFalse(overview.effective)
    }

    @Test
    fun `ordinary protection does not wait for Hook when app is outside scope`() {
        val result = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            frameworkConnected = true,
            managedHookState = ManagedAppHookState.NOT_IN_SCOPE,
            hookVersionCode = 0,
            hookModeGeneration = 0L,
        )
        assertEquals(HookVerificationState.NOT_APPLICABLE, result.hookState)
        assertEquals(EffectiveController.ACCESSIBILITY, result.controller)
        assertEquals(ProtectionHealth.HEALTHY, result.health)
    }

    @Test
    fun `non root mode completely ignores Hook and scope health`() {
        val pending = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            managedHookState = ManagedAppHookState.IN_SCOPE_IDLE,
            hookVersionCode = 34,
            hookModeGeneration = 8L,
        )
        assertEquals(HookVerificationState.NOT_APPLICABLE, pending.hookState)
        assertEquals(ProtectionHealth.HEALTHY, pending.health)

        val yielded = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            managedHookState = ManagedAppHookState.IN_SCOPE_IDLE,
            hookVersionCode = 35,
            hookModeGeneration = 9L,
        )
        assertEquals(HookVerificationState.NOT_APPLICABLE, yielded.hookState)
        assertEquals(ProtectionHealth.HEALTHY, yielded.health)
    }

    @Test
    fun `Shizuku unavailable degrades to restriction page`() {
        val result = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY_SHIZUKU,
            frameworkConnected = false,
            managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
            shizukuState = ShizukuExecutionState.UNAVAILABLE,
        )
        assertEquals(EffectiveController.ACCESSIBILITY_FALLBACK, result.controller)
        assertEquals(ProtectionHealth.DEGRADED, result.health)
        assertEquals(ProtectionProductMessage.SHIZUKU_FALLBACK, result.message)
    }

    @Test
    fun `overview is effective only when selected chain can control a target`() {
        val inactive = resolve(
            managedHookState = ManagedAppHookState.NOT_IN_SCOPE,
            hookVersionCode = 0,
            hookModeGeneration = 0L,
        )
        val inactiveOverview = ProtectionStatusPolicy.overview(
            ProtectionMode.XPOSED,
            listOf(inactive),
        )
        assertFalse(inactiveOverview.effective)
        assertEquals(1, inactiveOverview.issueCount)

        val active = resolve(
            selectedMode = ProtectionMode.ACCESSIBILITY,
            frameworkConnected = false,
            managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
        )
        val activeOverview = ProtectionStatusPolicy.overview(
            ProtectionMode.ACCESSIBILITY,
            listOf(active),
        )
        assertTrue(activeOverview.effective)
    }

    private fun resolve(
        selectedMode: ProtectionMode = ProtectionMode.XPOSED,
        frameworkConnected: Boolean = true,
        managedHookState: ManagedAppHookState = ManagedAppHookState.RUNNING_CURRENT,
        hookVersionCode: Int = 0,
        hookModeGeneration: Long = 0L,
        accessibilityEnabled: Boolean = true,
        accessibilityConfigured: Boolean = accessibilityEnabled,
        usageAccessGranted: Boolean = true,
        shizukuState: ShizukuExecutionState = ShizukuExecutionState.READY,
    ): TargetProtectionStatus = ProtectionStatusPolicy.resolveTarget(
        TargetProtectionInput(
            packageName = "com.example.target",
            hasSavedRule = true,
            frameworkConnected = frameworkConnected,
            managedHookState = managedHookState,
            hookVersionCode = hookVersionCode,
            hookModeGeneration = hookModeGeneration,
            currentVersionCode = 36,
            selectedMode = selectedMode,
            selectedModeGeneration = 9L,
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConfigured = accessibilityConfigured,
            usageAccessGranted = usageAccessGranted,
            shizukuState = shizukuState,
        ),
    )
}
