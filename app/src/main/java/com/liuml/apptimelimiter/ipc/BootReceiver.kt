package com.liuml.apptimelimiter.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.liuml.apptimelimiter.data.RuleRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repository = RuleRepository(context)
        repository.configuredPackages().forEach(repository::grantRuleAccess)
    }
}
