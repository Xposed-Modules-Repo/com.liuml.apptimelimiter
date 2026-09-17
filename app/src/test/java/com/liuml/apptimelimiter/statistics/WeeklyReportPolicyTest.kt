package com.liuml.apptimelimiter.statistics

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReportPolicyTest {
    @Test fun emptyMetricsHaveNoFakeRankingAndTiesAreStable() {
        val day = WeeklyReportDay(LocalDate.of(2026, 9, 7), listOf(
            AppUsageSummary("b", 10L, 0, 0, 0L),
            AppUsageSummary("a", 10L, 0, 0, 0L),
        ), 20L)
        assertTrue(WeeklyReportPolicy.top(listOf(day)) { _, app -> app.launchCount.toLong() }.isEmpty())
        assertEquals(listOf("a", "b"), WeeklyReportPolicy.top(listOf(day)) { _, app -> app.durationMillis }.map { it.packageName })
    }

    @Test fun corruptedLargeCountersNeverOverflowToNegative() {
        val day = WeeklyReportDay(LocalDate.of(2026, 9, 7), listOf(
            AppUsageSummary("a", Long.MAX_VALUE, Int.MAX_VALUE, 0, 0L),
            AppUsageSummary("b", Long.MAX_VALUE, Int.MAX_VALUE, 0, 0L),
        ), Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, day.launchCount)
        assertEquals(Int.MAX_VALUE, WeeklyReport(day.date, listOf(day, day)).launchCount)
        assertEquals(172_800_000L, WeeklyReport(day.date, listOf(day, day)).totalDurationMillis)
        assertEquals(Long.MAX_VALUE, WeeklyReportPolicy.top(listOf(day, day)) { _, app -> app.durationMillis }.first().value)
    }

    @Test
    fun insightsUseWeeklyAggregateWithoutInventingUsage() {
        val report = WeeklyReport(
            weekStart = LocalDate.of(2026, 9, 7),
            previousWeekTotalDurationMillis = 60L * 60L * 1000L,
            days = listOf(
                WeeklyReportDay(
                    LocalDate.of(2026, 9, 7),
                    listOf(AppUsageSummary("video", 2L * 60L * 60L * 1000L, 0, 0, 0L)),
                    2L * 60L * 60L * 1000L,
                ),
            ),
        )

        val insights = WeeklyReportInsightPolicy.insights(report, mapOf("video" to "Video"))

        assertTrue(insights.any { it.key == "top_usage" && it.zh.contains("Video") })
        assertTrue(insights.any { it.key == "week_increase" })
        assertTrue(insights.any { it.key == "highest_day" })
    }
    @Test
    fun weekStartsOnMondayAcrossYearBoundary() {
        assertEquals(LocalDate.of(2026, 1, 26), WeeklyReportPolicy.weekStart(LocalDate.of(2026, 2, 1)))
        assertEquals(DayOfWeek.MONDAY, WeeklyReportPolicy.weekStart(LocalDate.of(2026, 2, 1)).dayOfWeek)
    }

    @Test
    fun topThreeAggregatesByPackageAndKeepsLongDurations() {
        fun summary(packageName: String, duration: Long, launches: Int = 0) =
            AppUsageSummary(packageName, duration, launches, 0, 0L)
        val days = listOf(
            WeeklyReportDay(LocalDate.of(2026, 9, 7), listOf(
                summary("a", 90L * 60L * 1000L),
                summary("b", 30L * 60L * 1000L),
            ), 120L * 60L * 1000L),
            WeeklyReportDay(LocalDate.of(2026, 9, 8), listOf(
                summary("a", 90L * 60L * 1000L),
                summary("c", 60L * 60L * 1000L),
                summary("d", 1L),
            ), 150L * 60L * 1000L),
        )

        assertEquals(
            listOf(
                WeeklyReportMetric("a", 180L * 60L * 1000L),
                WeeklyReportMetric("c", 60L * 60L * 1000L),
                WeeklyReportMetric("b", 30L * 60L * 1000L),
            ),
            WeeklyReportPolicy.top(days) { _, value -> value.durationMillis },
        )
    }

    @Test
    fun futureDaysRemainEmptyInCurrentWeek() {
        val report = WeeklyReport(
            weekStart = LocalDate.of(2026, 9, 7),
            days = (0L..6L).map { offset ->
                WeeklyReportDay(LocalDate.of(2026, 9, 7).plusDays(offset), emptyList(), 0L)
            },
        )
        assertEquals(7, report.days.size)
        assertEquals(0L, report.days.sumOf { it.totalDurationMillis })
    }

    @Test
    fun eventMetricsAreAggregatedPerDay() {
        val summary = AppUsageSummary(
            packageName = "app",
            durationMillis = 1L,
            launchCount = 2,
            limitHitCount = 3,
            lastUsedAtMillis = 0L,
            reminderCount = 4,
            parentUnlockCount = 5,
        )
        val day = WeeklyReportDay(LocalDate.of(2026, 9, 7), listOf(summary), 1L)
        assertEquals(3, day.limitHitCount)
        assertEquals(4, day.reminderCount)
        assertEquals(5, day.parentUnlockCount)
    }
}
