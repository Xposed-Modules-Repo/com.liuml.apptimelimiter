package com.liuml.apptimelimiter.nonroot

import com.liuml.apptimelimiter.core.QuotaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootRuleEvaluatorTest {
    private fun snapshot() = NonRootRuleSnapshot(
        scheduleBlocked = false,
        cooldownRemainingMillis = 0L,
        appDailyEnabled = true,
        appDailyUsedMillis = 2_000L,
        appDailyLimitMillis = 10_000L,
        appPerSessionEnabled = true,
        appPerSessionLimitMillis = 6_000L,
        groupDailyEnabled = false,
        groupDailyUsedMillis = 0L,
        groupDailyLimitMillis = 0L,
        groupPerSessionEnabled = false,
        groupPerSessionLimitMillis = 0L,
        sessionUsedMillis = 1_000L,
        planActive = false,
        planRemainingMillis = 0L,
    )

    @Test
    fun earliestThresholdWins() {
        val decision = NonRootRuleEvaluator.evaluate(snapshot())
        assertNull(decision.blockingReason)
        assertEquals(5_000L, decision.nextThresholdMillis)
        assertFalse(decision.sessionPlanIsNextThreshold)
    }

    @Test
    fun `session plan is marked when it expires before permanent limits`() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                planActive = true,
                planRemainingMillis = 4_000L,
            ),
        )

        assertEquals(4_000L, decision.nextThresholdMillis)
        assertTrue(decision.sessionPlanIsNextThreshold)
    }

    @Test
    fun `permanent limit suppresses plan warning when it expires first`() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                planActive = true,
                planRemainingMillis = 7_000L,
            ),
        )

        assertEquals(5_000L, decision.nextThresholdMillis)
        assertFalse(decision.sessionPlanIsNextThreshold)
    }

    @Test
    fun `permanent limit wins when plan and quota expire together`() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                planActive = true,
                planRemainingMillis = 5_000L,
            ),
        )

        assertEquals(5_000L, decision.nextThresholdMillis)
        assertFalse(decision.sessionPlanIsNextThreshold)
    }

    @Test
    fun schedulePrecedesCooldownAndQuota() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                scheduleBlocked = true,
                cooldownRemainingMillis = 10_000L,
                sessionUsedMillis = 6_000L,
            ),
        )
        assertEquals(NonRootBlockReason.SCHEDULE, decision.blockingReason)
        assertFalse(decision.sessionPlanAllowed)
    }

    @Test
    fun quotaReportsStableKinds() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(sessionUsedMillis = 6_000L),
        )
        assertEquals(NonRootBlockReason.QUOTA, decision.blockingReason)
        assertEquals(setOf(QuotaKind.APP_PER_LAUNCH), decision.reachedKinds)
    }

    @Test
    fun `returning during grace keeps per session restriction active`() {
        val started = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(started, 7_000L)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 20_000L)

        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                sessionUsedMillis = NonRootSessionPolicy.foregroundUsedMillis(resumed, 20_000L),
            ),
        )

        assertEquals(started.sessionId, resumed.sessionId)
        assertEquals(NonRootBlockReason.QUOTA, decision.blockingReason)
    }

    @Test
    fun `returning after grace starts an unrestricted per session cycle`() {
        val started = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(started, 7_000L)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 37_001L)

        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                sessionUsedMillis = NonRootSessionPolicy.foregroundUsedMillis(resumed, 37_001L),
            ),
        )

        assertFalse(started.sessionId == resumed.sessionId)
        assertNull(decision.blockingReason)
    }

    @Test
    fun activePlanExpiresWithoutQuotaIncident() {
        val decision = NonRootRuleEvaluator.evaluate(
            snapshot().copy(
                appDailyEnabled = false,
                appPerSessionEnabled = false,
                planActive = true,
                planRemainingMillis = 0L,
            ),
        )
        assertEquals(NonRootBlockReason.SESSION_PLAN, decision.blockingReason)
        assertEquals(emptySet<QuotaKind>(), decision.reachedKinds)
    }

    @Test
    fun `cooldown and session plan do not add limit hits`() {
        assertFalse(
            NonRootLimitHitPolicy.shouldRecord(
                NonRootBlockReason.COOLDOWN,
                quotaIncidentIsNew = true,
                scheduleIncidentIsNew = true,
            ),
        )
        assertFalse(
            NonRootLimitHitPolicy.shouldRecord(
                NonRootBlockReason.SESSION_PLAN,
                quotaIncidentIsNew = true,
                scheduleIncidentIsNew = true,
            ),
        )
        assertTrue(
            NonRootLimitHitPolicy.shouldRecord(
                NonRootBlockReason.SCHEDULE,
                quotaIncidentIsNew = false,
                scheduleIncidentIsNew = true,
            ),
        )
    }
}
