package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMilestonePolicyTest {
    @Test
    fun `half hour nodes are calculated without early reminders`() {
        assertEquals(0, UsageMilestonePolicy.reachedIndex(minutes(29)))
        assertEquals(1, UsageMilestonePolicy.reachedIndex(minutes(30)))
        assertEquals(2, UsageMilestonePolicy.reachedIndex(minutes(60)))
        assertEquals(3, UsageMilestonePolicy.reachedIndex(minutes(90)))
        assertEquals(minutes(1), UsageMilestonePolicy.nextDelayMillis(minutes(29)))
    }

    @Test
    fun `usage is bounded to the local day milestones`() {
        assertEquals(48, UsageMilestonePolicy.reachedIndex(Long.MAX_VALUE))
        assertNull(UsageMilestonePolicy.nextDelayMillis(minutes(24 * 60)))
        assertTrue(UsageMilestonePolicy.isValidMilestoneIndex(1))
        assertTrue(UsageMilestonePolicy.isValidMilestoneIndex(48))
        assertFalse(UsageMilestonePolicy.isValidMilestoneIndex(0))
        assertFalse(UsageMilestonePolicy.isValidMilestoneIndex(49))
    }

    @Test
    fun `event identity is stable for one package day and node`() {
        assertEquals(
            UsageMilestonePolicy.eventId("com.example.app", "2026-09-13", 1),
            UsageMilestonePolicy.eventId("com.example.app", "2026-09-13", 1),
        )
    }

    private fun minutes(value: Int): Long = value * 60_000L
}
