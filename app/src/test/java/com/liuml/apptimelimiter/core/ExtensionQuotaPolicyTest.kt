package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionQuotaPolicyTest {
    @Test
    fun `migrates only the former three-per-day default`() {
        assertTrue(ExtensionQuotaPolicy.shouldMigrateLegacyDefaults(3, false, false))
        assertFalse(ExtensionQuotaPolicy.shouldMigrateLegacyDefaults(3, true, false))
        assertFalse(ExtensionQuotaPolicy.shouldMigrateLegacyDefaults(10, false, false))
    }

    @Test
    fun firstThreeDailyExtensionsAreFreeAndFourthRequiresAnAd() {
        val day = "2026-09-13"
        var state = ExtensionQuotaState()
        repeat(3) {
            val claim = ExtensionQuotaPolicy.claimFree(state, day, "session-$it", 10, 3, 3)
            assertTrue(claim.allowed)
            state = claim.nextState
        }
        val fourth = ExtensionQuotaPolicy.claimFree(state, day, "session-3", 10, 3, 3)
        assertFalse(fourth.allowed)
        assertTrue(fourth.requiresAd)
        assertEquals(7, fourth.remainingCount)

        val rewarded = ExtensionQuotaPolicy.claimRewarded(fourth.nextState, day, "session-3", 10, 3, 3)
        assertTrue(rewarded.allowed)
        assertEquals(6, rewarded.remainingCount)
    }

    @Test
    fun `free extension quota remains fixed at three`() {
        assertEquals(3, ExtensionQuotaPolicy.normalizeFreeDailyLimit(0, 10))
        assertEquals(3, ExtensionQuotaPolicy.normalizeFreeDailyLimit(50, 10))
        assertEquals(2, ExtensionQuotaPolicy.normalizeFreeDailyLimit(0, 2))
    }

    @Test
    fun totalDailyQuotaIsSharedAcrossApplications() {
        val day = "2026-09-13"
        val first = ExtensionQuotaPolicy.claimFree(ExtensionQuotaState(), day, "app-a", 2, 3, 2)
        val second = ExtensionQuotaPolicy.claimFree(first.nextState, day, "app-b", 2, 3, 2)
        val exhausted = ExtensionQuotaPolicy.claimRewarded(second.nextState, day, "app-c", 2, 3, 2)
        assertTrue(first.allowed)
        assertTrue(second.allowed)
        assertFalse(exhausted.allowed)
        assertFalse(exhausted.requiresAd)
        assertEquals(0, exhausted.remainingCount)
    }

    @Test
    fun sessionQuotaResetsForNewUsageRoundButNotForSameRound() {
        val day = "2026-09-13"
        val first = ExtensionQuotaPolicy.claimFree(ExtensionQuotaState(), day, "same", 10, 1, 10)
        val blocked = ExtensionQuotaPolicy.claimFree(first.nextState, day, "same", 10, 1, 10)
        val nextRound = ExtensionQuotaPolicy.claimFree(first.nextState, day, "new", 10, 1, 10)
        assertTrue(first.allowed)
        assertFalse(blocked.allowed)
        assertFalse(blocked.requiresAd)
        assertTrue(nextRound.allowed)
    }

    @Test
    fun rewardedExtensionIsNotEligibleAfterTheUsageRoundLimitIsReached() {
        val day = "2026-09-13"
        var state = ExtensionQuotaState()
        repeat(3) {
            val claim = ExtensionQuotaPolicy.claimFree(state, day, "same", 10, 3, 3)
            assertTrue(claim.allowed)
            state = claim.nextState
        }

        val rewarded = ExtensionQuotaPolicy.claimRewarded(state, day, "same", 10, 3, 3)

        assertFalse(rewarded.allowed)
        assertFalse(rewarded.requiresAd)
        assertEquals(7, rewarded.remainingCount)
        assertEquals(0, rewarded.remainingSessionCount)
    }

    @Test
    fun newDayClearsBothDailyAndFreeCounters() {
        val old = ExtensionQuotaState("old", 10, 3, "session", 3)
        val claim = ExtensionQuotaPolicy.claimFree(old, "new", "new-session", 10, 3, 3)
        assertTrue(claim.allowed)
        assertEquals(9, claim.remainingCount)
        assertEquals(2, claim.remainingFreeCount)
    }
}
