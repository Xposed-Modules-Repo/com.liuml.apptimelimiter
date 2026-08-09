package com.liuml.apptimelimiter.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.nonroot.NonRootProtectionStatusRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repository = RuleRepository(context)
        repository.configuredPackages().forEach(repository::grantRuleAccess)
        val settings = repository.getGlobalSettings()
        if (settings.diagnosticsEnabled) {
            val bootCount = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1,
            )
            DiagnosticsRepository(context).append(
                level = "INFO",
                packageName = context.packageName,
                event = "BOOT_COMPLETED",
                message = "系统已完成启动；bootCount=$bootCount, elapsed=${SystemClock.elapsedRealtime()}ms, mode=${settings.protectionMode}, modeGeneration=${settings.protectionModeGeneration}, compatibility=${settings.nonRootCompatibilityMode}, accessibility=${NonRootProtectionStatusRepository.isAccessibilityEnabled(context)}, usageAccess=${DeviceUsageStatsRepository(context).hasUsageAccess()}",
            )
        }
    }
}
