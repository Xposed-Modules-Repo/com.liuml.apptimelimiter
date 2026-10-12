package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.nonroot.NonRootSessionPolicy
import org.junit.Assert.*
import org.junit.Test

class SessionRestPolicyTest {
    @Test fun `overnight stale restriction resets cycle but hard gates remain`() {
        val reset = PerLaunchRestPolicy.evaluate(true, 300_000L,
            1_000L, 28_801_000L, true, 0L)
        assertTrue(reset.shouldStartNewCycle)
        assertNull(LimitEnforcementPolicy.evaluate(LimitGateSnapshot(false, 0L, false)).blockingReason)
        assertEquals(LimitBlockReason.QUOTA,
            LimitEnforcementPolicy.evaluate(LimitGateSnapshot(false, 0L, true)).blockingReason)
        assertEquals(LimitBlockReason.SCHEDULE,
            LimitEnforcementPolicy.evaluate(LimitGateSnapshot(true, 0L, false)).blockingReason)
    }

    @Test fun `both modes agree at the configured rest boundary`() {
        for (enabled in listOf(true, false)) {
            val interval = SessionRestPolicy.intervalMillis(enabled, 300_000L)
            val active = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
            val paused = NonRootSessionPolicy.background(active, 901_000L, interval)
            for (gap in listOf(interval - 1L, interval)) {
                val hook = PerLaunchRestPolicy.evaluate(true, if (enabled) interval else 0L,
                    901_000L, 901_000L + gap, true, 0L)
                val ordinary = NonRootSessionPolicy.foreground(paused, "pkg", 901_000L + gap,
                    restIntervalMillis = interval)
                assertEquals(gap == interval, hook.shouldStartNewCycle)
                assertEquals(hook.shouldStartNewCycle, ordinary.sessionId != paused.sessionId)
                assertEquals(if (gap == interval) 0L else 900_000L, ordinary.accumulatedForegroundMillis)
            }
        }
    }

    @Test fun `current interval overrides old persisted thirty second grace`() {
        val active = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(active, 2_000L)
        val restored = NonRootSessionPolicy.foreground(paused, "pkg", 301_999L,
            restIntervalMillis = 300_000L)
        assertEquals(paused.sessionId, restored.sessionId)
    }

    @Test fun `new cycle drops plan but duplicate pause keeps original rest start`() {
        val active = NonRootSessionPolicy.withPlan(
            NonRootSessionPolicy.foreground(null, "pkg", 1_000L), 600_000L)
        val paused = NonRootSessionPolicy.background(active, 2_000L)
        assertEquals(paused, NonRootSessionPolicy.background(paused, 20_000L))
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 32_000L)
        assertFalse(resumed.planPromptHandled)
        assertFalse(resumed.planActive)
    }

    @Test fun `active authorization preserves cycle`() {
        val active = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(active, 2_000L)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 4_000_000L,
            preserveExistingSession = true, restIntervalMillis = 300_000L)
        assertEquals(paused.sessionId, resumed.sessionId)
    }

    @Test fun `group rest boundary resets and old segments cannot enter new cycle`() {
        val old = SharedGroupSessionRecord(groupVersion = 1L, bootCount = 1,
            sessionId = "old", usedMillis = 900_000L, inactiveSinceElapsedMillis = 1_000L)
        fun enter(now: Long) = SharedGroupSessionPolicy.update(old, SharedGroupSessionAction.ENTER,
            1L, 1, "b", "old", "new", "", 0L, now, 300_000L)
        assertEquals("old", enter(300_999L).record.sessionId)
        val fresh = enter(301_000L)
        assertEquals(0L, fresh.record.usedMillis)
        val late = SharedGroupSessionPolicy.update(fresh.record, SharedGroupSessionAction.COMMIT,
            1L, 1, "a", "old", "unused", "old-segment", 900_000L, 302_000L, 300_000L)
        assertTrue(late.staleRequest)
        assertEquals(0L, late.record.usedMillis)
    }
}
