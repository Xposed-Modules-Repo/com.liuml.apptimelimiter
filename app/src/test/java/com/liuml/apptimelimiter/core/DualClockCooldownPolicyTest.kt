package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualClockCooldownPolicyTest {
    private val record = SharedCooldownRecord(
        startedAtMillis = 1_000L,
        endsAtMillis = 61_000L,
        startedAtElapsedMillis = 10_000L,
        endsAtElapsedMillis = 70_000L,
    )

    @Test fun `dual clock never extends after wall clock is moved back`() {
        assertEquals(40_000L, SharedCooldownPolicy.remainingMillisDual(record, 21_000L, 30_000L))
        assertEquals(0L, SharedCooldownPolicy.remainingMillisDual(record, 1_001L, 70_000L))
    }

    @Test fun `clock skew is detectable`() {
        assertTrue(SharedCooldownPolicy.wallAndElapsedAreConsistent(record, 31_000L, 40_000L))
        assertFalse(SharedCooldownPolicy.wallAndElapsedAreConsistent(record, 100_000L, 40_000L))
    }
}
