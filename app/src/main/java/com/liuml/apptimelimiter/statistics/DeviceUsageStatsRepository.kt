package com.liuml.apptimelimiter.statistics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/** Reads Android's usage database on demand; it does not keep a background service alive. */
class DeviceUsageStatsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val durationCache = mutableMapOf<String, CachedDuration>()

    fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun todayUsageSummaries(packageNames: Collection<String>): Map<String, CalculatedUsageSummary> {
        if (packageNames.isEmpty() || !hasUsageAccess()) return emptyMap()
        val now = System.currentTimeMillis()
        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val usageEvents = runCatching {
            manager.queryEvents((startOfDay - EVENT_LOOKBACK_MS).coerceAtLeast(0L), now)
        }.getOrElse { return emptyMap() }
        val tracked = packageNames.toSet()
        val transitions = buildList<UsageTimelineEvent> {
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                @Suppress("DEPRECATION")
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> if (event.packageName in tracked) {
                        add(UsageTransition(event.packageName, event.timeStamp, true))
                    }

                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (event.packageName in tracked) {
                        add(UsageTransition(event.packageName, event.timeStamp, false))
                    }

                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        when (event.eventType) {
                            UsageEvents.Event.SCREEN_INTERACTIVE -> {
                                add(ScreenInteractiveTransition(event.timeStamp, true))
                            }

                            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                                add(ScreenInteractiveTransition(event.timeStamp, false))
                            }
                        }
                    }
                }
            }
        }
        return UsageEventDurationCalculator.calculateSummaries(tracked, startOfDay, now, transitions)
    }

    fun todayDurations(packageNames: Collection<String>): Map<String, Long> =
        todayUsageSummaries(packageNames).mapValues { it.value.durationMillis }

    fun todayDuration(packageName: String): Long? {
        if (packageName.isBlank() || !hasUsageAccess()) return null
        val today = LocalDate.now()
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(durationCache) {
            durationCache[packageName]?.takeIf {
                it.day == today && nowElapsed - it.measuredAtElapsedMillis <= PROVIDER_CACHE_MS
            }?.let { return it.durationMillis }
        }
        val durations = todayDurations(listOf(packageName))
        if (!durations.containsKey(packageName)) return null
        val duration = durations.getValue(packageName)
        synchronized(durationCache) {
            durationCache[packageName] = CachedDuration(today, nowElapsed, duration)
        }
        return duration
    }

    fun openUsageAccessSettings() {
        val packagePage = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(packagePage) }
            .recoverCatching { appContext.startActivity(fallback) }
    }

    private companion object {
        const val EVENT_LOOKBACK_MS = 24L * 60L * 60L * 1000L
        const val PROVIDER_CACHE_MS = 5_000L
    }

    private data class CachedDuration(
        val day: LocalDate,
        val measuredAtElapsedMillis: Long,
        val durationMillis: Long,
    )
}
