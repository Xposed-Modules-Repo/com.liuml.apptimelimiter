package com.liuml.apptimelimiter.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.core.GroupMembershipPolicy
import java.io.File
import java.util.UUID

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
        val scheduleWindows = ScheduleCodec.decode(
            prefs.getString("${prefix}schedule_windows", null),
        )
        val scheduleEnabled = prefs.getBoolean("${prefix}schedule_enabled", false) &&
            scheduleWindows.isNotEmpty()
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
            scheduleWindows = scheduleWindows,
            cooldownEnabled = prefs.getBoolean("${prefix}cooldown_enabled", false),
            cooldownSeconds = prefs.getLong(
                "${prefix}cooldown_seconds",
                DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun save(rule: AppRule) {
        val scheduleWindows = rule.scheduleWindows
            .filter(ScheduleWindow::isValid)
            .take(ScheduleCodec.MAX_WINDOWS)
        val scheduleEnabled = rule.scheduleEnabled && scheduleWindows.isNotEmpty()
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        packages += rule.packageName
        val prefix = prefix(rule.packageName)
        val previousVersion = prefs.getLong("${prefix}version", 0L)
        val nextVersion = maxOf(System.currentTimeMillis(), previousVersion + 1L)
        prefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean(
                "${prefix}enabled",
                rule.enabled && (rule.dailyEnabled || rule.perLaunchEnabled || scheduleEnabled),
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
            .putBoolean("${prefix}schedule_enabled", scheduleEnabled)
            .putString("${prefix}schedule_mode", rule.scheduleMode.name)
            .putString("${prefix}schedule_windows", ScheduleCodec.encode(scheduleWindows))
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

    fun getGroups(): List<AppGroup> = prefs.getStringSet(KEY_GROUP_IDS, emptySet())
        .orEmpty()
        .mapNotNull(::getGroup)
        .sortedBy { it.name.lowercase() }

    fun getGroup(groupId: String): AppGroup? {
        if (groupId.isBlank()) return null
        val prefix = groupPrefix(groupId)
        if (!prefs.contains("${prefix}name")) return null
        return AppGroup(
            id = groupId,
            name = prefs.getString("${prefix}name", null)
                .orEmpty()
                .ifBlank { DEFAULT_GROUP_NAME }
                .take(MAX_GROUP_NAME_LENGTH),
            enabled = prefs.getBoolean("${prefix}enabled", true),
            dailyLimitSeconds = prefs.getLong(
                "${prefix}daily_limit_seconds",
                DEFAULT_GROUP_LIMIT_SECONDS,
            ).coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            packageNames = prefs.getStringSet("${prefix}packages", emptySet())
                .orEmpty()
                .filter(String::isNotBlank)
                .take(MAX_GROUP_MEMBERS)
                .toSet(),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun groupForPackage(packageName: String): AppGroup? {
        val groupId = prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null)
            ?: return null
        return getGroup(groupId)?.takeIf { packageName in it.packageNames }
    }

    fun newGroupId(): String = UUID.randomUUID().toString()

    /** Returns false when membership conflicts with another group or persistence fails. */
    fun saveGroup(group: AppGroup): Boolean {
        val groupId = group.id.trim()
        if (groupId.isBlank() || groupId.length > MAX_GROUP_ID_LENGTH ||
            groupId.any { !it.isLetterOrDigit() && it != '-' && it != '_' }
        ) return false
        val members = group.packageNames
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot { it == appContext.packageName }
            .take(MAX_GROUP_MEMBERS)
            .toSet()
        val hasConflict = GroupMembershipPolicy.hasConflict(
            targetGroupId = groupId,
            packageNames = members,
            assignedGroupByPackage = members.associateWith { packageName ->
                prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null)
            },
        )
        if (hasConflict) return false

        val prefix = groupPrefix(groupId)
        val previousMembers = prefs.getStringSet("${prefix}packages", emptySet()).orEmpty()
        val previousVersion = prefs.getLong("${prefix}version", 0L)
        val nextVersion = maxOf(System.currentTimeMillis(), previousVersion + 1L)
        val groupIds = prefs.getStringSet(KEY_GROUP_IDS, emptySet()).orEmpty().toMutableSet()
        if (groupId !in groupIds && groupIds.size >= MAX_GROUPS) return false
        groupIds.add(groupId)
        val configuredPackages = configuredPackages().toMutableSet().apply { addAll(members) }
        val editor = prefs.edit()
            .putStringSet(KEY_GROUP_IDS, groupIds)
            .putStringSet(KEY_PACKAGES, configuredPackages)
            .putString("${prefix}name", group.name.trim().ifBlank { DEFAULT_GROUP_NAME }.take(MAX_GROUP_NAME_LENGTH))
            .putBoolean("${prefix}enabled", group.enabled && members.isNotEmpty())
            .putLong(
                "${prefix}daily_limit_seconds",
                group.dailyLimitSeconds.coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putStringSet("${prefix}packages", members)
            .putLong("${prefix}version", nextVersion)
        previousMembers.filterNot { it in members }.forEach { packageName ->
            if (prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null) == groupId) {
                editor.remove("$KEY_PACKAGE_GROUP_PREFIX$packageName")
            }
            editor.putLong("$KEY_PACKAGE_GROUP_VERSION_PREFIX$packageName", nextVersion)
        }
        members.forEach { packageName ->
            editor.putString("$KEY_PACKAGE_GROUP_PREFIX$packageName", groupId)
            editor.putLong("$KEY_PACKAGE_GROUP_VERSION_PREFIX$packageName", nextVersion)
        }
        val persisted = editor.commit()
        if (persisted) {
            members.forEach(::grantRuleAccess)
            makePreferencesReadable()
        }
        return persisted
    }

    fun deleteGroup(groupId: String): Boolean {
        val existing = getGroup(groupId) ?: return true
        val prefix = groupPrefix(groupId)
        val groupIds = prefs.getStringSet(KEY_GROUP_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { remove(groupId) }
        val editor = prefs.edit().putStringSet(KEY_GROUP_IDS, groupIds)
        existing.packageNames.forEach { packageName ->
            if (prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null) == groupId) {
                editor.remove("$KEY_PACKAGE_GROUP_PREFIX$packageName")
            }
            editor.putLong(
                "$KEY_PACKAGE_GROUP_VERSION_PREFIX$packageName",
                maxOf(System.currentTimeMillis(), existing.version + 1L),
            )
        }
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        val persisted = editor.commit()
        if (persisted) makePreferencesReadable()
        return persisted
    }

    fun getGlobalSettings(): GlobalSettings = GlobalSettings(
        exitWarningEnabled = prefs.getBoolean(KEY_EXIT_WARNING_ENABLED, true),
        fullScreenExitWarningEnabled = prefs.getBoolean(
            KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
            false,
        ),
        extensionSeconds = prefs.getLong(KEY_EXTENSION_SECONDS, DEFAULT_EXTENSION_SECONDS)
            .coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
        diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, true),
        launcherIconHidden = prefs.getBoolean(KEY_LAUNCHER_ICON_HIDDEN, false),
        usageStatsEnabled = prefs.getBoolean(KEY_USAGE_STATS_ENABLED, true),
    )

    fun saveGlobalSettings(settings: GlobalSettings) {
        prefs.edit()
            .putBoolean(KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
            .putBoolean(
                KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
                settings.fullScreenExitWarningEnabled,
            )
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
    private fun groupPrefix(groupId: String) = "group.$groupId."

    companion object {
        const val PREFS_NAME = "rules"
        const val KEY_PACKAGES = "configured_packages"
        const val KEY_GROUP_IDS = "group_ids"
        const val KEY_PACKAGE_GROUP_PREFIX = "package_group."
        const val KEY_PACKAGE_GROUP_VERSION_PREFIX = "package_group_version."
        const val DEFAULT_LIMIT_SECONDS = 30L * 60L
        const val MIN_LIMIT_SECONDS = 1L
        const val MAX_LIMIT_SECONDS = 24L * 60L * 60L
        const val DEFAULT_GROUP_LIMIT_SECONDS = 60L * 60L
        const val DEFAULT_GROUP_NAME = "应用分组"
        const val MAX_GROUP_NAME_LENGTH = 40
        const val MAX_GROUP_MEMBERS = 50
        const val MAX_GROUPS = 50
        const val MAX_GROUP_ID_LENGTH = 64
        const val KEY_EXIT_WARNING_ENABLED = "global.exit_warning_enabled"
        const val KEY_FULL_SCREEN_EXIT_WARNING_ENABLED =
            "global.full_screen_exit_warning_enabled"
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
