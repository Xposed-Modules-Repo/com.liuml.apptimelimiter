package com.liuml.apptimelimiter.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repository = RuleRepository(context)
        repository.configuredPackages().forEach(repository::grantRuleAccess)
        if (repository.getGlobalSettings().diagnosticsEnabled) {
            val bootCount = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1,
            )
            DiagnosticsRepository(context).append(
                level = "INFO",
                packageName = context.packageName,
                event = "BOOT_COMPLETED",
                message = "系统已完成启动；bootCount=$bootCount, elapsed=${SystemClock.elapsedRealtime()}ms",
            )
        }
    }
}
