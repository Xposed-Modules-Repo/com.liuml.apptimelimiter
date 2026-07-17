package com.liuml.apptimelimiter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCodecTest {
    @Test
    fun `valid windows round trip`() {
        val windows = listOf(
            ScheduleWindow(setOf(1, 2, 3, 4, 5), 18 * 60, 22 * 60),
            ScheduleWindow(setOf(6, 7), 23 * 60, 7 * 60),
        )

        assertEquals(windows, ScheduleCodec.decode(ScheduleCodec.encode(windows)))
    }

    @Test
    fun `malformed windows are ignored`() {
        assertTrue(ScheduleCodec.decode("bad;1,2:100-100;9:10-20").isEmpty())
    }
}
