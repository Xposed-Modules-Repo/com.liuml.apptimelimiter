package com.liuml.apptimelimiter.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSummaryMergePolicyTest {
    @Test
    fun `zero system launches do not erase a hook launch`() {
        assertEquals(
            1,
            UsageSummaryMergePolicy.authoritativeLaunchCount(
                moduleLaunchCount = 1,
                systemLaunchCount = 0,
            ),
        )
    }

    @Test
    fun `larger system launch count remains authoritative`() {
        assertEquals(
            3,
            UsageSummaryMergePolicy.authoritativeLaunchCount(
                moduleLaunchCount = 1,
                systemLaunchCount = 3,
            ),
        )
    }

    @Test
    fun `system interval union prevents overlapping apps from inflating total`() {
        assertEquals(
            6_000L,
            UsageSummaryMergePolicy.authoritativeTotalDuration(
                appDurationsMillis = listOf(4_000L, 4_000L, 3_000L),
                systemUnionDurationMillis = 6_000L,
                maximumDayDurationMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `fallback total never exceeds elapsed part of current day`() {
        assertEquals(
            8_000L,
            UsageSummaryMergePolicy.authoritativeTotalDuration(
                appDurationsMillis = listOf(7_000L, 6_000L),
                systemUnionDurationMillis = null,
                maximumDayDurationMillis = 8_000L,
            ),
        )
    }

    @Test
    fun `system total remains at least the longest authoritative app duration`() {
        assertEquals(
            7_000L,
            UsageSummaryMergePolicy.authoritativeTotalDuration(
                appDurationsMillis = listOf(7_000L, 2_000L),
                systemUnionDurationMillis = 5_000L,
                maximumDayDurationMillis = 10_000L,
            ),
        )
    }
}
