package com.liuml.apptimelimiter.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.liuml.apptimelimiter.ipc.RuleContract
import java.io.File

class RuleRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = openPreferences()

    @SuppressLint("WorldReadableFiles")
    private fun openPreferences(): SharedPreferences = try {
        // LSPosed API 93+ redirects this to its protected cross-process preference area.
        // Hooked apps can then read rules with XSharedPreferences even if this app is stopped.
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
    } catch (_: SecurityException) {
        // Keeps the UI usable on unsupported frameworks; the ContentProvider remains available.
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRule(packageName: String): AppRule {
        val prefix = prefix(packageName)
        val legacyEnabled = prefs.getBoolean("${prefix}enabled", false)
        val legacyLimitSeconds = prefs.getLong("${prefix}limit_seconds", DEFAULT_LIMIT_SECONDS)
            .coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS)
        val legacyMode = prefs.getString("${prefix}mode", RuleMode.DAILY.name)
            ?.let { runCatching { RuleMode.valueOf(it) }.getOrNull() }
            ?: RuleMode.DAILY
        val hasDualThresholdRule = prefs.contains("${prefix}daily_enabled") ||
            prefs.contains("${prefix}per_launch_enabled")
        val dailyEnabled = if (hasDualThresholdRule) {
            prefs.getBoolean("${prefix}daily_enabled", false)
        } else {
            legacyEnabled && legacyMode == RuleMode.DAILY
        }
        val perLaunchEnabled = if (hasDualThresholdRule) {
            prefs.getBoolean("${prefix}per_launch_enabled", false)
        } else {
            legacyEnabled && legacyMode == RuleMode.PER_LAUNCH
        }
        val scheduleEnabled = prefs.getBoolean("${prefix}schedule_enabled", false)
        return AppRule(
            packageName = packageName,
            enabled = legacyEnabled && (dailyEnabled || perLaunchEnabled || scheduleEnabled),
            dailyEnabled = dailyEnabled,
            dailyLimitSeconds = prefs.getLong(
                "${prefix}daily_limit_seconds",
                legacyLimitSeconds,
            ).coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitSeconds = prefs.getLong(
                "${prefix}per_launch_limit_seconds",
                legacyLimitSeconds,
            ).coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            scheduleEnabled = scheduleEnabled,
            scheduleMode = prefs.getString(
                "${prefix}schedule_mode",
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(
                prefs.getString("${prefix}schedule_windows", null),
            ),
            cooldownEnabled = prefs.getBoolean("${prefix}cooldown_enabled", false),
            cooldownSeconds = prefs.getLong(
                "${prefix}cooldown_seconds",
                DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun save(rule: AppRule) {
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        packages += rule.packageName
        val prefix = prefix(rule.packageName)
        val previousVersion = prefs.getLong("${prefix}version", 0L)
        val nextVersion = maxOf(System.currentTimeMillis(), previousVersion + 1L)
        prefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean(
                "${prefix}enabled",
                rule.enabled && (rule.dailyEnabled || rule.perLaunchEnabled || rule.scheduleEnabled),
            )
            .putBoolean("${prefix}daily_enabled", rule.dailyEnabled)
            .putLong(
                "${prefix}daily_limit_seconds",
                rule.dailyLimitSeconds.coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putBoolean("${prefix}per_launch_enabled", rule.perLaunchEnabled)
            .putLong(
                "${prefix}per_launch_limit_seconds",
                rule.perLaunchLimitSeconds.coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putBoolean("${prefix}schedule_enabled", rule.scheduleEnabled)
            .putString("${prefix}schedule_mode", rule.scheduleMode.name)
            .putString("${prefix}schedule_windows", ScheduleCodec.encode(rule.scheduleWindows))
            .putBoolean("${prefix}cooldown_enabled", rule.cooldownEnabled)
            .putLong(
                "${prefix}cooldown_seconds",
                rule.cooldownSeconds.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            )
            // Keep one legacy representation so downgrading does not leave an unreadable rule.
            .putLong(
                "${prefix}limit_seconds",
                (if (rule.dailyEnabled) rule.dailyLimitSeconds else rule.perLaunchLimitSeconds)
                    .coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putString(
                "${prefix}mode",
                if (rule.dailyEnabled) RuleMode.DAILY.name else RuleMode.PER_LAUNCH.name,
            )
            .putLong("${prefix}version", nextVersion)
            .commit()
        grantRuleAccess(rule.packageName)
        makePreferencesReadable()
    }

    fun configuredPackages(): Set<String> =
        prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun getGlobalSettings(): GlobalSettings = GlobalSettings(
        exitWarningEnabled = prefs.getBoolean(KEY_EXIT_WARNING_ENABLED, true),
        extensionSeconds = prefs.getLong(KEY_EXTENSION_SECONDS, DEFAULT_EXTENSION_SECONDS)
            .coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
        diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, true),
        launcherIconHidden = prefs.getBoolean(KEY_LAUNCHER_ICON_HIDDEN, false),
        usageStatsEnabled = prefs.getBoolean(KEY_USAGE_STATS_ENABLED, true),
    )

    fun saveGlobalSettings(settings: GlobalSettings) {
        prefs.edit()
            .putBoolean(KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
            .putLong(
                KEY_EXTENSION_SECONDS,
                settings.extensionSeconds.coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
            )
            .putBoolean(KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
            .putBoolean(KEY_LAUNCHER_ICON_HIDDEN, settings.launcherIconHidden)
            .putBoolean(KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
            .commit()
        makePreferencesReadable()
    }

    fun grantRuleAccess(packageName: String) {
        runCatching {
            appContext.grantUriPermission(
                packageName,
                RuleContract.CONTENT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun makePreferencesReadable() {
        // Legacy Xposed's XSharedPreferences reads this file from hooked processes.
        // Re-apply permissions after every commit because Android may replace the XML file.
        val dataDir = File(appContext.applicationInfo.dataDir)
        val sharedPrefsDir = File(dataDir, "shared_prefs")
        val prefsFile = File(sharedPrefsDir, "$PREFS_NAME.xml")
        dataDir.setExecutable(true, false)
        sharedPrefsDir.setReadable(true, false)
        sharedPrefsDir.setExecutable(true, false)
        prefsFile.setReadable(true, false)
    }

    private fun prefix(packageName: String) = "rule.$packageName."

    companion object {
        const val PREFS_NAME = "rules"
        const val KEY_PACKAGES = "configured_packages"
        const val DEFAULT_LIMIT_SECONDS = 30L * 60L
        const val MIN_LIMIT_SECONDS = 1L
        const val MAX_LIMIT_SECONDS = 24L * 60L * 60L
        const val KEY_EXIT_WARNING_ENABLED = "global.exit_warning_enabled"
        const val KEY_EXTENSION_SECONDS = "global.extension_seconds"
        const val KEY_DIAGNOSTICS_ENABLED = "global.diagnostics_enabled"
        const val KEY_LAUNCHER_ICON_HIDDEN = "global.launcher_icon_hidden"
        const val KEY_USAGE_STATS_ENABLED = "global.usage_stats_enabled"
        const val DEFAULT_EXTENSION_SECONDS = 5L * 60L
        const val DEFAULT_COOLDOWN_SECONDS = 5L * 60L
        const val MIN_COOLDOWN_SECONDS = 60L
        const val MAX_COOLDOWN_SECONDS = 24L * 60L * 60L
        const val MIN_EXTENSION_SECONDS = 60L
        const val MAX_EXTENSION_SECONDS = 60L * 60L
    }
}
