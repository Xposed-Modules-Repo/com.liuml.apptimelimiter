package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.ForceStopEnhancement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionExecutorPolicyTest {
    @Test fun xposedUsesOnlyXposed() {
        assertEquals(
            RestrictionExecutorType.XPOSED,
            RestrictionExecutorSelectionPolicy.select(ProtectionMode.XPOSED, ForceStopEnhancement.ROOT),
        )
        assertEquals(null, RestrictionExecutorSelectionPolicy.fallbackFor(RestrictionExecutorType.XPOSED))
    }

    @Test fun rootIsOnlyAvailableForOrdinaryAccessibility() {
        assertEquals(
            RestrictionExecutorType.ROOT,
            RestrictionExecutorSelectionPolicy.select(ProtectionMode.ACCESSIBILITY, ForceStopEnhancement.ROOT),
        )
        assertEquals(
            RestrictionExecutorType.ACCESSIBILITY,
            RestrictionExecutorSelectionPolicy.select(ProtectionMode.ACCESSIBILITY, ForceStopEnhancement.NONE),
        )
        assertEquals(
            RestrictionExecutorType.SHIZUKU,
            RestrictionExecutorSelectionPolicy.select(ProtectionMode.ACCESSIBILITY, ForceStopEnhancement.SHIZUKU),
        )
    }

    @Test fun enhancedExecutorFailuresFallBackToAccessibilityOnly() {
        assertEquals(
            RestrictionExecutorType.ACCESSIBILITY,
            RestrictionExecutorSelectionPolicy.fallbackFor(RestrictionExecutorType.ROOT),
        )
        assertEquals(
            RestrictionExecutorType.ACCESSIBILITY,
            RestrictionExecutorSelectionPolicy.fallbackFor(RestrictionExecutorType.SHIZUKU),
        )
    }

    @Test fun duplicateIncidentIsClaimedOnce() {
        val dedupe = RestrictionIncidentDeduplicator(maxEntries = 2)
        assertTrue(dedupe.claim("a"))
        assertFalse(dedupe.claim("a"))
        assertTrue(dedupe.claim("b"))
        assertTrue(dedupe.claim("c"))
        assertTrue(dedupe.claim("a"))
    }
}
