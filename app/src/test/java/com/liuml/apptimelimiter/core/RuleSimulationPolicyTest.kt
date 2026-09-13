package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RuleSimulationPolicyTest {
    private val now = ZonedDateTime.of(2026, 9, 5, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test fun `remaining quota is shown without changing the rule`() {
        val rule = AppRule(packageName = "demo", dailyEnabled = true, dailyLimitSeconds = 600)
        val result = RuleSimulationPolicy.evaluate(RuleSimulationInput(rule, dailyUsedMillis = 120_000, now = now))
        assertTrue(result.allowed)
        assertEquals(480_000L, result.remainingMillis)
    }

    @Test fun `schedule and cooldown take precedence over quota`() {
        val rule = AppRule(
            packageName = "demo",
            scheduleEnabled = true,
            scheduleMode = ScheduleMode.BLOCK_DURING,
            scheduleWindows = listOf(ScheduleWindow(setOf(6), 11 * 60, 13 * 60)),
            dailyEnabled = true,
            dailyLimitSeconds = 1,
        )
        val scheduled = RuleSimulationPolicy.evaluate(RuleSimulationInput(rule, now = now))
        assertFalse(scheduled.allowed)
        assertEquals(RuleSimulationReason.SCHEDULE, scheduled.reason)
        val cooldown = RuleSimulationPolicy.evaluate(RuleSimulationInput(rule, cooldownRemainingMillis = 3_000, now = now.withHour(15)))
        assertFalse(cooldown.allowed)
        assertEquals(RuleSimulationReason.COOLDOWN, cooldown.reason)
    }
}
