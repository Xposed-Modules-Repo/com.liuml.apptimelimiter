package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionModePolicyTest {
    @Test
    fun `legacy settings migrate to one exclusive mode`() {
        assertEquals(
            ProtectionMode.XPOSED,
            ProtectionModePolicy.parse(null, false, false),
        )
        assertEquals(
            ProtectionMode.ACCESSIBILITY,
            ProtectionModePolicy.parse(null, true, false),
        )
        assertEquals(
            ProtectionMode.ACCESSIBILITY_SHIZUKU,
            ProtectionModePolicy.parse(null, true, true),
        )
    }

    @Test
    fun `stored mode is authoritative and unknown values fall back to LSPosed`() {
        assertEquals(
            ProtectionMode.XPOSED,
            ProtectionModePolicy.parse(ProtectionMode.XPOSED.name, true, true),
        )
        assertEquals(
            ProtectionMode.XPOSED,
            ProtectionModePolicy.parse("UNKNOWN", true, false),
        )
    }

    @Test
    fun `generation changes only when mode changes`() {
        assertEquals(
            8L,
            ProtectionModePolicy.nextGeneration(
                previousMode = ProtectionMode.XPOSED,
                requestedMode = ProtectionMode.XPOSED,
                previousGeneration = 8L,
                wallClockMillis = 100L,
            ),
        )
        assertEquals(
            100L,
            ProtectionModePolicy.nextGeneration(
                previousMode = ProtectionMode.XPOSED,
                requestedMode = ProtectionMode.ACCESSIBILITY,
                previousGeneration = 8L,
                wallClockMillis = 100L,
            ),
        )
    }
}
