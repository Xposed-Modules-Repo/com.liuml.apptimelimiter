package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRulePolicyTest {
    @Test
    fun groupPerLaunchMakesTimedQuotaActive() {
        assertTrue(GroupRulePolicy.hasTimedQuota(false, false, false, true))
    }

    @Test
    fun noEnabledThresholdHasNoTimedQuota() {
        assertFalse(GroupRulePolicy.hasTimedQuota(false, false, false, false))
    }

    @Test
    fun longerEnabledCooldownWins() {
        assertEquals(
            10 * 60_000L,
            GroupRulePolicy.effectiveCooldownMillis(
                appEnabled = true,
                appDurationMillis = 2 * 60_000L,
                groupEnabled = true,
                groupDurationMillis = 10 * 60_000L,
            ),
        )
        assertEquals(
            2 * 60_000L,
            GroupRulePolicy.effectiveCooldownMillis(
                appEnabled = true,
                appDurationMillis = 2 * 60_000L,
                groupEnabled = false,
                groupDurationMillis = 10 * 60_000L,
            ),
        )
    }
}
