package com.liuml.apptimelimiter.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsDisplayPolicyTest {
    @Test
    fun `system apps are filtered consistently`() {
        val summaries = listOf(
            AppUsageSummary("user", 20L, 1, 0, 0L),
            AppUsageSummary("system", 50L, 1, 2, 0L),
        )

        val visible = StatisticsDisplayPolicy.filter(summaries, setOf("system"), false)

        assertEquals(listOf("user"), visible.map(AppUsageSummary::packageName))
    }

    @Test
    fun `blank packages are never displayed`() {
        val visible = StatisticsDisplayPolicy.filter(
            listOf(AppUsageSummary("", 100L, 1, 1, 0L)),
            emptySet(),
            true,
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `ratio is bounded and safe for empty totals`() {
        assertEquals(0f, StatisticsDisplayPolicy.ratio(10L, 0L), 0.001f)
        assertEquals(1f, StatisticsDisplayPolicy.ratio(20L, 10L), 0.001f)
    }
}
