package com.liuml.apptimelimiter.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventDurationCalculatorTest {
    @Test
    fun `usage crossing midnight only counts time after midnight`() {
        val result = UsageEventDurationCalculator.calculateSummaries(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 5_000L,
            transitions = listOf(
                UsageTransition("video.app", 500L, true),
                UsageTransition("video.app", 2_500L, false),
            ),
        )

        assertEquals(1_500L, result["video.app"]?.durationMillis)
        assertEquals(1, result["video.app"]?.launchCount)
    }

    @Test
    fun `foreground session still active is counted through query time`() {
        val result = UsageEventDurationCalculator.calculate(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 5_000L,
            transitions = listOf(UsageTransition("video.app", 2_000L, true)),
        )

        assertEquals(3_000L, result["video.app"])
    }

    @Test
    fun `duplicate foreground events do not double count`() {
        val result = UsageEventDurationCalculator.calculateSummaries(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 5_000L,
            transitions = listOf(
                UsageTransition("video.app", 1_500L, true),
                UsageTransition("video.app", 2_000L, true),
                UsageTransition("video.app", 3_000L, false),
            ),
        )

        assertEquals(1_500L, result["video.app"]?.durationMillis)
        assertEquals(1, result["video.app"]?.launchCount)
    }

    @Test
    fun `untracked packages are ignored`() {
        val result = UsageEventDurationCalculator.calculate(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 5_000L,
            transitions = listOf(
                UsageTransition("other.app", 1_500L, true),
                UsageTransition("other.app", 3_000L, false),
            ),
        )

        assertEquals(0L, result["video.app"])
    }

    @Test
    fun `screen off interval is excluded while app remains foreground`() {
        val result = UsageEventDurationCalculator.calculate(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 7_000L,
            transitions = listOf(
                UsageTransition("video.app", 1_500L, true),
                ScreenInteractiveTransition(3_000L, false),
                ScreenInteractiveTransition(5_000L, true),
                UsageTransition("video.app", 6_000L, false),
            ),
        )

        assertEquals(2_500L, result["video.app"])
    }

    @Test
    fun `separate foreground sessions count as separate launches`() {
        val result = UsageEventDurationCalculator.calculateSummaries(
            packageNames = listOf("video.app"),
            startMillis = 1_000L,
            endMillis = 8_000L,
            transitions = listOf(
                UsageTransition("video.app", 1_500L, true),
                UsageTransition("video.app", 3_000L, false),
                UsageTransition("video.app", 5_000L, true),
                UsageTransition("video.app", 7_000L, false),
            ),
        )

        assertEquals(3_500L, result["video.app"]?.durationMillis)
        assertEquals(2, result["video.app"]?.launchCount)
        assertEquals(5_000L, result["video.app"]?.lastUsedAtMillis)
    }
}
