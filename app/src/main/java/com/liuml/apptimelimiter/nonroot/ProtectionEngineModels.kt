package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.data.ProtectionMode

enum class ProtectionEngine {
    XPOSED,
    ACCESSIBILITY_SHIZUKU,
    ACCESSIBILITY_ONLY,
    INACTIVE,
}

enum class ProtectionDegradedReason {
    NONE,
    NON_ROOT_DISABLED,
    ACCESSIBILITY_DISABLED,
    ACCESSIBILITY_DISCONNECTED,
    USAGE_ACCESS_MISSING,
    SHIZUKU_DISABLED,
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_PERMISSION_MISSING,
    WAITING_FOR_HOOK,
}

data class ProtectionEngineSnapshot(
    val engine: ProtectionEngine = ProtectionEngine.INACTIVE,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConfigured: Boolean = false,
    val accessibilityRuntimeState: AccessibilityRuntimeState = AccessibilityRuntimeState.DISABLED,
    val usageAccessGranted: Boolean = false,
    val shizukuEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val selectedMode: ProtectionMode = ProtectionMode.XPOSED,
    val degradedReason: ProtectionDegradedReason = ProtectionDegradedReason.NON_ROOT_DISABLED,
)

object ProtectionEnginePolicy {
    fun resolve(
        protectionMode: ProtectionMode,
        accessibilityEnabled: Boolean,
        accessibilityConfigured: Boolean = accessibilityEnabled,
        usageAccessGranted: Boolean,
        shizukuAvailable: Boolean,
        shizukuPermissionGranted: Boolean,
    ): ProtectionEngineSnapshot {
        if (protectionMode == ProtectionMode.XPOSED) {
            return ProtectionEngineSnapshot(
                engine = ProtectionEngine.XPOSED,
                accessibilityEnabled = accessibilityEnabled,
                accessibilityConfigured = accessibilityConfigured,
                accessibilityRuntimeState = when {
                    accessibilityEnabled -> AccessibilityRuntimeState.CONNECTED
                    accessibilityConfigured -> AccessibilityRuntimeState.ENABLED_DISCONNECTED
                    else -> AccessibilityRuntimeState.DISABLED
                },
                usageAccessGranted = usageAccessGranted,
                shizukuEnabled = false,
                shizukuAvailable = shizukuAvailable,
                shizukuPermissionGranted = shizukuPermissionGranted,
                selectedMode = protectionMode,
                degradedReason = ProtectionDegradedReason.NONE,
            )
        }
        val shizukuEnabled = protectionMode == ProtectionMode.ACCESSIBILITY_SHIZUKU
        val reason = when {
            !accessibilityConfigured -> ProtectionDegradedReason.ACCESSIBILITY_DISABLED
            !accessibilityEnabled -> ProtectionDegradedReason.ACCESSIBILITY_DISCONNECTED
            !usageAccessGranted -> ProtectionDegradedReason.USAGE_ACCESS_MISSING
            shizukuEnabled && !shizukuAvailable ->
                ProtectionDegradedReason.SHIZUKU_UNAVAILABLE
            shizukuEnabled && !shizukuPermissionGranted ->
                ProtectionDegradedReason.SHIZUKU_PERMISSION_MISSING
            !shizukuEnabled -> ProtectionDegradedReason.SHIZUKU_DISABLED
            else -> ProtectionDegradedReason.NONE
        }
        val basicReady = accessibilityEnabled && usageAccessGranted
        val engine = when {
            !basicReady -> ProtectionEngine.INACTIVE
            shizukuEnabled && shizukuAvailable && shizukuPermissionGranted ->
                ProtectionEngine.ACCESSIBILITY_SHIZUKU
            else -> ProtectionEngine.ACCESSIBILITY_ONLY
        }
        return ProtectionEngineSnapshot(
            engine = engine,
            accessibilityEnabled = accessibilityEnabled,
            accessibilityConfigured = accessibilityConfigured,
            accessibilityRuntimeState = when {
                accessibilityEnabled -> AccessibilityRuntimeState.CONNECTED
                accessibilityConfigured -> AccessibilityRuntimeState.ENABLED_DISCONNECTED
                else -> AccessibilityRuntimeState.DISABLED
            },
            usageAccessGranted = usageAccessGranted,
            shizukuEnabled = shizukuEnabled,
            shizukuAvailable = shizukuAvailable,
            shizukuPermissionGranted = shizukuPermissionGranted,
            selectedMode = protectionMode,
            degradedReason = reason,
        )
    }

    fun planPromptDelayMillis(
        foregroundDetectedAtMillis: Long,
        nowMillis: Long,
        stableMillis: Long = PLAN_PROMPT_STABLE_MILLIS,
    ): Long = (
        safeAdd(
            foregroundDetectedAtMillis,
            stableMillis.coerceAtLeast(0L),
        ) - nowMillis
        ).coerceAtLeast(0L)

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    const val PLAN_PROMPT_STABLE_MILLIS = 500L
}
