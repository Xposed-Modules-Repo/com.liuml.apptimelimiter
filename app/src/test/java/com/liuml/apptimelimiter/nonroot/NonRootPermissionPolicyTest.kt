package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootPermissionPolicyTest {
    @Test
    fun disabledProtectionNeedsNoPermissionPrompt() {
        assertTrue(
            NonRootPermissionPolicy.issues(
                nonRootEnabled = false,
                accessibilityState = AccessibilityRuntimeState.DISABLED,
                usageAccessGranted = false,
                shizukuSelected = true,
                shizukuState = ShizukuExecutionState.UNAVAILABLE,
            ).isEmpty(),
        )
    }

    @Test
    fun basicPermissionsAreRequiredInPriorityOrder() {
        val issues = NonRootPermissionPolicy.issues(
            nonRootEnabled = true,
            accessibilityState = AccessibilityRuntimeState.DISABLED,
            usageAccessGranted = false,
            shizukuSelected = false,
            shizukuState = ShizukuExecutionState.DISABLED,
        )

        assertEquals(
            NonRootPermissionIssue.ACCESSIBILITY_DISABLED,
            NonRootPermissionPolicy.primaryIssue(issues),
        )
        assertFalse(NonRootPermissionPolicy.basicProtectionAvailable(issues))
    }

    @Test
    fun shizukuFailureDoesNotDisableBasicProtection() {
        val issues = NonRootPermissionPolicy.issues(
            nonRootEnabled = true,
            accessibilityState = AccessibilityRuntimeState.CONNECTED,
            usageAccessGranted = true,
            shizukuSelected = true,
            shizukuState = ShizukuExecutionState.PERMISSION_REQUIRED,
        )

        assertEquals(
            listOf(NonRootPermissionIssue.SHIZUKU_PERMISSION_REQUIRED),
            issues,
        )
        assertTrue(NonRootPermissionPolicy.basicProtectionAvailable(issues))
    }

    @Test
    fun readyShizukuHasNoIssues() {
        assertTrue(
            NonRootPermissionPolicy.issues(
                nonRootEnabled = true,
                accessibilityState = AccessibilityRuntimeState.CONNECTED,
                usageAccessGranted = true,
                shizukuSelected = true,
                shizukuState = ShizukuExecutionState.READY,
            ).isEmpty(),
        )
    }

    @Test
    fun configuredButDisconnectedServiceIsReportedSeparately() {
        val issues = NonRootPermissionPolicy.issues(
            nonRootEnabled = true,
            accessibilityState = AccessibilityRuntimeState.ENABLED_DISCONNECTED,
            usageAccessGranted = true,
            shizukuSelected = false,
            shizukuState = ShizukuExecutionState.DISABLED,
        )

        assertEquals(listOf(NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED), issues)
        assertFalse(NonRootPermissionPolicy.basicProtectionAvailable(issues))
    }
}
