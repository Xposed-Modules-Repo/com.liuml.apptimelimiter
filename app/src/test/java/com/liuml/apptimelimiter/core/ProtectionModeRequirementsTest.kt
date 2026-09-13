package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.ForceStopEnhancement
import com.liuml.apptimelimiter.nonroot.AccessibilityRuntimeState
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionModeRequirementsTest {
    @Test
    fun xposedShowsHookAndOnlyItsEnabledRootEnhancement() {
        val status = ProtectionModeRequirementsPolicy.resolve(
            ProtectionMode.XPOSED,
            ManagedAppHookState.RUNNING_CURRENT,
            AccessibilityRuntimeState.DISABLED,
            usageAccessGranted = false,
            shizukuState = ShizukuExecutionState.DISABLED,
            xposedRootEnhancementEnabled = true,
            accessibilityEnhancement = ForceStopEnhancement.NONE,
        )

        assertEquals(
            listOf(ProtectionRequirement.LSPOSED_HOOK, ProtectionRequirement.ROOT),
            status.items.map { it.requirement },
        )
        assertTrue(status.isEffective)
    }

    @Test
    fun accessibilityShowsOnlyItsRequirements() {
        val status = ProtectionModeRequirementsPolicy.resolve(
            ProtectionMode.ACCESSIBILITY,
            hookState = ManagedAppHookState.RUNNING_FAILED,
            accessibilityState = AccessibilityRuntimeState.CONNECTED,
            usageAccessGranted = true,
            shizukuState = ShizukuExecutionState.READY,
            xposedRootEnhancementEnabled = false,
            accessibilityEnhancement = ForceStopEnhancement.NONE,
        )

        assertEquals(
            listOf(
                ProtectionRequirement.ACCESSIBILITY_SERVICE,
                ProtectionRequirement.USAGE_ACCESS,
            ),
            status.items.map { it.requirement },
        )
    }

    @Test
    fun shizukuEnhancementAddsShizukuButNeverRoot() {
        val status = ProtectionModeRequirementsPolicy.resolve(
            ProtectionMode.ACCESSIBILITY,
            hookState = null,
            accessibilityState = AccessibilityRuntimeState.CONNECTED,
            usageAccessGranted = true,
            shizukuState = ShizukuExecutionState.READY,
            xposedRootEnhancementEnabled = false,
            accessibilityEnhancement = ForceStopEnhancement.SHIZUKU,
        )

        assertEquals(
            listOf(
                ProtectionRequirement.ACCESSIBILITY_SERVICE,
                ProtectionRequirement.USAGE_ACCESS,
                ProtectionRequirement.SHIZUKU,
            ),
            status.items.map { it.requirement },
        )
    }
}
