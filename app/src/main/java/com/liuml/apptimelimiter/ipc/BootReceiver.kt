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
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        Thread {
            try {
                val repository = RuleRepository(context)
                val reconciliation = repository.reconcileRuleAccess()
                val settings = repository.getGlobalSettings()
                if (settings.diagnosticsEnabled) {
                    val event = if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        "RULE_PROVIDER_ACCESS_RECONCILED_AFTER_UPDATE"
                    } else {
                        "BOOT_COMPLETED"
                    }
                    val bootCount = Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.BOOT_COUNT,
                        -1,
                    )
                    DiagnosticsRepository(context).append(
                        level = if (reconciliation.failedPackages.isEmpty()) "INFO" else "WARN",
                        packageName = context.packageName,
                        event = event,
                        message = "configured=${reconciliation.configuredPackages.size}, granted=${reconciliation.grantedPackages.size}, failed=${reconciliation.failedPackages.size}, bootCount=$bootCount, elapsed=${SystemClock.elapsedRealtime()}ms, mode=${settings.protectionMode}, modeGeneration=${settings.protectionModeGeneration}, compatibility=${settings.nonRootCompatibilityMode}, accessibility=${NonRootProtectionStatusRepository.isAccessibilityEnabled(context)}, usageAccess=${DeviceUsageStatsRepository(context).hasUsageAccess()}",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }.apply {
            isDaemon = true
            name = "TimeStop-RuleAccessReconcile"
            start()
        }
    }
}
