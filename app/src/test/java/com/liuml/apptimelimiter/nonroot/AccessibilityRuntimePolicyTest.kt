package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRuntimePolicyTest {
    @Test
    fun `secure setting accepts full and relative component names`() {
        assertTrue(
            AccessibilityRuntimePolicy.containsComponent(
                "other/.Service:$PACKAGE/.nonroot.TimeStopAccessibilityService",
                PACKAGE,
                SERVICE,
            ),
        )
        assertTrue(
            AccessibilityRuntimePolicy.containsComponent(
                "$PACKAGE/$SERVICE",
                PACKAGE,
                SERVICE,
            ),
        )
        assertFalse(
            AccessibilityRuntimePolicy.containsComponent(
                "other/.nonroot.TimeStopAccessibilityService",
                PACKAGE,
                SERVICE,
            ),
        )
    }

    @Test
    fun `live service connection overrides stale system lists`() {
        val connected = AccessibilityRuntimePolicy.resolve(
            configuredBySecureSettings = false,
            configuredByAccessibilityManager = false,
            serviceConnected = true,
            usageAccessGranted = true,
        )
        assertEquals(AccessibilityRuntimeState.CONNECTED, connected.state)
        assertEquals(AccessibilityDetectionSource.LIVE_SERVICE, connected.detectionSource)
        assertTrue(connected.readyForProtection)
    }

    @Test
    fun `configured service without connection is not protection ready`() {
        val snapshot = AccessibilityRuntimePolicy.resolve(
            configuredBySecureSettings = true,
            configuredByAccessibilityManager = false,
            serviceConnected = false,
            usageAccessGranted = true,
        )
        assertEquals(AccessibilityRuntimeState.ENABLED_DISCONNECTED, snapshot.state)
        assertTrue(snapshot.systemConfigured)
        assertFalse(snapshot.readyForProtection)
    }

    private companion object {
        const val PACKAGE = "com.liuml.apptimelimiter"
        const val SERVICE =
            "com.liuml.apptimelimiter.nonroot.TimeStopAccessibilityService"
    }
}
