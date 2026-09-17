package com.liuml.apptimelimiter.backup

import com.liuml.apptimelimiter.core.CooldownPolicy
import com.liuml.apptimelimiter.core.ExtensionQuotaPolicy
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.data.hasPersonalConfiguration

data class PortableGlobalSettings(
    val exitWarningEnabled: Boolean = true,
    val fullScreenExitWarningEnabled: Boolean = false,
    val exitWarningVibrationEnabled: Boolean = false,
    val usageMilestoneReminderEnabled: Boolean = false,
    val openUsageTipEnabled: Boolean = true,
    val languageMode: AppLanguageMode = AppLanguageMode.SYSTEM,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themeColor: AppThemeColor = AppThemeColor.GREEN,
    val timeQuotesEnabled: Boolean = true,
    val builtInTimeQuotesEnabled: Boolean = true,
    val customTimeQuotes: List<String> = emptyList(),
    val automaticUpdateCheckEnabled: Boolean = true,
    val extensionEnabled: Boolean = true,
    val extensionSeconds: Long = RuleRepository.DEFAULT_EXTENSION_SECONDS,
    val extensionDailyLimit: Int = ExtensionQuotaPolicy.DEFAULT_DAILY_LIMIT,
    val extensionSessionLimit: Int = ExtensionQuotaPolicy.DEFAULT_SESSION_LIMIT,
    val extensionFreeDailyLimit: Int = ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT,
    val diagnosticsEnabled: Boolean = true,
    val usageStatsEnabled: Boolean = true,
)

data class PortableBackupV1(
    val createdAtMillis: Long,
    val sourceVersionName: String,
    val sourceVersionCode: Int,
    val rules: List<AppRule>,
    val groups: List<AppGroup>,
    val settings: PortableGlobalSettings,
)

data class PortableBackupPreview(
    val backup: PortableBackupV1,
    val installedRuleCount: Int,
    val missingRulePackages: Set<String>,
    val existingRuleCount: Int,
    val existingGroupCount: Int,
    val currentFingerprint: String,
    val diff: PortableBackupDiff,
)

sealed interface PortableBackupValidationResult {
    data class Valid(val backup: PortableBackupV1) : PortableBackupValidationResult
    data class Invalid(val reason: String) : PortableBackupValidationResult
}

object PortableBackupPolicy {
    const val FORMAT = "com.liuml.apptimelimiter.portable-backup"
    const val SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
    const val MAX_RULES = 1_000
    const val MAX_QUOTES = 20
    const val MAX_QUOTE_LENGTH = 80

    fun validate(
        backup: PortableBackupV1,
        selfPackageName: String,
    ): PortableBackupValidationResult {
        if (backup.createdAtMillis <= 0L) return invalid("invalid_created_at")
        if (backup.sourceVersionName.isBlank() || backup.sourceVersionName.length > 80) {
            return invalid("invalid_source_version")
        }
        if (backup.sourceVersionCode < 0) return invalid("invalid_source_version_code")
        if (backup.rules.size > MAX_RULES) return invalid("too_many_rules")
        if (backup.groups.size > RuleRepository.MAX_GROUPS) return invalid("too_many_groups")
        if (backup.rules.map(AppRule::packageName).toSet().size != backup.rules.size) {
            return invalid("duplicate_rule_package")
        }
        if (backup.groups.map(AppGroup::id).toSet().size != backup.groups.size) {
            return invalid("duplicate_group_id")
        }
        backup.rules.forEach { rule ->
            if (!PackageNamePolicy.isValid(rule.packageName) || rule.packageName == selfPackageName) {
                return invalid("invalid_rule_package")
            }
            if (rule.dailyLimitSeconds !in RuleRepository.MIN_LIMIT_SECONDS..RuleRepository.MAX_LIMIT_SECONDS ||
                rule.perLaunchLimitSeconds !in RuleRepository.MIN_LIMIT_SECONDS..RuleRepository.MAX_LIMIT_SECONDS
            ) return invalid("invalid_rule_limit")
            if (rule.cooldownSeconds !in RuleRepository.MIN_COOLDOWN_SECONDS..RuleRepository.MAX_COOLDOWN_SECONDS) {
                return invalid("invalid_rule_cooldown")
            }
            if (rule.scheduleWindows.size > 64 || rule.scheduleWindows.any { !it.isValid() }) {
                return invalid("invalid_rule_schedule")
            }
            if (rule.cooldownEnabled && !CooldownPolicy.canEnable(rule.dailyEnabled, rule.perLaunchEnabled)) {
                return invalid("orphan_rule_cooldown")
            }
        }
        val membership = mutableMapOf<String, String>()
        backup.groups.forEach { group ->
            if (!validGroupId(group.id)) return invalid("invalid_group_id")
            if (group.name.isBlank() || group.name.length > RuleRepository.MAX_GROUP_NAME_LENGTH) {
                return invalid("invalid_group_name")
            }
            if (group.packageNames.size > RuleRepository.MAX_GROUP_MEMBERS) {
                return invalid("too_many_group_members")
            }
            if (group.dailyLimitSeconds !in RuleRepository.MIN_LIMIT_SECONDS..RuleRepository.MAX_LIMIT_SECONDS ||
                group.perLaunchLimitSeconds !in RuleRepository.MIN_LIMIT_SECONDS..RuleRepository.MAX_LIMIT_SECONDS
            ) return invalid("invalid_group_limit")
            if (group.cooldownSeconds !in RuleRepository.MIN_COOLDOWN_SECONDS..RuleRepository.MAX_COOLDOWN_SECONDS) {
                return invalid("invalid_group_cooldown")
            }
            if (group.scheduleWindows.size > 64 || group.scheduleWindows.any { !it.isValid() }) {
                return invalid("invalid_group_schedule")
            }
            if (group.cooldownEnabled && !CooldownPolicy.canEnable(group.dailyEnabled, group.perLaunchEnabled)) {
                return invalid("orphan_group_cooldown")
            }
            group.packageNames.forEach { packageName ->
                if (!PackageNamePolicy.isValid(packageName) || packageName == selfPackageName) {
                    return invalid("invalid_group_package")
                }
                if (membership.put(packageName, group.id) != null) {
                    return invalid("duplicate_group_membership")
                }
            }
        }
        val personalPackages = backup.rules
            .filter(AppRule::hasPersonalConfiguration)
            .mapTo(mutableSetOf(), AppRule::packageName)
        if (personalPackages.any(membership::containsKey)) {
            return invalid("personal_group_conflict")
        }
        val settings = backup.settings
        if (settings.extensionSeconds !in RuleRepository.MIN_EXTENSION_SECONDS..RuleRepository.MAX_EXTENSION_SECONDS) {
            return invalid("invalid_extension")
        }
        if (settings.extensionDailyLimit !in 1..ExtensionQuotaPolicy.MAX_DAILY_LIMIT ||
            settings.extensionSessionLimit !in 1..ExtensionQuotaPolicy.MAX_SESSION_LIMIT
        ) {
            return invalid("invalid_extension_daily_limit")
        }
        if (settings.customTimeQuotes.size > MAX_QUOTES ||
            settings.customTimeQuotes.any { it.isBlank() || it.length > MAX_QUOTE_LENGTH }
        ) return invalid("invalid_quotes")
        return PortableBackupValidationResult.Valid(backup)
    }

    fun normalize(backup: PortableBackupV1): PortableBackupV1 = backup.copy(
        rules = backup.rules.sortedBy(AppRule::packageName).map { it.copy(version = 0L) },
        groups = backup.groups.sortedBy(AppGroup::id).map { group ->
            group.copy(packageNames = group.packageNames.toSortedSet(), version = 0L)
        },
        settings = backup.settings.copy(
            customTimeQuotes = backup.settings.customTimeQuotes
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(MAX_QUOTES),
        ),
    )

    private fun validGroupId(value: String): Boolean =
        value.isNotBlank() && value.length <= RuleRepository.MAX_GROUP_ID_LENGTH &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun invalid(reason: String) = PortableBackupValidationResult.Invalid(reason)
}

internal fun scheduleWindow(
    days: Set<Int>,
    startMinute: Int,
    endMinute: Int,
): ScheduleWindow = ScheduleWindow(days, startMinute, endMinute)

internal fun scheduleMode(value: String): ScheduleMode =
    runCatching { ScheduleMode.valueOf(value) }.getOrDefault(ScheduleMode.BLOCK_DURING)
