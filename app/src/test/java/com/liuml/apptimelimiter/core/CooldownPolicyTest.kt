package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CooldownPolicyTest {
    @Test
    fun `cooldown requires a daily or per-launch quota`() {
        assertEquals(false, CooldownPolicy.canEnable(false, false))
        assertEquals(true, CooldownPolicy.canEnable(true, false))
        assertEquals(true, CooldownPolicy.canEnable(false, true))
    }

    @Test
    fun `remaining time decreases from forced exit`() {
        assertEquals(
            40_000L,
            CooldownPolicy.remainingMillis(10_000L, 60_000L, 30_000L),
        )
    }

    @Test
    fun `expired or missing cooldown returns zero`() {
        assertEquals(0L, CooldownPolicy.remainingMillis(10_000L, 60_000L, 70_000L))
        assertEquals(0L, CooldownPolicy.remainingMillis(0L, 60_000L, 30_000L))
    }

    @Test
    fun `backward wall clock never extends beyond configured duration`() {
        assertEquals(
            60_000L,
            CooldownPolicy.remainingMillis(10_000L, 60_000L, 5_000L),
        )
    }
}
