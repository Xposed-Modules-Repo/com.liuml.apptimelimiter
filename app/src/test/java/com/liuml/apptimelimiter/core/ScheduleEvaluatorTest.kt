package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEvaluatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `allow-only mode denies outside and allows inside window`() {
        val window = ScheduleWindow(setOf(1, 2, 3, 4, 5), 18 * 60, 22 * 60)

        assertFalse(ScheduleEvaluator.evaluate(ScheduleMode.ALLOW_ONLY, listOf(window), at(17, 0)).allowed)
        assertTrue(ScheduleEvaluator.evaluate(ScheduleMode.ALLOW_ONLY, listOf(window), at(19, 0)).allowed)
    }

    @Test
    fun `blocked cross-midnight window continues into next day`() {
        val window = ScheduleWindow(setOf(1), 22 * 60, 7 * 60)

        assertFalse(ScheduleEvaluator.evaluate(ScheduleMode.BLOCK_DURING, listOf(window), at(23, 0)).allowed)
        assertFalse(
            ScheduleEvaluator.evaluate(
                ScheduleMode.BLOCK_DURING,
                listOf(window),
                ZonedDateTime.of(2026, 7, 14, 6, 30, 0, 0, zone),
            ).allowed,
        )
        assertTrue(
            ScheduleEvaluator.evaluate(
                ScheduleMode.BLOCK_DURING,
                listOf(window),
                ZonedDateTime.of(2026, 7, 14, 7, 0, 0, 0, zone),
            ).allowed,
        )
    }

    @Test
    fun `overlapping windows transition only when effective access changes`() {
        val windows = listOf(
            ScheduleWindow(setOf(1), 18 * 60, 20 * 60),
            ScheduleWindow(setOf(1), 19 * 60, 22 * 60),
        )

        val decision = ScheduleEvaluator.evaluate(ScheduleMode.ALLOW_ONLY, windows, at(18, 30))

        assertTrue(decision.allowed)
        assertEquals(22, decision.nextTransition?.hour)
        assertEquals(0, decision.nextTransition?.minute)
    }

    @Test
    fun `denied state reports next allowed boundary`() {
        val window = ScheduleWindow(setOf(1), 22 * 60, 7 * 60)
        val decision = ScheduleEvaluator.evaluate(ScheduleMode.BLOCK_DURING, listOf(window), at(23, 0))

        assertFalse(decision.allowed)
        assertEquals(7, decision.nextTransition?.hour)
        assertEquals(14, decision.nextTransition?.dayOfMonth)
    }

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 7, 13, hour, minute, 0, 0, zone)
}
