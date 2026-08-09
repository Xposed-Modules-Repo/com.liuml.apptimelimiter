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
    private var summariesCache: CachedSummaries? = null

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
        return todayUsageSnapshot(packageNames).summaries
    }

    fun todayUsageSnapshot(packageNames: Collection<String>): CalculatedUsageSnapshot {
        if (packageNames.isEmpty() || !hasUsageAccess()) return CalculatedUsageSnapshot()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val tracked = packageNames.toSet()
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(this) {
            summariesCache?.takeIf {
                it.day == today &&
                    it.packageNames == tracked &&
                    nowElapsed - it.measuredAtElapsedMillis <= PROVIDER_CACHE_MS
            }?.let { return it.snapshot }
        }
        val startOfDay = today
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return CalculatedUsageSnapshot()
        val usageEvents = runCatching {
            manager.queryEvents((startOfDay - EVENT_LOOKBACK_MS).coerceAtLeast(0L), now)
        }.getOrElse { return CalculatedUsageSnapshot() }
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
        val snapshot = UsageEventDurationCalculator.calculateSnapshot(
            tracked,
            startOfDay,
            now,
            transitions,
        )
        synchronized(this) {
            summariesCache = CachedSummaries(today, tracked, nowElapsed, snapshot)
        }
        return snapshot
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

    /**
     * One-shot recovery used when the accessibility service is recreated. This is not polled.
     * A later accessibility window event remains authoritative.
     */
    fun currentForegroundPackage(): String? {
        return currentForegroundSnapshot()?.packageName
    }

    fun currentForegroundSnapshot(): ForegroundUsageSnapshot? {
        if (!hasUsageAccess()) return null
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        // Include the previous day so an Activity that stayed resumed across midnight can still
        // be reconstructed after Android recreates the accessibility service.
        val recoveryStart = LocalDate.now()
            .minusDays(1L)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val events = runCatching {
            manager.queryEvents(recoveryStart.coerceAtLeast(0L), now)
        }.getOrNull() ?: return null
        var current: ForegroundUsageSnapshot? = null
        var interactive = true
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    current = event.packageName?.takeIf(String::isNotBlank)?.let {
                        ForegroundUsageSnapshot(it, event.timeStamp)
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (current?.packageName == event.packageName) current = null
                }
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            interactive = false
                            current = null
                        }
                    }
                }
            }
        }
        return current?.takeIf { interactive }
    }

    /**
     * A bounded one-shot query for reconciling a recent accessibility foreground signal.
     * A null result means no recent transition was available and must not clear a valid signal.
     */
    fun recentForegroundSnapshot(
        lookbackMillis: Long = RECENT_FOREGROUND_LOOKBACK_MS,
    ): ForegroundUsageSnapshot? {
        if (!hasUsageAccess()) return null
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val start = (now - lookbackMillis.coerceIn(1_000L, EVENT_LOOKBACK_MS))
            .coerceAtLeast(0L)
        val events = runCatching { manager.queryEvents(start, now) }.getOrNull() ?: return null
        var current: ForegroundUsageSnapshot? = null
        var interactive = true
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    current = event.packageName?.takeIf(String::isNotBlank)?.let {
                        ForegroundUsageSnapshot(it, event.timeStamp)
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (current?.packageName == event.packageName) current = null
                }
                else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> interactive = true
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            interactive = false
                            current = null
                        }
                    }
                }
            }
        }
        return current?.takeIf { interactive }
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
        const val RECENT_FOREGROUND_LOOKBACK_MS = 15_000L
    }

    private data class CachedDuration(
        val day: LocalDate,
        val measuredAtElapsedMillis: Long,
        val durationMillis: Long,
    )

    private data class CachedSummaries(
        val day: LocalDate,
        val packageNames: Set<String>,
        val measuredAtElapsedMillis: Long,
        val snapshot: CalculatedUsageSnapshot,
    )
}

data class ForegroundUsageSnapshot(
    val packageName: String,
    val observedAtMillis: Long,
)
