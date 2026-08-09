package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Test

class NonRootSessionResetPolicyTest {
    @Test
    fun `per session without cooldown uses remaining grace`() {
        assertEquals(
            130_000L,
            NonRootSessionResetPolicy.resetAtMillis(
                perSessionReached = true,
                nowWallMillis = 100_000L,
                nowElapsedMillis = 20_000L,
                graceEndsAtElapsedMillis = 50_000L,
                cooldownEndsAtMillis = 0L,
            ),
        )
    }

    @Test
    fun `configured cooldown can extend session reset`() {
        assertEquals(
            180_000L,
            NonRootSessionResetPolicy.resetAtMillis(
                perSessionReached = true,
                nowWallMillis = 100_000L,
                nowElapsedMillis = 20_000L,
                graceEndsAtElapsedMillis = 50_000L,
                cooldownEndsAtMillis = 180_000L,
            ),
        )
    }

    @Test
    fun `daily only incident has no session reset`() {
        assertEquals(
            0L,
            NonRootSessionResetPolicy.resetAtMillis(
                perSessionReached = false,
                nowWallMillis = Long.MAX_VALUE,
                nowElapsedMillis = 0L,
                graceEndsAtElapsedMillis = Long.MAX_VALUE,
                cooldownEndsAtMillis = Long.MAX_VALUE,
            ),
        )
    }
}
