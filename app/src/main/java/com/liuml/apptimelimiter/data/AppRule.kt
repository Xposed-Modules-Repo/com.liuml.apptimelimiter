package com.liuml.apptimelimiter.data

enum class RuleMode {
    DAILY,
    PER_LAUNCH,
}

enum class ScheduleMode {
    ALLOW_ONLY,
    BLOCK_DURING,
}

enum class AppLanguageMode {
    SYSTEM,
    SIMPLIFIED_CHINESE,
    ENGLISH,
}

data class ScheduleWindow(
    val daysOfWeek: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
) {
    val crossesMidnight: Boolean get() = endMinute < startMinute

    fun isValid(): Boolean = daysOfWeek.isNotEmpty() &&
        daysOfWeek.all { it in 1..7 } &&
        startMinute in 0 until MINUTES_PER_DAY &&
        endMinute in 0 until MINUTES_PER_DAY &&
        startMinute != endMinute

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

data class AppRule(
    val packageName: String,
    val enabled: Boolean = false,
    val sessionPlanningEnabled: Boolean = false,
    val dailyEnabled: Boolean = false,
    val dailyLimitSeconds: Long = 30 * 60,
    val perLaunchEnabled: Boolean = false,
    val perLaunchLimitSeconds: Long = 30 * 60,
    val scheduleEnabled: Boolean = false,
    val scheduleMode: ScheduleMode = ScheduleMode.BLOCK_DURING,
    val scheduleWindows: List<ScheduleWindow> = emptyList(),
    val cooldownEnabled: Boolean = false,
    val cooldownSeconds: Long = 5 * 60L,
    val version: Long = 0,
)

data class InstalledApp(
    val label: String,
    val packageName: String,
    val isSystemApp: Boolean,
)

data class AppGroup(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val dailyEnabled: Boolean = true,
    val dailyLimitSeconds: Long = 60L * 60L,
    val perLaunchEnabled: Boolean = false,
    val perLaunchLimitSeconds: Long = 30L * 60L,
    val scheduleEnabled: Boolean = false,
    val scheduleMode: ScheduleMode = ScheduleMode.BLOCK_DURING,
    val scheduleWindows: List<ScheduleWindow> = emptyList(),
    val cooldownEnabled: Boolean = false,
    val cooldownSeconds: Long = 5L * 60L,
    val packageNames: Set<String> = emptySet(),
    val version: Long = 0L,
)

data class GlobalSettings(
    val exitWarningEnabled: Boolean = true,
    val fullScreenExitWarningEnabled: Boolean = false,
    val exitWarningVibrationEnabled: Boolean = false,
    val languageMode: AppLanguageMode = AppLanguageMode.SYSTEM,
    val extensionSeconds: Long = 5 * 60L,
    val diagnosticsEnabled: Boolean = true,
    val launcherIconHidden: Boolean = false,
    val usageStatsEnabled: Boolean = true,
)
