package com.liuml.apptimelimiter.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsChartPolicyTest {
    @Test
    fun `keeps six largest apps and aggregates the remainder`() {
        val summaries = (1..8).map { index ->
            AppUsageSummary("app$index", (index * 1_000L), 1, 0, 0L)
        }

        val segments = StatisticsChartPolicy.segments(summaries)

        assertEquals(7, segments.size)
        assertEquals("app8", segments.first().packageName)
        assertEquals(null, segments.last().packageName)
        assertEquals(36_000L, segments.sumOf(StatisticsChartSegment::durationMillis))
        assertEquals(1f, segments.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `empty or zero duration summaries produce no misleading chart`() {
        val segments = StatisticsChartPolicy.segments(
            listOf(AppUsageSummary("zero", 0L, 1, 0, 0L)),
        )

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `icon uses segment midpoint and excludes other`() {
        val segments = listOf(
            StatisticsChartSegment("first", 50, 0.5f),
            StatisticsChartSegment("second", 30, 0.3f),
            StatisticsChartSegment(null, 20, 0.2f),
        )

        val placements = StatisticsChartPolicy.iconPlacements(segments)

        assertEquals(2, placements.size)
        assertEquals(0f, placements[0].angleDegrees, 0.001f)
        assertEquals(144f, placements[1].angleDegrees, 0.001f)
    }

    @Test
    fun `nearby icons are omitted instead of moving to another radial lane`() {
        val placements = StatisticsChartPolicy.iconPlacements(
            listOf(
                StatisticsChartSegment("a", 99, 0.05f),
                StatisticsChartSegment("b", 99, 0.05f),
            ),
            minimumAngularSeparationDegrees = 30f,
        )

        assertEquals(1, placements.size)
        assertEquals(0, placements.single().layer)
    }

    @Test
    fun `tiny segments do not receive icons`() {
        val placements = StatisticsChartPolicy.iconPlacements(
            listOf(
                StatisticsChartSegment("large", 96, 0.96f),
                StatisticsChartSegment("tiny", 4, 0.049f),
            ),
        )

        assertEquals(1, placements.size)
        assertEquals("large", placements.single().packageName)
    }
}
