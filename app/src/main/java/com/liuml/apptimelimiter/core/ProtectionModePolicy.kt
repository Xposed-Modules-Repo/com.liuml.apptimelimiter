package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode

object ProtectionModePolicy {
    fun parse(
        storedValue: String?,
        legacyNonRootEnabled: Boolean,
        legacyShizukuEnabled: Boolean,
    ): ProtectionMode = if (storedValue != null) {
        when (storedValue) {
            "ACCESSIBILITY_SHIZUKU" -> ProtectionMode.ACCESSIBILITY
            else -> runCatching { ProtectionMode.valueOf(storedValue) }
                .getOrDefault(ProtectionMode.XPOSED)
        }
    } else {
        when {
            !legacyNonRootEnabled -> ProtectionMode.XPOSED
            legacyShizukuEnabled -> ProtectionMode.ACCESSIBILITY
            else -> ProtectionMode.ACCESSIBILITY
        }
    }

    fun nextGeneration(
        previousMode: ProtectionMode,
        requestedMode: ProtectionMode,
        previousGeneration: Long,
        wallClockMillis: Long,
    ): Long = if (previousMode == requestedMode) {
        previousGeneration.coerceAtLeast(1L)
    } else {
        MonotonicVersionPolicy.next(
            previousVersion = previousGeneration.coerceAtLeast(1L),
            wallClockMillis = wallClockMillis,
        )
    }
}
