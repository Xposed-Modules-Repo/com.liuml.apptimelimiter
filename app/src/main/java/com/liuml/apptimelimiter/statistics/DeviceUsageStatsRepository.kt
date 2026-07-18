package com.liuml.apptimelimiter.statistics

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/** Reads Android's own usage database only when the manager UI requests it. */
class DeviceUsageStatsRepository(context: Context) {
    private val appContext = context.applicationContext

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

    fun todayDurations(packageNames: Collection<String>): Map<String, Long> {
        if (packageNames.isEmpty() || !hasUsageAccess()) return emptyMap()
        val now = System.currentTimeMillis()
        val startOfDay = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val aggregate = runCatching {
            manager.queryAndAggregateUsageStats(startOfDay, now)
        }.getOrElse { return emptyMap() }
        return packageNames.associateWith { packageName ->
            aggregate[packageName]?.totalTimeInForeground?.coerceAtLeast(0L) ?: 0L
        }
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
}
