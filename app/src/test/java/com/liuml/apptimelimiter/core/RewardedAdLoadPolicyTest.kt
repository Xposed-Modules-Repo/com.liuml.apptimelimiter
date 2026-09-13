package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdLoadPolicyTest {
    @Test
    fun `waits before the bounded load timeout`() {
        assertTrue(RewardedAdLoadPolicy.shouldKeepWaiting(10_000L, 25_999L))
    }

    @Test
    fun `stops waiting at the load timeout`() {
        assertFalse(RewardedAdLoadPolicy.shouldKeepWaiting(10_000L, 26_000L))
    }

    @Test
    fun `rejects retry until failure backoff has elapsed`() {
        assertFalse(RewardedAdLoadPolicy.isRetryAllowed(10_000L, 12_499L))
        assertTrue(RewardedAdLoadPolicy.isRetryAllowed(10_000L, 12_500L))
    }

    @Test
    fun `allows the first load before any failure`() {
        assertTrue(RewardedAdLoadPolicy.isRetryAllowed(Long.MIN_VALUE, 10_000L))
    }
}
