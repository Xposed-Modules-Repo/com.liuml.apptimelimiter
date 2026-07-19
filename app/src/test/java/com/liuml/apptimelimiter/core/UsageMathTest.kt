package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMathTest {
    @Test
    fun `remaining time subtracts committed and current segment`() {
        assertEquals(4_000L, UsageMath.remainingMillis(10_000L, 4_000L, 2_000L))
    }

    @Test
    fun `remaining time never becomes negative`() {
        assertEquals(0L, UsageMath.remainingMillis(10_000L, 9_000L, 2_000L))
    }

    @Test
    fun `limit is reached exactly at boundary`() {
        assertFalse(UsageMath.isLimitReached(10_000L, 7_000L, 2_999L))
        assertTrue(UsageMath.isLimitReached(10_000L, 7_000L, 3_000L))
    }

    @Test
    fun `warning is scheduled five seconds before deadline`() {
        assertEquals(55_000L, UsageMath.warningDelayMillis(60_000L, 5_000L))
        assertEquals(0L, UsageMath.warningDelayMillis(3_000L, 5_000L))
    }

    @Test
    fun `each extension is added but capped`() {
        assertEquals(600_000L, UsageMath.addExtensionMillis(300_000L, 300_000L, 900_000L))
        assertEquals(900_000L, UsageMath.addExtensionMillis(800_000L, 300_000L, 900_000L))
    }

    @Test
    fun `earliest enabled threshold determines exit deadline`() {
        assertEquals(20_000L, UsageMath.earliestRemainingMillis(listOf(120_000L, 20_000L)))
        assertEquals(0L, UsageMath.earliestRemainingMillis(listOf(0L, 20_000L)))
    }

    @Test
    fun `system usage wins when it is greater than hook usage`() {
        assertEquals(90_000L, UsageMath.authoritativeDailyUsedMillis(60_000L, 90_000L))
        assertEquals(60_000L, UsageMath.authoritativeDailyUsedMillis(60_000L, -1L))
    }
}
