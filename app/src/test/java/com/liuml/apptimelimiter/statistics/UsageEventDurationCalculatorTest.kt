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
    fun `screen off clears foreground until a new resume event arrives`() {
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

        assertEquals(1_500L, result["video.app"])
    }

    @Test
    fun `screen off clears stale foreground packages instead of reviving all on wake`() {
        val result = UsageEventDurationCalculator.calculate(
            packageNames = listOf("first.app", "second.app"),
            startMillis = 0L,
            endMillis = 100_000L,
            transitions = listOf(
                UsageTransition("first.app", 10_000L, true),
                // Simulate a vendor ROM omitting first.app's pause event.
                UsageTransition("second.app", 20_000L, true),
                ScreenInteractiveTransition(30_000L, false),
                ScreenInteractiveTransition(50_000L, true),
                UsageTransition("second.app", 55_000L, true),
                UsageTransition("second.app", 80_000L, false),
            ),
        )

        assertEquals(20_000L, result["first.app"])
        assertEquals(35_000L, result["second.app"])
    }

    @Test
    fun `total duration uses the union of overlapping app intervals`() {
        val result = UsageEventDurationCalculator.calculateSnapshot(
            packageNames = listOf("first.app", "second.app"),
            startMillis = 0L,
            endMillis = 10_000L,
            transitions = listOf(
                UsageTransition("first.app", 1_000L, true),
                UsageTransition("second.app", 2_000L, true),
                UsageTransition("second.app", 4_000L, false),
                UsageTransition("first.app", 5_000L, false),
            ),
        )

        assertEquals(4_000L, result.summaries["first.app"]?.durationMillis)
        assertEquals(2_000L, result.summaries["second.app"]?.durationMillis)
        assertEquals(4_000L, result.totalDurationMillis)
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
