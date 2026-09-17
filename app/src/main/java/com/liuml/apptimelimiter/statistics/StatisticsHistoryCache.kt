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
    private data class Entry(val report: WeeklyReport, val cachedAtMillis: Long)

    private val values = LinkedHashMap<String, Entry>()

    @Synchronized
    fun get(key: String, nowMillis: Long, maxAgeMillis: Long): WeeklyReport? {
        val entry = values[key] ?: return null
        val age = nowMillis - entry.cachedAtMillis
        if (age < 0L || age > maxAgeMillis.coerceAtLeast(0L)) {
            values.remove(key)
            return null
        }
        return entry.report
    }

    @Synchronized
    fun put(key: String, report: WeeklyReport, nowMillis: Long) {
        values.remove(key)
        values[key] = Entry(report, nowMillis)
        while (values.size > maxEntries.coerceAtLeast(1)) values.remove(values.keys.first())
    }
}
