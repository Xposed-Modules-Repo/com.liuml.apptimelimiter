package com.liuml.apptimelimiter.statistics

import android.content.Context
import java.time.LocalDate

data class AppUsageSummary(
    val packageName: String,
    val durationMillis: Long,
    val launchCount: Int,
    val limitHitCount: Int,
    val lastUsedAtMillis: Long,
    val lastHookEventAtMillis: Long = 0L,
    val hookVersionCode: Int = 0,
)

class UsageStatsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun record(
        packageName: String,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
        hookVersionCode: Int,
        dayToken: String? = null,
    ): Boolean {
        if (packageName.isBlank()) return false
        val day = normalizedUsageDayToken(dayToken) ?: return false
        return synchronized(LOCK) {
            val prefix = "$day.$packageName."
            // ContentProvider calls can cold-start this process for a single short write.
            // Commit synchronously so Android cannot kill the process before apply() flushes it.
            prefs.edit()
                .putLong(
                    "${prefix}duration_ms",
                    safeAdd(
                        prefs.getLong("${prefix}duration_ms", 0L).coerceAtLeast(0L),
                        durationMillis.coerceAtLeast(0L),
                    ),
                )
                .putInt(
                    "${prefix}launches",
                    safeAdd(
                        prefs.getInt("${prefix}launches", 0).coerceAtLeast(0),
                        launchIncrement.coerceAtLeast(0),
                    ),
                )
                .putInt(
                    "${prefix}limit_hits",
                    safeAdd(
                        prefs.getInt("${prefix}limit_hits", 0).coerceAtLeast(0),
                        limitHitIncrement.coerceAtLeast(0),
                    ),
                )
                .putLong("${prefix}last_used_at", System.currentTimeMillis())
                .putLong("heartbeat.$packageName", System.currentTimeMillis())
                .putInt("hook_version.$packageName", hookVersionCode.coerceAtLeast(0))
                .commit()
        }
    }

    fun summaryToday(packageName: String): AppUsageSummary {
        val prefix = "${dayToken()}.$packageName."
        return AppUsageSummary(
            packageName = packageName,
            durationMillis = prefs.getLong("${prefix}duration_ms", 0L).coerceAtLeast(0L),
            launchCount = prefs.getInt("${prefix}launches", 0).coerceAtLeast(0),
            limitHitCount = prefs.getInt("${prefix}limit_hits", 0).coerceAtLeast(0),
            lastUsedAtMillis = prefs.getLong("${prefix}last_used_at", 0L).coerceAtLeast(0L),
            lastHookEventAtMillis = prefs.getLong("${prefix}last_used_at", 0L).coerceAtLeast(0L),
            hookVersionCode = prefs.getInt("hook_version.$packageName", 0).coerceAtLeast(0),
        )
    }

    fun summariesToday(packageNames: Collection<String>): List<AppUsageSummary> =
        packageNames.map(::summaryToday)

    fun totalToday(packageNames: Collection<String>): Long =
        packageNames.sumOf { summaryToday(it).durationMillis }

    fun latestHookHeartbeat(packageNames: Collection<String>): Long = packageNames
        .maxOfOrNull { prefs.getLong("heartbeat.$it", 0L) }
        ?: 0L

    fun clearAll() {
        synchronized(LOCK) {
            prefs.edit().clear().commit()
        }
    }

    private fun dayToken(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS_NAME = "usage_statistics"
        val LOCK = Any()
    }
}

private fun safeAdd(current: Long, increment: Long): Long =
    if (increment > Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment

private fun safeAdd(current: Int, increment: Int): Int =
    if (increment > Int.MAX_VALUE - current) Int.MAX_VALUE else current + increment

internal fun normalizedUsageDayToken(
    value: String?,
    today: LocalDate = LocalDate.now(),
): String? {
    if (value.isNullOrBlank()) return today.toString()
    val parsed = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return parsed.toString().takeIf {
        !parsed.isBefore(today.minusDays(MAX_PENDING_DAYS)) &&
            !parsed.isAfter(today.plusDays(MAX_FUTURE_DAYS))
    }
}

private const val MAX_PENDING_DAYS = 31L
private const val MAX_FUTURE_DAYS = 1L
