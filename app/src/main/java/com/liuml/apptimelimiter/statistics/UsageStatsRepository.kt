package com.liuml.apptimelimiter.statistics

import android.content.Context
import java.time.LocalDate

data class AppUsageSummary(
    val packageName: String,
    val durationMillis: Long,
    val launchCount: Int,
    val limitHitCount: Int,
    val lastUsedAtMillis: Long,
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
    ): Boolean {
        if (packageName.isBlank()) return false
        return synchronized(LOCK) {
            val day = dayToken()
            val prefix = "$day.$packageName."
            // ContentProvider calls can cold-start this process for a single short write.
            // Commit synchronously so Android cannot kill the process before apply() flushes it.
            prefs.edit()
                .putLong(
                    "${prefix}duration_ms",
                    prefs.getLong("${prefix}duration_ms", 0L) + durationMillis.coerceAtLeast(0L),
                )
                .putInt(
                    "${prefix}launches",
                    prefs.getInt("${prefix}launches", 0) + launchIncrement.coerceAtLeast(0),
                )
                .putInt(
                    "${prefix}limit_hits",
                    prefs.getInt("${prefix}limit_hits", 0) + limitHitIncrement.coerceAtLeast(0),
                )
                .putLong("${prefix}last_used_at", System.currentTimeMillis())
                .putLong("heartbeat.$packageName", System.currentTimeMillis())
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
            val editor = prefs.edit()
            prefs.all.keys.filterNot { it.startsWith("heartbeat.") }.forEach(editor::remove)
            editor.apply()
        }
    }

    private fun dayToken(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS_NAME = "usage_statistics"
        val LOCK = Any()
    }
}
