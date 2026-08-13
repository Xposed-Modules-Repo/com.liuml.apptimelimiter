package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBlockPolicyTest {
    @Test
    fun `same blocked interval is recorded once`() {
        val token = ScheduleBlockPolicy.token(7L, 11L, ScheduleMode.BLOCK_DURING, 123_456L)

        assertTrue(ScheduleBlockPolicy.shouldRecord(null, token))
        assertFalse(ScheduleBlockPolicy.shouldRecord(token, token))
    }

    @Test
    fun `new boundary or rule version creates a new interval`() {
        val original = ScheduleBlockPolicy.token(7L, 11L, ScheduleMode.BLOCK_DURING, 123_456L)
        val nextBoundary = ScheduleBlockPolicy.token(7L, 11L, ScheduleMode.BLOCK_DURING, 456_789L)
        val nextVersion = ScheduleBlockPolicy.token(8L, 11L, ScheduleMode.BLOCK_DURING, 123_456L)
        val nextGroupVersion = ScheduleBlockPolicy.token(
            7L,
            12L,
            ScheduleMode.BLOCK_DURING,
            123_456L,
        )

        assertNotEquals(original, nextBoundary)
        assertNotEquals(original, nextVersion)
        assertNotEquals(original, nextGroupVersion)
    }
}
