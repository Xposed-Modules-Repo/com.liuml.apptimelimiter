package com.liuml.apptimelimiter.data

import android.content.Context
import android.content.Intent
import com.liuml.apptimelimiter.ipc.RuleContract
import java.io.File

class RuleRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRule(packageName: String): AppRule {
        val prefix = prefix(packageName)
        val legacyEnabled = prefs.getBoolean("${prefix}enabled", false)
        val legacyLimitSeconds = prefs.getLong("${prefix}limit_seconds", DEFAULT_LIMIT_SECONDS)
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
        return AppRule(
            packageName = packageName,
            enabled = legacyEnabled && (dailyEnabled || perLaunchEnabled),
            dailyEnabled = dailyEnabled,
            dailyLimitSeconds = prefs.getLong(
                "${prefix}daily_limit_seconds",
                legacyLimitSeconds,
            ).coerceAtLeast(1L),
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitSeconds = prefs.getLong(
                "${prefix}per_launch_limit_seconds",
                legacyLimitSeconds,
            ).coerceAtLeast(1L),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun save(rule: AppRule) {
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        packages += rule.packageName
        val prefix = prefix(rule.packageName)
        prefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean("${prefix}enabled", rule.enabled && (rule.dailyEnabled || rule.perLaunchEnabled))
            .putBoolean("${prefix}daily_enabled", rule.dailyEnabled)
            .putLong("${prefix}daily_limit_seconds", rule.dailyLimitSeconds.coerceAtLeast(1L))
            .putBoolean("${prefix}per_launch_enabled", rule.perLaunchEnabled)
            .putLong("${prefix}per_launch_limit_seconds", rule.perLaunchLimitSeconds.coerceAtLeast(1L))
            // Keep one legacy representation so downgrading does not leave an unreadable rule.
            .putLong(
                "${prefix}limit_seconds",
                if (rule.dailyEnabled) rule.dailyLimitSeconds else rule.perLaunchLimitSeconds,
            )
            .putString(
                "${prefix}mode",
                if (rule.dailyEnabled) RuleMode.DAILY.name else RuleMode.PER_LAUNCH.name,
            )
            .putLong("${prefix}version", System.currentTimeMillis())
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
    )

    fun saveGlobalSettings(settings: GlobalSettings) {
        prefs.edit()
            .putBoolean(KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
            .putLong(
                KEY_EXTENSION_SECONDS,
                settings.extensionSeconds.coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
            )
            .putBoolean(KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
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
        const val KEY_EXIT_WARNING_ENABLED = "global.exit_warning_enabled"
        const val KEY_EXTENSION_SECONDS = "global.extension_seconds"
        const val KEY_DIAGNOSTICS_ENABLED = "global.diagnostics_enabled"
        const val DEFAULT_EXTENSION_SECONDS = 5L * 60L
        const val MIN_EXTENSION_SECONDS = 60L
        const val MAX_EXTENSION_SECONDS = 60L * 60L
    }
}
