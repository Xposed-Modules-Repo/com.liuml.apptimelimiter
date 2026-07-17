package com.liuml.apptimelimiter.data

enum class RuleMode {
    DAILY,
    PER_LAUNCH,
}

data class AppRule(
    val packageName: String,
    val enabled: Boolean = false,
    val dailyEnabled: Boolean = false,
    val dailyLimitSeconds: Long = 30 * 60,
    val perLaunchEnabled: Boolean = false,
    val perLaunchLimitSeconds: Long = 30 * 60,
    val version: Long = 0,
)

data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean,
)

data class GlobalSettings(
    val exitWarningEnabled: Boolean = true,
    val extensionSeconds: Long = 5 * 60L,
    val diagnosticsEnabled: Boolean = true,
)
