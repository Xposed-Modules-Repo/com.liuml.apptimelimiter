package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootSessionPolicyTest {
    @Test
    fun `mode generation mismatch creates a new session`() {
        val old = NonRootSessionPolicy.newSession("com.example.app", 3L)
        val resumed = NonRootSessionPolicy.foreground(
            state = old,
            packageName = old.packageName,
            nowElapsedMillis = 1_000L,
            protectionModeGeneration = 4L,
        )
        assertEquals(4L, resumed.protectionModeGeneration)
        org.junit.Assert.assertNotEquals(old.sessionId, resumed.sessionId)
    }

    @Test
    fun `duplicate foreground event keeps current session and launch state`() {
        val first = NonRootSessionPolicy.foreground(
            state = null,
            packageName = "com.example.target",
            nowElapsedMillis = 1_000L,
        ).copy(launchRecorded = true)

        val duplicate = NonRootSessionPolicy.foreground(
            state = first,
            packageName = "com.example.target",
            nowElapsedMillis = 1_500L,
        )

        assertEquals(first, duplicate)
        assertTrue(duplicate.launchRecorded)
    }

    @Test
    fun `foreground segment keeps its original day token until paused`() {
        val state = NonRootSessionPolicy.foreground(
            state = null,
            packageName = "pkg",
            nowElapsedMillis = 1_000L,
            dayToken = "2026-07-25",
        )

        val duplicateAfterMidnight = NonRootSessionPolicy.foreground(
            state = state,
            packageName = "pkg",
            nowElapsedMillis = 2_000L,
            dayToken = "2026-07-26",
        )
        val paused = NonRootSessionPolicy.background(duplicateAfterMidnight, 3_000L)

        assertEquals("2026-07-25", duplicateAfterMidnight.foregroundDayToken)
        assertEquals("", paused.foregroundDayToken)
    }

    @Test
    fun returnsWithinThirtySecondsResumeSameSession() {
        val first = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(first, 6_000L)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 20_000L)
        assertEquals(first.sessionId, resumed.sessionId)
        assertEquals(5_000L, resumed.accumulatedForegroundMillis)
    }

    @Test
    fun returnsAfterGraceCreatesNewSession() {
        val first = NonRootSessionPolicy.foreground(null, "pkg", 1_000L)
        val paused = NonRootSessionPolicy.background(first, 2_000L)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 32_001L)
        assertNotEquals(first.sessionId, resumed.sessionId)
        assertEquals(0L, resumed.accumulatedForegroundMillis)
    }

    @Test
    fun planPausesWhileBackgrounded() {
        val foreground = NonRootSessionPolicy.withPlan(
            NonRootSessionPolicy.foreground(null, "pkg", 1_000L),
            10_000L,
        )
        val paused = NonRootSessionPolicy.background(foreground, 4_000L)
        assertEquals(7_000L, paused.planRemainingMillis)
        val resumed = NonRootSessionPolicy.foreground(paused, "pkg", 20_000L)
        assertEquals(7_000L, NonRootSessionPolicy.planRemainingMillis(resumed, 20_000L))
        assertTrue(resumed.planPromptHandled)
    }

    @Test
    fun `process loss recovery does not charge an unobserved foreground gap`() {
        val active = NonRootSessionPolicy.withPlan(
            NonRootSessionPolicy.foreground(null, "pkg", 1_000L),
            20_000L,
        ).copy(accumulatedForegroundMillis = 7_000L)

        val recovered = NonRootSessionPolicy.recoverAfterProcessLoss(active, 101_000L)

        assertEquals(7_000L, recovered.accumulatedForegroundMillis)
        assertEquals(20_000L, recovered.planRemainingMillis)
        assertEquals(0L, recovered.foregroundStartedAtElapsedMillis)
        assertEquals(101_000L, recovered.backgroundedAtElapsedMillis)
        assertEquals(131_000L, recovered.graceEndsAtElapsedMillis)
    }

    @Test
    fun `active segment is split at the current day boundary`() {
        val active = NonRootSessionPolicy.foreground(
            state = null,
            packageName = "pkg",
            nowElapsedMillis = 1_000L,
            dayToken = "2026-07-30",
        )

        val split = NonRootSessionPolicy.splitActiveSegmentAtDayBoundary(
            state = active,
            nowElapsedMillis = 11_000L,
            elapsedSinceCurrentDayStartMillis = 3_000L,
        )

        assertEquals(7_000L, split.previousDayMillis)
        assertEquals(3_000L, split.currentDayMillis)
    }
}
