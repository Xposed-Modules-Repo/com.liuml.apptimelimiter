package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerLaunchRestPolicyTest {
    @Test
    fun `short background interval keeps current cycle`() {
        val decision = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 1_000L,
            nowElapsedMillis = 10 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )

        assertFalse(decision.shouldStartNewCycle)
        assertEquals(599_000L, decision.backgroundGapMillis)
    }

    @Test
    fun `rest equal to configured cooldown starts new cycle`() {
        val decision = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 5_000L,
            nowElapsedMillis = 5_000L + 20 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )

        assertTrue(decision.shouldStartNewCycle)
        assertEquals(20 * 60_000L, decision.backgroundGapMillis)
    }

    @Test
    fun `one hour rest resets a thirty minute example session`() {
        val decision = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 100_000L,
            nowElapsedMillis = 100_000L + 60 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )

        assertTrue(decision.shouldStartNewCycle)
    }

    @Test
    fun `active limit state cannot be bypassed by background rest`() {
        val blocked = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 1_000L,
            nowElapsedMillis = 60 * 60_000L,
            blockingStateActive = true,
            activeCooldownRemainingMillis = 0L,
        )
        val coolingDown = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 1_000L,
            nowElapsedMillis = 60 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 1L,
        )

        assertFalse(blocked.shouldStartNewCycle)
        assertFalse(coolingDown.shouldStartNewCycle)
    }

    @Test
    fun `missing quota or cooldown keeps current cycle`() {
        val noQuota = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = false,
            configuredCooldownMillis = 20 * 60_000L,
            backgroundedAtElapsedMillis = 1_000L,
            nowElapsedMillis = 60 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )
        val noCooldown = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 0L,
            backgroundedAtElapsedMillis = 1_000L,
            nowElapsedMillis = 60 * 60_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )

        assertFalse(noQuota.shouldStartNewCycle)
        assertFalse(noCooldown.shouldStartNewCycle)
    }

    @Test
    fun `invalid or rolled back elapsed timestamp does not reset`() {
        val missing = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 1L,
            backgroundedAtElapsedMillis = 0L,
            nowElapsedMillis = 10_000L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )
        val rolledBack = PerLaunchRestPolicy.evaluate(
            hasPerLaunchQuota = true,
            configuredCooldownMillis = 1L,
            backgroundedAtElapsedMillis = 10_000L,
            nowElapsedMillis = 9_999L,
            blockingStateActive = false,
            activeCooldownRemainingMillis = 0L,
        )

        assertFalse(missing.shouldStartNewCycle)
        assertFalse(rolledBack.shouldStartNewCycle)
    }
}
