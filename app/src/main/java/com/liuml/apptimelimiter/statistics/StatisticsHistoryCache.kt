package com.liuml.apptimelimiter.statistics

import java.time.LocalDate

/** Bounded in-memory cache for date navigation; never persists statistics or tokens. */
class StatisticsHistoryCache(private val maxEntries: Int = 8) {
    private val values = LinkedHashMap<LocalDate, StatisticsSnapshot>()

    @Synchronized
    fun get(date: LocalDate): StatisticsSnapshot? = values[date]

    @Synchronized
    fun put(date: LocalDate, snapshot: StatisticsSnapshot) {
        values.remove(date)
        values[date] = snapshot
        while (values.size > maxEntries.coerceAtLeast(1)) values.remove(values.keys.first())
    }

    @Synchronized
    fun clear() = values.clear()
}

/** Bounded memory-only cache for current and previous weekly reports. */
class WeeklyReportCache(private val maxEntries: Int = 4) {
    private val values = LinkedHashMap<String, WeeklyReport>()

    @Synchronized fun get(key: String): WeeklyReport? = values[key]

    @Synchronized fun put(key: String, report: WeeklyReport) {
        values.remove(key)
        values[key] = report
        while (values.size > maxEntries.coerceAtLeast(1)) values.remove(values.keys.first())
    }
}
