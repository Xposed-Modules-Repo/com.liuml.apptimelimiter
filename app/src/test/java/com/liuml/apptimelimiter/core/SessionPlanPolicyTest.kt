package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanPolicyTest {
    @Test
    fun `foreground segment reduces remaining time`() {
        assertEquals(
            45_000L,
            SessionPlanPolicy.remainingMillis(60_000L, 10_000L, 25_000L),
        )
    }

    @Test
    fun `background time does not reduce committed remaining time`() {
        assertEquals(
            45_000L,
            SessionPlanPolicy.remainingMillis(45_000L, null, 5_000_000L),
        )
    }

    @Test
    fun `replanning uses the newly selected duration`() {
        assertEquals(
            10L * 60_000L,
            SessionPlanPolicy.remainingMillis(10L * 60_000L, 20_000L, 20_000L),
        )
    }

    @Test
    fun `skipping produces no active duration`() {
        assertEquals(null, SessionPlanPolicy.selectedDurationMillis(null))
    }

    @Test
    fun `release choices are bounded while debug choice may use ten seconds`() {
        assertEquals(
            SessionPlanPolicy.MIN_DURATION_MILLIS,
            SessionPlanPolicy.selectedDurationMillis(10_000L),
        )
        assertEquals(10_000L, SessionPlanPolicy.selectedDurationMillis(10_000L, true))
        assertEquals(
            SessionPlanPolicy.MAX_DURATION_MILLIS,
            SessionPlanPolicy.selectedDurationMillis(Long.MAX_VALUE),
        )
    }

    @Test
    fun `earliest timed condition owns warning`() {
        assertTrue(SessionPlanPolicy.isEarliest(5_000L, 8_000L))
        assertFalse(SessionPlanPolicy.isEarliest(8_000L, 5_000L))
        assertFalse(SessionPlanPolicy.isEarliest(5_000L, 5_000L))
        assertTrue(SessionPlanPolicy.isEarliest(5_000L, null))
    }

    @Test
    fun `plan within remaining quota is allowed`() {
        assertTrue(
            SessionPlanPolicy.fitsWithinQuota(
                requestedDurationMillis = 10L * 60_000L,
                earliestQuotaRemainingMillis = 10L * 60_000L,
            ),
        )
    }

    @Test
    fun `plan beyond remaining quota is rejected`() {
        assertFalse(
            SessionPlanPolicy.fitsWithinQuota(
                requestedDurationMillis = 15L * 60_000L,
                earliestQuotaRemainingMillis = 8L * 60_000L,
            ),
        )
    }

    @Test
    fun `plan without timed quota is allowed`() {
        assertTrue(
            SessionPlanPolicy.fitsWithinQuota(
                requestedDurationMillis = 30L * 60_000L,
                earliestQuotaRemainingMillis = null,
            ),
        )
    }

    @Test
    fun `exhausted quota cannot offer a plan`() {
        assertFalse(SessionPlanPolicy.canOfferPlan(0L))
        assertFalse(SessionPlanPolicy.canOfferPlan(-1L))
        assertTrue(SessionPlanPolicy.canOfferPlan(1L))
        assertTrue(SessionPlanPolicy.canOfferPlan(null))
    }

    @Test
    fun `plan expiry has no cooldown or limit hit side effects`() {
        assertFalse(SessionPlanPolicy.EXPIRY_EFFECTS.startsCooldown)
        assertEquals(0, SessionPlanPolicy.EXPIRY_EFFECTS.limitHitIncrement)
    }
}
