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

    @Test
    fun `repeated denied evaluations keep the same next allowed boundary`() {
        val windows = listOf(
            ScheduleWindow(setOf(1), 20 * 60, 23 * 60),
            ScheduleWindow(setOf(1), 22 * 60, 7 * 60),
        )
        val first = ZonedDateTime.of(2026, 7, 13, 22, 15, 1, 0, zone)
        val second = first.plusSeconds(37)

        val firstDecision = ScheduleEvaluator.evaluate(ScheduleMode.BLOCK_DURING, windows, first)
        val secondDecision = ScheduleEvaluator.evaluate(ScheduleMode.BLOCK_DURING, windows, second)

        assertFalse(firstDecision.allowed)
        assertFalse(secondDecision.allowed)
        assertEquals(firstDecision.nextTransition, secondDecision.nextTransition)
        assertEquals(7, firstDecision.nextTransition?.hour)
        assertEquals(14, firstDecision.nextTransition?.dayOfMonth)
    }

    @Test
    fun `adjacent blocked windows do not expose a false available boundary`() {
        val windows = listOf(
            ScheduleWindow(setOf(1), 18 * 60, 20 * 60),
            ScheduleWindow(setOf(1), 20 * 60, 22 * 60),
        )

        val decision = ScheduleEvaluator.evaluate(ScheduleMode.BLOCK_DURING, windows, at(19, 30))

        assertFalse(decision.allowed)
        assertEquals(22, decision.nextTransition?.hour)
        assertEquals(0, decision.nextTransition?.minute)
    }

    @Test
    fun `app and group allow-only schedules use their intersection`() {
        val decision = ScheduleEvaluator.evaluateAll(
            constraints = listOf(
                ScheduleConstraint(
                    ScheduleMode.ALLOW_ONLY,
                    listOf(ScheduleWindow(setOf(1), 8 * 60, 22 * 60)),
                ),
                ScheduleConstraint(
                    ScheduleMode.ALLOW_ONLY,
                    listOf(ScheduleWindow(setOf(1), 18 * 60, 20 * 60)),
                ),
            ),
            now = at(17, 0),
        )

        assertFalse(decision.allowed)
        assertEquals(18, decision.nextTransition?.hour)
    }

    @Test
    fun `overlapping app and group blocks report the final available boundary`() {
        val decision = ScheduleEvaluator.evaluateAll(
            constraints = listOf(
                ScheduleConstraint(
                    ScheduleMode.BLOCK_DURING,
                    listOf(ScheduleWindow(setOf(1), 18 * 60, 20 * 60)),
                ),
                ScheduleConstraint(
                    ScheduleMode.BLOCK_DURING,
                    listOf(ScheduleWindow(setOf(1), 19 * 60, 22 * 60)),
                ),
            ),
            now = at(19, 30),
        )

        assertFalse(decision.allowed)
        assertEquals(22, decision.nextTransition?.hour)
        assertEquals(0, decision.nextTransition?.minute)
    }

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 7, 13, hour, minute, 0, 0, zone)
}
