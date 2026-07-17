package com.liuml.apptimelimiter.data

enum class RuleMode {
    DAILY,
    PER_LAUNCH,
}

data class AppRule(
    val packageName: String,
    val enabled: Boolean = false,
    val limitSeconds: Long = 30 * 60,
    val mode: RuleMode = RuleMode.DAILY,
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
