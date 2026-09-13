package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdPolicyTest {
    @Test
    fun rewardUsesConfiguredExtensionDuration() {
        val decision = RewardedAdPolicy.claim(RewardedAdQuotaState(), "day", 60_000L, Long.MAX_VALUE)
        assertTrue(decision.allowed)
        assertEquals(60_000L, decision.rewardMillis)
    }

    @Test
    fun policyDoesNotApplyASecondProductQuota() {
        val first = RewardedAdPolicy.claim(RewardedAdQuotaState(), "day", 60_000L, Long.MAX_VALUE)
        val second = RewardedAdPolicy.claim(first.nextState, "day", 60_000L, Long.MAX_VALUE)
        val third = RewardedAdPolicy.claim(second.nextState, "day", 60_000L, Long.MAX_VALUE)
        assertTrue(first.allowed)
        assertTrue(second.allowed)
        assertTrue(third.allowed)
    }

    @Test
    fun noRewardWhenTheRuleHasNoRemainingDuration() {
        val decision = RewardedAdPolicy.claim(RewardedAdQuotaState(), "day", 60_000L, 0L)
        assertFalse(decision.allowed)
        assertEquals(0L, decision.rewardMillis)
    }
}
