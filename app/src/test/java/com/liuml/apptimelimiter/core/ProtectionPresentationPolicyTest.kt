package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.AccessibilityRuntimeState
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionPresentationPolicyTest {
    @Test
    fun `unknown LSPosed evidence waits instead of reporting inactive`() {
        val result = present(
            mode = ProtectionMode.XPOSED,
            target = target(
                frameworkConnected = false,
                managedHookState = ManagedAppHookState.COMPATIBILITY_PENDING,
                hookVersionCode = 0,
            ),
        )
        assertEquals(
            ProtectionPresentationState.XPOSED_WAITING_VERIFICATION,
            result.state,
        )
        assertEquals(ProtectionPresentationSeverity.INFORMATION, result.severity)
    }

    @Test
    fun `scope is ready even when target was not started`() {
        val result = present(
            mode = ProtectionMode.XPOSED,
            target = target(managedHookState = ManagedAppHookState.IN_SCOPE_IDLE),
        )
        assertEquals(ProtectionPresentationState.XPOSED_SCOPE_READY, result.state)
        assertEquals(ProtectionPresentationSeverity.HEALTHY, result.severity)
    }

    @Test
    fun `unavailable Shizuku presents basic protection fallback`() {
        val result = present(
            mode = ProtectionMode.ACCESSIBILITY_SHIZUKU,
            target = target(selectedMode = ProtectionMode.ACCESSIBILITY_SHIZUKU),
            shizukuState = ShizukuExecutionState.UNAVAILABLE,
        )
        assertEquals(
            ProtectionPresentationState.ACCESSIBILITY_SHIZUKU_FALLBACK,
            result.state,
        )
        assertEquals(ProtectionPresentationSeverity.WARNING, result.severity)
    }

    @Test
    fun `missing basic permission is repair required`() {
        val result = present(
            mode = ProtectionMode.ACCESSIBILITY,
            target = target(selectedMode = ProtectionMode.ACCESSIBILITY),
            accessibilityState = AccessibilityRuntimeState.ENABLED_DISCONNECTED,
        )
        assertEquals(ProtectionPresentationState.ACCESSIBILITY_NOT_READY, result.state)
        assertEquals(ProtectionPresentationSeverity.REPAIR_REQUIRED, result.severity)
    }

    private fun present(
        mode: ProtectionMode,
        target: TargetProtectionStatus,
        accessibilityState: AccessibilityRuntimeState = AccessibilityRuntimeState.CONNECTED,
        shizukuState: ShizukuExecutionState = ShizukuExecutionState.READY,
    ) = ProtectionPresentationPolicy.resolve(
        selectedMode = mode,
        targets = listOf(target),
        accessibilityState = accessibilityState,
        usageAccessGranted = true,
        shizukuState = shizukuState,
    )

    private fun target(
        selectedMode: ProtectionMode = ProtectionMode.XPOSED,
        frameworkConnected: Boolean = true,
        managedHookState: ManagedAppHookState = ManagedAppHookState.RUNNING_CURRENT,
        hookVersionCode: Int = 37,
    ) = ProtectionStatusPolicy.resolveTarget(
        TargetProtectionInput(
            packageName = "com.example.target",
            hasSavedRule = true,
            frameworkConnected = frameworkConnected,
            managedHookState = managedHookState,
            hookVersionCode = hookVersionCode,
            hookModeGeneration = 1L,
            currentVersionCode = 37,
            selectedMode = selectedMode,
            selectedModeGeneration = 1L,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            shizukuState = ShizukuExecutionState.READY,
        ),
    )
}
