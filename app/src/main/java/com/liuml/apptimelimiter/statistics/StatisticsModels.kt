package com.liuml.apptimelimiter.statistics

import java.time.LocalDate

data class StatisticsDay(val date: LocalDate)

data class StatisticsSnapshot(
    val date: LocalDate,
    val summaries: List<AppUsageSummary>,
    val totalDurationMillis: Long = summaries.sumOf { it.durationMillis.coerceAtLeast(0L) },
)

data class StatisticsChartSegment(
    val packageName: String?,
    val durationMillis: Long,
    val fraction: Float,
)

data class StatisticsChartIconPlacement(
    val packageName: String,
    val angleDegrees: Float,
    val layer: Int,
)

data class WeeklyReportDay(
    val date: LocalDate,
    val summaries: List<AppUsageSummary>,
    val totalDurationMillis: Long,
) {
    val launchCount: Int get() = summaries.sumOf { it.launchCount.coerceAtLeast(0) }
    val limitHitCount: Int get() = summaries.sumOf { it.limitHitCount.coerceAtLeast(0) }
    val reminderCount: Int get() = summaries.sumOf { it.reminderCount.coerceAtLeast(0) }
    val parentUnlockCount: Int get() = summaries.sumOf { it.parentUnlockCount.coerceAtLeast(0) }
}

data class WeeklyReport(
    val weekStart: LocalDate,
    val days: List<WeeklyReportDay>,
    val previousWeekTotalDurationMillis: Long = 0L,
) {
    val weekEnd: LocalDate get() = weekStart.plusDays(6L)
    val summaries: List<AppUsageSummary> get() = days.flatMap { it.summaries }
    val totalDurationMillis: Long get() = days.sumOf { it.totalDurationMillis.coerceAtLeast(0L) }
    val launchCount: Int get() = days.sumOf { it.launchCount }
    val limitHitCount: Int get() = days.sumOf { it.limitHitCount }
    val reminderCount: Int get() = days.sumOf { it.reminderCount }
    val parentUnlockCount: Int get() = days.sumOf { it.parentUnlockCount }
}

data class WeeklyReportMetric(val packageName: String, val value: Long)

data class WeeklyReportInsight(
    val key: String,
    val zh: String,
    val en: String,
)

object WeeklyReportPolicy {
    fun weekStart(date: LocalDate): LocalDate = date.minusDays(date.dayOfWeek.value - 1L)

    fun top(
        days: List<WeeklyReportDay>,
        selector: (WeeklyReportDay, AppUsageSummary) -> Long,
    ): List<WeeklyReportMetric> = days.flatMap { day -> day.summaries.map { day to it } }
        .groupBy { it.second.packageName }
        .map { (packageName, entries) ->
            WeeklyReportMetric(packageName, entries.sumOf { selector(it.first, it.second).coerceAtLeast(0L) })
        }
        .sortedByDescending { it.value }
        .take(3)
}

/** Short, factual suggestions derived only from the weekly aggregate on screen. */
object WeeklyReportInsightPolicy {
    fun insights(report: WeeklyReport, appLabels: Map<String, String>): List<WeeklyReportInsight> {
        if (report.totalDurationMillis <= 0L) return emptyList()
        val usageByPackage = report.summaries
            .groupBy(AppUsageSummary::packageName)
            .mapValues { (_, values) -> values.sumOf { it.durationMillis.coerceAtLeast(0L) } }
        val top = usageByPackage.maxByOrNull { it.value } ?: return emptyList()
        val result = mutableListOf<WeeklyReportInsight>()
        val label = appLabels[top.key] ?: top.key
        val share = (top.value.toDouble() / report.totalDurationMillis.toDouble() * 100).toInt().coerceIn(0, 100)
        if (share >= 40) {
            result += WeeklyReportInsight(
                key = "top_usage",
                zh = "${label} 占本周使用时间 ${share}%，可优先为它设置更明确的每日或单次额度。",
                en = "${label} accounts for ${share}% of this week's use; consider a clearer daily or per-session limit.",
            )
        }
        val delta = report.totalDurationMillis - report.previousWeekTotalDurationMillis
        if (report.previousWeekTotalDurationMillis > 0L && delta > 0L) {
            result += WeeklyReportInsight(
                key = "week_increase",
                zh = "本周比上周多使用 ${duration(delta)}，可关注使用增长的日期。",
                en = "Usage increased by ${duration(delta)} from last week; review the days with the largest increase.",
            )
        }
        report.days.maxByOrNull { it.totalDurationMillis }
            ?.takeIf { it.totalDurationMillis > 0L }
            ?.let { day ->
                result += WeeklyReportInsight(
                    key = "highest_day",
                    zh = "${day.date.monthValue} 月 ${day.date.dayOfMonth} 日使用最多，为 ${duration(day.totalDurationMillis)}。",
                    en = "${day.date.monthValue}/${day.date.dayOfMonth} had the highest use at ${duration(day.totalDurationMillis)}.",
                )
            }
        return result.take(3)
    }

    private fun duration(millis: Long): String {
        val minutes = millis.coerceAtLeast(0L) / 60_000L
        return if (minutes >= 60L) "${minutes / 60L}h ${minutes % 60L}m" else "${minutes}m"
    }
}

object StatisticsChartPolicy {
    fun segments(summaries: List<AppUsageSummary>, maxItems: Int = 6): List<StatisticsChartSegment> {
        val positive = summaries.filter { it.durationMillis > 0L }
            .sortedByDescending(AppUsageSummary::durationMillis)
        val total = positive.sumOf(AppUsageSummary::durationMillis).coerceAtLeast(1L)
        if (positive.isEmpty()) return emptyList()
        val visible = positive.take(maxItems.coerceAtLeast(1))
        val remainder = positive.drop(visible.size).sumOf(AppUsageSummary::durationMillis)
        return buildList {
            visible.forEach { summary ->
                add(StatisticsChartSegment(summary.packageName, summary.durationMillis, summary.durationMillis.toFloat() / total))
            }
            if (remainder > 0L) add(StatisticsChartSegment(null, remainder, remainder.toFloat() / total))
        }
    }

    /** Places icons at the midpoint of their actual chart segment on one shared outer circle. */
    fun iconPlacements(
        segments: List<StatisticsChartSegment>,
        maxItems: Int = 6,
        minimumAngularSeparationDegrees: Float = 18f,
        minimumFraction: Float = 0.05f,
    ): List<StatisticsChartIconPlacement> {
        val placements = mutableListOf<StatisticsChartIconPlacement>()
        var angle = -90f
        segments.forEach { segment ->
            val sweep = segment.fraction.coerceAtLeast(0f) * 360f
            val packageName = segment.packageName
            if (
                packageName != null &&
                segment.fraction >= minimumFraction.coerceAtLeast(0f) &&
                placements.size < maxItems.coerceAtLeast(1)
            ) {
                val midpoint = angle + sweep / 2f
                // All icons share one radius. If two midpoints are too close, omit the later
                // icon instead of moving it to another ring and breaking visual alignment.
                if (placements.all {
                        angularDistance(it.angleDegrees, midpoint) >= minimumAngularSeparationDegrees
                    }
                ) {
                    placements += StatisticsChartIconPlacement(packageName, midpoint, layer = 0)
                }
            }
            angle += sweep
        }
        return placements
    }

    private fun angularDistance(first: Float, second: Float): Float {
        val distance = kotlin.math.abs((first - second) % 360f)
        return minOf(distance, 360f - distance)
    }
}

object StatisticsDateRangePolicy {
    const val MAX_HISTORY_DAYS = 31L

    fun canSelect(date: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        !date.isAfter(today) && !date.isBefore(today.minusDays(MAX_HISTORY_DAYS - 1L))
}
