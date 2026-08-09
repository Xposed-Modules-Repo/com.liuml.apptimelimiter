package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSnapshotSelectionPolicyTest {
    @Test
    fun `new reset generation overrides an older cached rule version`() {
        assertTrue(
            RuleSnapshotSelectionPolicy.shouldUseShared(
                sharedGeneration = 200L,
                sharedVersion = Long.MIN_VALUE,
                cachedGeneration = 100L,
                cachedVersion = 9_999L,
            ),
        )
    }

    @Test
    fun `same generation uses the newest rule version`() {
        assertTrue(
            RuleSnapshotSelectionPolicy.shouldUseShared(
                sharedGeneration = 100L,
                sharedVersion = 20L,
                cachedGeneration = 100L,
                cachedVersion = 19L,
            ),
        )
        assertFalse(
            RuleSnapshotSelectionPolicy.shouldUseShared(
                sharedGeneration = 100L,
                sharedVersion = 18L,
                cachedGeneration = 100L,
                cachedVersion = 19L,
            ),
        )
    }

    @Test
    fun `older shared generation cannot revive stale data`() {
        assertFalse(
            RuleSnapshotSelectionPolicy.shouldUseShared(
                sharedGeneration = 99L,
                sharedVersion = Long.MAX_VALUE,
                cachedGeneration = 100L,
                cachedVersion = 1L,
            ),
        )
    }

    @Test
    fun `newer shared cooldown is not hidden by an older local cache`() {
        assertTrue(
            RuleSnapshotSelectionPolicy.shouldUseSharedCooldown(
                sharedEndsAtMillis = 20_000L,
                cachedEndsAtMillis = 10_000L,
            ),
        )
        assertFalse(
            RuleSnapshotSelectionPolicy.shouldUseSharedCooldown(
                sharedEndsAtMillis = 10_000L,
                cachedEndsAtMillis = 20_000L,
            ),
        )
    }
}
