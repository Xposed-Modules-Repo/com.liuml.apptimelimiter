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

    @Test
    fun `window count is bounded when encoding and decoding`() {
        val windows = List(ScheduleCodec.MAX_WINDOWS + 5) { index ->
            ScheduleWindow(setOf(1), index, index + 1)
        }

        val encoded = ScheduleCodec.encode(windows)

        assertEquals(ScheduleCodec.MAX_WINDOWS, ScheduleCodec.decode(encoded).size)
        assertEquals(
            ScheduleCodec.MAX_WINDOWS,
            ScheduleCodec.decode(List(ScheduleCodec.MAX_WINDOWS + 5) { "1:$it-${it + 1}" }.joinToString(";")).size,
        )
    }
}
