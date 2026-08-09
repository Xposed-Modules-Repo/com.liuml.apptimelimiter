package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanDurationPolicyTest {
    @Test
    fun `empty and non numeric values are rejected`() {
        assertEquals(
            SessionPlanDurationStatus.EMPTY,
            SessionPlanDurationPolicy.evaluate("  ", null).status,
        )
        assertEquals(
            SessionPlanDurationStatus.NON_NUMERIC,
            SessionPlanDurationPolicy.evaluate("12.5", null).status,
        )
    }

    @Test
    fun `zero and values above one day are rejected`() {
        assertEquals(
            SessionPlanDurationStatus.ZERO,
            SessionPlanDurationPolicy.evaluate("0000", null).status,
        )
        assertEquals(
            SessionPlanDurationStatus.OUT_OF_RANGE,
            SessionPlanDurationPolicy.evaluate("1441", null).status,
        )
    }

    @Test
    fun `one minute and one day are valid`() {
        assertEquals(1, SessionPlanDurationPolicy.evaluate("0001", null).totalMinutes)
        assertEquals(1440, SessionPlanDurationPolicy.evaluate("1440", null).totalMinutes)
    }

    @Test
    fun `permanent allowance is enforced in whole minutes`() {
        val maxAllowed = 9 * 60_000L + 59_000L
        assertEquals(
            SessionPlanDurationStatus.VALID,
            SessionPlanDurationPolicy.evaluate("9", maxAllowed).status,
        )
        assertEquals(
            SessionPlanDurationStatus.EXCEEDS_MAX,
            SessionPlanDurationPolicy.evaluate("10", maxAllowed).status,
        )
        assertEquals(9L, SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowed))
    }

    @Test
    fun `quick and debug durations use the same hard boundary`() {
        val maxAllowed = 12 * 60_000L
        assertTrue(SessionPlanDurationPolicy.durationAllowed(10 * 60_000L, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.durationAllowed(15 * 60_000L, maxAllowed))
        assertTrue(SessionPlanDurationPolicy.durationAllowed(10_000L, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.durationAllowed(0L, maxAllowed))
    }
}
