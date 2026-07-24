package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.LimitEnforcementMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitEnforcementPolicyTest {
    @Test
    fun missingOrUnknownModeFallsBackToForceExit() {
        assertEquals(
            LimitEnforcementMode.FORCE_EXIT,
            LimitEnforcementPolicy.parseMode(null),
        )
        assertEquals(
            LimitEnforcementMode.FORCE_EXIT,
            LimitEnforcementPolicy.parseMode("FUTURE_MODE"),
        )
    }

    @Test
    fun standalonePageRoundTripsAndRemovedModeFallsBackSafely() {
        assertEquals(
            LimitEnforcementMode.FORCE_EXIT,
            LimitEnforcementPolicy.parseMode("BLOCK_OVERLAY"),
        )
        assertEquals(
            LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
            LimitEnforcementPolicy.parseMode(LimitEnforcementMode.EXTERNAL_BREAK_PAGE.name),
        )
        assertFalse(
            LimitEnforcementPolicy.shouldInstallMediaHooks(LimitEnforcementMode.FORCE_EXIT),
        )
        assertTrue(
            LimitEnforcementPolicy.shouldInstallMediaHooks(
                LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
            ),
        )
    }

    @Test
    fun blockingPriorityIsScheduleThenCooldownThenQuota() {
        assertEquals(
            LimitBlockReason.SCHEDULE,
            LimitEnforcementPolicy.firstBlockingReason(true, true, true),
        )
        assertEquals(
            LimitBlockReason.COOLDOWN,
            LimitEnforcementPolicy.firstBlockingReason(false, true, true),
        )
        assertEquals(
            LimitBlockReason.QUOTA,
            LimitEnforcementPolicy.firstBlockingReason(false, false, true),
        )
        assertNull(LimitEnforcementPolicy.firstBlockingReason(false, false, false))
    }

    @Test
    fun anyActiveBlockSuppressesSessionPlanning() {
        val schedule = LimitEnforcementPolicy.evaluate(
            LimitGateSnapshot(true, 10_000L, true),
        )
        val cooldown = LimitEnforcementPolicy.evaluate(
            LimitGateSnapshot(false, 10_000L, true),
        )
        val quota = LimitEnforcementPolicy.evaluate(
            LimitGateSnapshot(false, 0L, true),
        )
        val allowed = LimitEnforcementPolicy.evaluate(
            LimitGateSnapshot(false, 0L, false),
        )

        assertFalse(schedule.sessionPlanAllowed)
        assertFalse(cooldown.sessionPlanAllowed)
        assertFalse(quota.sessionPlanAllowed)
        assertTrue(allowed.sessionPlanAllowed)
    }

    @Test
    fun onlyNewQuotaBlockStartsCooldown() {
        assertFalse(
            LimitEnforcementPolicy.evaluate(
                LimitGateSnapshot(true, 0L, true),
            ).startsCooldownWhenNewlyBlocked,
        )
        assertFalse(
            LimitEnforcementPolicy.evaluate(
                LimitGateSnapshot(false, 1L, true),
            ).startsCooldownWhenNewlyBlocked,
        )
        assertTrue(
            LimitEnforcementPolicy.evaluate(
                LimitGateSnapshot(false, 0L, true),
            ).startsCooldownWhenNewlyBlocked,
        )
    }

    @Test
    fun activeCooldownIsNeverRefreshedByRepeatedQuotaChecks() {
        val quota = LimitEnforcementPolicy.evaluate(
            LimitGateSnapshot(false, 0L, true),
        )
        assertTrue(LimitEnforcementPolicy.shouldStartCooldown(quota, true, 60_000L, 0L))
        assertFalse(LimitEnforcementPolicy.shouldStartCooldown(quota, false, 60_000L, 0L))
        assertFalse(LimitEnforcementPolicy.shouldStartCooldown(quota, true, 60_000L, 30_000L))
        assertFalse(LimitEnforcementPolicy.shouldStartCooldown(quota, true, 0L, 0L))
    }

    @Test
    fun staleActivityCallbackCannotRun() {
        assertTrue(ActivityCallbackPolicy.isCurrent(2, 2, true, true, false))
        assertFalse(ActivityCallbackPolicy.isCurrent(1, 2, true, true, false))
        assertFalse(ActivityCallbackPolicy.isCurrent(2, 2, false, true, false))
        assertFalse(ActivityCallbackPolicy.isCurrent(2, 2, true, false, false))
        assertFalse(ActivityCallbackPolicy.isCurrent(2, 2, true, true, true))
    }

    @Test
    fun restPageStartsNewPerLaunchCycleAfterCooldown() {
        assertTrue(
            RestPagePolicy.shouldStartNewPerLaunchCycle(
                mode = LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
                previousReason = LimitBlockReason.COOLDOWN,
                cooldownEndsAtMillis = 60_000L,
                nowMillis = 60_000L,
                reachedKinds = setOf(QuotaKind.APP_PER_LAUNCH),
            ),
        )
        assertTrue(
            RestPagePolicy.shouldStartNewPerLaunchCycle(
                mode = LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
                previousReason = LimitBlockReason.COOLDOWN,
                cooldownEndsAtMillis = 60_000L,
                nowMillis = 61_000L,
                reachedKinds = setOf(QuotaKind.GROUP_PER_LAUNCH),
            ),
        )
    }

    @Test
    fun dailyHardLimitAndForceExitNeverStartNewRestCycle() {
        assertFalse(
            RestPagePolicy.shouldStartNewPerLaunchCycle(
                mode = LimitEnforcementMode.EXTERNAL_BREAK_PAGE,
                previousReason = LimitBlockReason.COOLDOWN,
                cooldownEndsAtMillis = 60_000L,
                nowMillis = 60_000L,
                reachedKinds = setOf(QuotaKind.APP_DAILY),
            ),
        )
        assertFalse(
            RestPagePolicy.shouldStartNewPerLaunchCycle(
                mode = LimitEnforcementMode.FORCE_EXIT,
                previousReason = LimitBlockReason.COOLDOWN,
                cooldownEndsAtMillis = 60_000L,
                nowMillis = 60_000L,
                reachedKinds = setOf(QuotaKind.APP_PER_LAUNCH),
            ),
        )
    }

    @Test
    fun quotaPageUsesOnlyClaimedActiveCooldown() {
        val active = RestPagePolicy.quotaState(
            claimedCooldownEndsAtMillis = 70_000L,
            nowMillis = 10_000L,
        )
        val expired = RestPagePolicy.quotaState(
            claimedCooldownEndsAtMillis = 70_000L,
            nowMillis = 70_000L,
        )
        val missing = RestPagePolicy.quotaState(
            claimedCooldownEndsAtMillis = 0L,
            nowMillis = 70_000L,
        )

        assertEquals(LimitBlockReason.COOLDOWN, active.reason)
        assertEquals(70_000L, active.cooldownEndsAtMillis)
        assertEquals(LimitBlockReason.QUOTA, expired.reason)
        assertEquals(0L, expired.cooldownEndsAtMillis)
        assertEquals(LimitBlockReason.QUOTA, missing.reason)
    }
}
