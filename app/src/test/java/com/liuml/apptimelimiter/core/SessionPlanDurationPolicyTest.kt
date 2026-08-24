package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanDurationPolicyTest {
    @Test
    fun `unlimited allowance uses five minute default and sixty minute maximum`() {
        assertEquals(5, SessionPlanDurationPolicy.defaultSelectableMinutes(null))
        assertEquals(60, SessionPlanDurationPolicy.maxSelectableMinutes(null))
    }

    @Test
    fun `allowance below default clamps initial value`() {
        val maxAllowed = 3 * 60_000L + 59_000L
        assertEquals(3, SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowed))
        assertEquals(3, SessionPlanDurationPolicy.defaultSelectableMinutes(maxAllowed))
    }

    @Test
    fun `allowance above one hour is capped at sixty minutes`() {
        val maxAllowed = 24 * 60 * 60_000L
        assertEquals(60, SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowed))
        assertEquals(5, SessionPlanDurationPolicy.defaultSelectableMinutes(maxAllowed))
    }

    @Test
    fun `allowance below one whole minute disables custom plan`() {
        val maxAllowed = 59_999L
        assertEquals(0, SessionPlanDurationPolicy.maxSelectableMinutes(maxAllowed))
        assertNull(SessionPlanDurationPolicy.defaultSelectableMinutes(maxAllowed))
        assertNull(SessionPlanDurationPolicy.normalizeSelectableMinutes(5, maxAllowed))
        assertEquals(1, SessionPlanDurationPolicy.defaultSliderMinutes(maxAllowed))
        assertFalse(SessionPlanDurationPolicy.minutesAllowed(1, maxAllowed))
    }

    @Test
    fun `slider normalization clamps to current selectable range`() {
        val maxAllowed = 12 * 60_000L
        assertEquals(1, SessionPlanDurationPolicy.normalizeSliderMinutes(0))
        assertEquals(60, SessionPlanDurationPolicy.normalizeSliderMinutes(61))
        assertEquals(7, SessionPlanDurationPolicy.normalizeSliderMinutes(7))
        assertTrue(SessionPlanDurationPolicy.minutesAllowed(7, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.minutesAllowed(13, maxAllowed))
    }

    @Test
    fun `quick and debug durations use the same hard boundary`() {
        val maxAllowed = 12 * 60_000L
        assertTrue(SessionPlanDurationPolicy.durationAllowed(10 * 60_000L, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.durationAllowed(15 * 60_000L, maxAllowed))
        assertTrue(SessionPlanDurationPolicy.durationAllowed(10_000L, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.durationAllowed(0L, maxAllowed))
        assertFalse(SessionPlanDurationPolicy.durationAllowed(61 * 60_000L, null))
    }
}
