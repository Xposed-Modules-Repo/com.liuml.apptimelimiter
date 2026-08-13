package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionEnginePolicyTest {
    @Test
    fun `selected LSPosed mode disables non root execution`() {
        val snapshot = ProtectionEnginePolicy.resolve(
            protectionMode = ProtectionMode.XPOSED,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            shizukuAvailable = true,
            shizukuPermissionGranted = true,
        )
        assertEquals(ProtectionEngine.XPOSED, snapshot.engine)
    }

    @Test
    fun `Shizuku mode falls back to accessibility when unavailable`() {
        val snapshot = ProtectionEnginePolicy.resolve(
            protectionMode = ProtectionMode.ACCESSIBILITY_SHIZUKU,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            shizukuAvailable = false,
            shizukuPermissionGranted = false,
        )
        assertEquals(ProtectionEngine.ACCESSIBILITY_ONLY, snapshot.engine)
        assertEquals(ProtectionDegradedReason.SHIZUKU_UNAVAILABLE, snapshot.degradedReason)
    }

    @Test
    fun `missing usage access keeps ordinary protection inactive`() {
        val snapshot = ProtectionEnginePolicy.resolve(
            protectionMode = ProtectionMode.ACCESSIBILITY,
            accessibilityEnabled = true,
            usageAccessGranted = false,
            shizukuAvailable = false,
            shizukuPermissionGranted = false,
        )
        assertEquals(ProtectionEngine.INACTIVE, snapshot.engine)
        assertEquals(ProtectionDegradedReason.USAGE_ACCESS_MISSING, snapshot.degradedReason)
    }

    @Test
    fun `ordinary accessibility mode is healthy without Shizuku`() {
        val snapshot = ProtectionEnginePolicy.resolve(
            protectionMode = ProtectionMode.ACCESSIBILITY,
            accessibilityEnabled = true,
            usageAccessGranted = true,
            shizukuAvailable = false,
            shizukuPermissionGranted = false,
        )

        assertEquals(ProtectionEngine.ACCESSIBILITY_ONLY, snapshot.engine)
        assertEquals(ProtectionDegradedReason.NONE, snapshot.degradedReason)
    }

    @Test
    fun `configured but disconnected accessibility is not ready`() {
        val snapshot = ProtectionEnginePolicy.resolve(
            protectionMode = ProtectionMode.ACCESSIBILITY,
            accessibilityEnabled = false,
            accessibilityConfigured = true,
            usageAccessGranted = true,
            shizukuAvailable = false,
            shizukuPermissionGranted = false,
        )
        assertEquals(ProtectionEngine.INACTIVE, snapshot.engine)
        assertEquals(
            AccessibilityRuntimeState.ENABLED_DISCONNECTED,
            snapshot.accessibilityRuntimeState,
        )
        assertEquals(
            ProtectionDegradedReason.ACCESSIBILITY_DISCONNECTED,
            snapshot.degradedReason,
        )
    }

    @Test
    fun `plan prompt only waits for stable foreground window`() {
        assertEquals(
            300L,
            ProtectionEnginePolicy.planPromptDelayMillis(
                foregroundDetectedAtMillis = 10_000L,
                nowMillis = 10_200L,
            ),
        )
        assertEquals(
            0L,
            ProtectionEnginePolicy.planPromptDelayMillis(
                foregroundDetectedAtMillis = 10_000L,
                nowMillis = 10_500L,
            ),
        )
    }
}
