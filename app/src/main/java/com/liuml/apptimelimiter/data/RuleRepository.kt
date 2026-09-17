package com.liuml.apptimelimiter.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.backup.PortableBackupPolicy
import com.liuml.apptimelimiter.backup.PortableBackupDiffPolicy
import com.liuml.apptimelimiter.backup.PortableBackupV1
import com.liuml.apptimelimiter.backup.PortableBackupValidationResult
import com.liuml.apptimelimiter.backup.PortableGlobalSettings
import com.liuml.apptimelimiter.core.SharedCooldownClaim
import com.liuml.apptimelimiter.core.SharedCooldownPolicy
import com.liuml.apptimelimiter.core.SharedCooldownRecord
import com.liuml.apptimelimiter.core.CooldownClock
import com.liuml.apptimelimiter.core.SharedGroupSessionAction
import com.liuml.apptimelimiter.core.SharedGroupSessionPolicy
import com.liuml.apptimelimiter.core.SharedGroupSessionRecord
import com.liuml.apptimelimiter.core.SharedGroupSessionUpdate
import com.liuml.apptimelimiter.core.CooldownPolicy
import com.liuml.apptimelimiter.core.ExtensionQuotaPolicy
import com.liuml.apptimelimiter.core.ExtensionQuotaDecision
import com.liuml.apptimelimiter.core.ExtensionQuotaState
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.MonotonicVersionPolicy
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.core.ProtectionModePolicy
import com.liuml.apptimelimiter.core.RuleStorageBootstrapAction
import com.liuml.apptimelimiter.core.RuleStorageBootstrapPolicy
import com.liuml.apptimelimiter.core.RuleActivationPolicy
import com.liuml.apptimelimiter.core.TimeQuotePolicy
import com.liuml.apptimelimiter.core.ThemeColorPolicy
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.core.GroupMembershipPolicy
import com.liuml.apptimelimiter.migration.MigrationStorageGate
import java.io.File
import java.util.UUID
import android.os.SystemClock

class RuleRepository(context: Context) {
    private val appContext = context.applicationContext
    private val devicePreferences = appContext.getSharedPreferences(
        DEVICE_SETTINGS_PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val lifecyclePrefs = appContext.getSharedPreferences(
        STORAGE_LIFECYCLE_PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val sharedStore = openSharedPreferences()
    private val sharedPrefs = sharedStore.preferences
    private val primaryPrefs = appContext.getSharedPreferences(
        PRIMARY_PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val prefs = prepareRuleStorage()

    init {
        migrateLegacyExtensionDefaults()
    }

    private fun migrateLegacyExtensionDefaults() = synchronized(STORAGE_LIFECYCLE_LOCK) {
        migrateLegacyExtensionDefaultsLocked()
    }

    private fun migrateLegacyExtensionDefaultsLocked() {
        val hasSessionLimit = prefs.contains(KEY_EXTENSION_SESSION_LIMIT)
        val hasFreeDailyLimit = prefs.contains(KEY_EXTENSION_FREE_DAILY_LIMIT)
        val legacyDailyLimit = prefs.getLong(KEY_EXTENSION_DAILY_LIMIT, 3L).toInt()
        if (!ExtensionQuotaPolicy.shouldMigrateLegacyDefaults(
                legacyDailyLimit = legacyDailyLimit,
                hasSessionLimit = hasSessionLimit,
                hasFreeDailyLimit = hasFreeDailyLimit,
            )
        ) return
        if (prefs.edit()
                .putLong(KEY_EXTENSION_DAILY_LIMIT, ExtensionQuotaPolicy.DEFAULT_DAILY_LIMIT.toLong())
                .putLong(KEY_EXTENSION_SESSION_LIMIT, ExtensionQuotaPolicy.DEFAULT_SESSION_LIMIT.toLong())
                .putLong(KEY_EXTENSION_FREE_DAILY_LIMIT, ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT.toLong())
                .commit()
        ) {
            makePreferencesReadable()
        }
    }

    @SuppressLint("WorldReadableFiles")
    private fun openSharedPreferences(): SharedPreferenceStore = try {
        // LSPosed API 93+ redirects this to its protected cross-process preference area.
        // Hooked apps can then read rules with XSharedPreferences even if this app is stopped.
        SharedPreferenceStore(
            preferences = appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_WORLD_READABLE,
            ),
            frameworkBacked = true,
        )
    } catch (_: SecurityException) {
        // Keeps the UI usable on unsupported frameworks; the ContentProvider remains available.
        SharedPreferenceStore(
            preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            frameworkBacked = false,
        )
    }

    private fun prepareRuleStorage(): SharedPreferences {
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val privateMarkerPresent = lifecyclePrefs.getBoolean(
                KEY_PRIVATE_STORAGE_INITIALIZED,
                false,
            )
            val sharedMarkerPresent = runCatching {
                sharedPrefs.getBoolean(
                    KEY_SHARED_STORAGE_INITIALIZED,
                    false,
                )
            }.getOrDefault(false)
            val primaryMarkerPresent = primaryPrefs.getBoolean(
                KEY_PRIMARY_STORAGE_INITIALIZED,
                false,
            )
            if (
                (BuildConfig.LEGACY_MIGRATION_EXPORT_ENABLED || BuildConfig.MODERN_XPOSED_ENABLED) &&
                sharedStore.frameworkBacked &&
                sharedMarkerPresent
            ) {
                val sharedGeneration = sharedPrefs.getLong(KEY_RULESET_GENERATION, 0L)
                val primaryGeneration = primaryPrefs.getLong(KEY_RULESET_GENERATION, 0L)
                val sharedPackages = sharedPrefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
                val primaryPackages = primaryPrefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
                if (
                    !primaryMarkerPresent ||
                    sharedGeneration > primaryGeneration ||
                    (sharedGeneration == primaryGeneration &&
                        sharedPackages.isNotEmpty() && primaryPackages.isEmpty())
                ) {
                    val adopted = primaryPrefs.edit().clear().also { editor ->
                        copyPreferences(sharedPrefs, editor)
                    }.putBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, true).commit()
                    check(adopted) { "Failed to adopt the legacy LSPosed rule store" }
                }
            }
            if (
                !primaryMarkerPresent &&
                privateMarkerPresent &&
                !sharedStore.frameworkBacked
            ) {
                // An existing legacy install may have its real rule file in LSPosed's protected
                // mirror. Disabling the module makes MODE_WORLD_READABLE fall back to a different
                // private file. Keep that file usable for this process, but do not promote or
                // mirror it until LSPosed is available again, otherwise an empty fallback could
                // overwrite the user's real rules.
                return sharedPrefs
            }
            val action = RuleStorageBootstrapPolicy.action(
                privateMarkerPresent = privateMarkerPresent,
                sharedMarkerPresent = sharedMarkerPresent,
                primaryMarkerPresent = primaryMarkerPresent,
            )
            if (action != RuleStorageBootstrapAction.KEEP) {
                val previousGeneration = maxOf(
                    primaryPrefs.getLong(KEY_RULESET_GENERATION, 0L),
                    runCatching {
                        sharedPrefs.getLong(KEY_RULESET_GENERATION, 0L)
                    }.getOrDefault(0L),
                )
                val nextGeneration = MonotonicVersionPolicy.next(
                    previousVersion = previousGeneration,
                    wallClockMillis = System.currentTimeMillis(),
                )
                val editor = primaryPrefs.edit().clear()
                if (action == RuleStorageBootstrapAction.ADOPT_EXISTING) {
                    runCatching { copyPreferences(sharedPrefs, editor) }
                }
                check(
                    editor
                        .putBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, true)
                        .putBoolean(KEY_SHARED_STORAGE_INITIALIZED, true)
                        .putLong(KEY_RULESET_GENERATION, nextGeneration)
                        .commit(),
                ) {
                    "Failed to initialize the private rule store"
                }
            }
            check(
                lifecyclePrefs.edit()
                    .putBoolean(KEY_PRIVATE_STORAGE_INITIALIZED, true)
                    .commit(),
            ) {
                "Failed to persist the private rule lifecycle marker"
            }
            if (MigrationStorageGate.maySynchronizeMirror(appContext)) {
                syncSharedMirrorLocked(primaryPrefs)
            }
            return primaryPrefs
        }
    }

    fun getRule(packageName: String): AppRule {
        if (!PackageNamePolicy.isValid(packageName)) return AppRule(packageName = packageName)
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
        val cooldownEnabled = prefs.getBoolean("${prefix}cooldown_enabled", false) &&
            CooldownPolicy.canEnable(dailyEnabled, perLaunchEnabled)
        return AppRule(
            packageName = packageName,
            enabled = legacyEnabled && (dailyEnabled || perLaunchEnabled || scheduleEnabled),
            sessionPlanningEnabled = prefs.getBoolean(
                "${prefix}session_planning_enabled",
                false,
            ),
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
            cooldownEnabled = cooldownEnabled,
            cooldownSeconds = prefs.getLong(
                "${prefix}cooldown_seconds",
                DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun save(rule: AppRule): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        saveLocked(rule)
    }

    private fun saveLocked(rule: AppRule): Boolean {
        if (
            !PackageNamePolicy.isValid(rule.packageName) ||
            rule.packageName == appContext.packageName
        ) return false
        val scheduleWindows = rule.scheduleWindows
            .filter(ScheduleWindow::isValid)
            .take(ScheduleCodec.MAX_WINDOWS)
        val scheduleEnabled = rule.scheduleEnabled && scheduleWindows.isNotEmpty()
        val cooldownEnabled = rule.cooldownEnabled &&
            CooldownPolicy.canEnable(rule.dailyEnabled, rule.perLaunchEnabled)
        val hasPersonalConfiguration = rule.sessionPlanningEnabled ||
            rule.dailyEnabled ||
            rule.perLaunchEnabled ||
            scheduleEnabled ||
            cooldownEnabled
        if (hasPersonalConfiguration && groupForPackage(rule.packageName) != null) return false
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        packages += rule.packageName
        val prefix = prefix(rule.packageName)
        val previousVersion = prefs.getLong("${prefix}version", 0L)
        val nextVersion = MonotonicVersionPolicy.next(
            previousVersion = previousVersion,
            wallClockMillis = System.currentTimeMillis(),
        )
        val persisted = prefs.edit()
            .putStringSet(KEY_PACKAGES, packages)
            .putBoolean(
                "${prefix}enabled",
                rule.enabled && (rule.dailyEnabled || rule.perLaunchEnabled || scheduleEnabled),
            )
            .putBoolean(
                "${prefix}session_planning_enabled",
                rule.sessionPlanningEnabled,
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
            .putBoolean("${prefix}cooldown_enabled", cooldownEnabled)
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
        if (persisted) {
            grantRuleAccess(rule.packageName)
            makePreferencesReadable()
        }
        return persisted
    }

    /** Packages that currently have an executable personal or group rule. */
    fun configuredPackages(): Set<String> = knownPackages()
            .asSequence()
            .filter(PackageNamePolicy::isValid)
            .filterNot { it == appContext.packageName }
            .filter { packageName ->
                RuleActivationPolicy.hasEffectiveRule(
                    rule = getRule(packageName),
                    assignedGroup = groupForPackage(packageName),
                )
            }
            .toSet()

    private fun knownPackages(): Set<String> = prefs.getStringSet(KEY_PACKAGES, emptySet())
        .orEmpty() + getGroups().flatMap(AppGroup::packageNames)

    fun rulesetGeneration(): Long = prefs.getLong(KEY_RULESET_GENERATION, 0L)

    /** Atomically consumes one ordinary delay for this app or its owning group's active session. */
    fun claimExtension(
        packageName: String,
        dayToken: String,
        sessionId: String = "",
    ): com.liuml.apptimelimiter.core.ExtensionQuotaDecision {
        val group = groupForPackage(packageName)
        val identity = group?.let { "group:${it.id}" } ?: "package:$packageName"
        val safeSession = sessionId.take(160)
        if (safeSession.isBlank()) throw IllegalArgumentException("missing_extension_session")
        val settings = getGlobalSettings()
        if (!settings.extensionEnabled) {
            return ExtensionQuotaDecision(false, false, ExtensionQuotaState(), 0, 0, 0)
        }
        val globalPrefix = "runtime.extension.global.daily."
        val sessionPrefix = "runtime.extension.$identity.session."
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val decision = ExtensionQuotaPolicy.claimFree(
                state = ExtensionQuotaState(
                    dayToken = prefs.getString(globalPrefix + "day", "").orEmpty(),
                    dailyUsedCount = prefs.getInt(globalPrefix + "count", 0),
                    freeUsedCount = prefs.getInt(globalPrefix + "free_count", 0),
                    sessionId = prefs.getString(sessionPrefix + "id", "").orEmpty(),
                    sessionUsedCount = prefs.getInt(sessionPrefix + "count", 0),
                ),
                dayToken = dayToken.take(32),
                sessionId = safeSession,
                dailyLimit = settings.extensionDailyLimit,
                sessionLimit = settings.extensionSessionLimit,
                freeDailyLimit = settings.extensionFreeDailyLimit,
            )
            if (!decision.allowed) return decision
            if (!prefs.edit()
                    .putString(globalPrefix + "day", decision.nextState.dayToken)
                    .putInt(globalPrefix + "count", decision.nextState.dailyUsedCount)
                    .putInt(globalPrefix + "free_count", decision.nextState.freeUsedCount)
                    .putString(sessionPrefix + "id", decision.nextState.sessionId)
                    .putInt(sessionPrefix + "count", decision.nextState.sessionUsedCount)
                    .commit()) {
                throw IllegalStateException("extension_quota_persist_failed")
            }
            recordExtensionStatistic(packageName, decision.nextState.dayToken, identity, decision.nextState.dailyUsedCount)
            return decision
        }
    }

    /** Claims an extension only after a rewarded-ad callback has been verified. */
    fun claimRewardedExtension(
        packageName: String,
        dayToken: String,
        sessionId: String,
    ): ExtensionQuotaDecision {
        val settings = getGlobalSettings()
        if (!settings.extensionEnabled) {
            return ExtensionQuotaDecision(false, false, ExtensionQuotaState(), 0, 0, 0)
        }
        val group = groupForPackage(packageName)
        val identity = group?.let { "group:${it.id}" } ?: "package:$packageName"
        val safeSession = sessionId.take(160)
        if (safeSession.isBlank()) throw IllegalArgumentException("missing_extension_session")
        val globalPrefix = "runtime.extension.global.daily."
        val sessionPrefix = "runtime.extension.$identity.session."
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val decision = ExtensionQuotaPolicy.claimRewarded(
                state = ExtensionQuotaState(
                    dayToken = prefs.getString(globalPrefix + "day", "").orEmpty(),
                    dailyUsedCount = prefs.getInt(globalPrefix + "count", 0),
                    freeUsedCount = prefs.getInt(globalPrefix + "free_count", 0),
                    sessionId = prefs.getString(sessionPrefix + "id", "").orEmpty(),
                    sessionUsedCount = prefs.getInt(sessionPrefix + "count", 0),
                ),
                dayToken = dayToken.take(32),
                sessionId = safeSession,
                dailyLimit = settings.extensionDailyLimit,
                sessionLimit = settings.extensionSessionLimit,
                freeDailyLimit = settings.extensionFreeDailyLimit,
            )
            if (!decision.allowed) return decision
            if (!prefs.edit()
                    .putString(globalPrefix + "day", decision.nextState.dayToken)
                    .putInt(globalPrefix + "count", decision.nextState.dailyUsedCount)
                    .putInt(globalPrefix + "free_count", decision.nextState.freeUsedCount)
                    .putString(sessionPrefix + "id", decision.nextState.sessionId)
                    .putInt(sessionPrefix + "count", decision.nextState.sessionUsedCount)
                    .commit()) {
                throw IllegalStateException("rewarded_extension_quota_persist_failed")
            }
            recordExtensionStatistic(packageName, decision.nextState.dayToken, identity, decision.nextState.dailyUsedCount)
            return decision
        }
    }

    /** Checks whether a rewarded extension can be granted without consuming its quota. */
    fun previewRewardedExtension(
        packageName: String,
        dayToken: String,
        sessionId: String,
    ): ExtensionQuotaDecision {
        val settings = getGlobalSettings()
        if (!settings.extensionEnabled) {
            return ExtensionQuotaDecision(false, false, ExtensionQuotaState(), 0, 0, 0)
        }
        val group = groupForPackage(packageName)
        val identity = group?.let { "group:${it.id}" } ?: "package:$packageName"
        val safeSession = sessionId.take(160)
        if (safeSession.isBlank()) throw IllegalArgumentException("missing_extension_session")
        val globalPrefix = "runtime.extension.global.daily."
        val sessionPrefix = "runtime.extension.$identity.session."
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            return ExtensionQuotaPolicy.claimRewarded(
                state = ExtensionQuotaState(
                    dayToken = prefs.getString(globalPrefix + "day", "").orEmpty(),
                    dailyUsedCount = prefs.getInt(globalPrefix + "count", 0),
                    freeUsedCount = prefs.getInt(globalPrefix + "free_count", 0),
                    sessionId = prefs.getString(sessionPrefix + "id", "").orEmpty(),
                    sessionUsedCount = prefs.getInt(sessionPrefix + "count", 0),
                ),
                dayToken = dayToken.take(32),
                sessionId = safeSession,
                dailyLimit = settings.extensionDailyLimit,
                sessionLimit = settings.extensionSessionLimit,
                freeDailyLimit = settings.extensionFreeDailyLimit,
            )
        }
    }

    private fun recordExtensionStatistic(
        packageName: String,
        dayToken: String,
        identity: String,
        ordinal: Int,
    ) {
        val day = runCatching { java.time.LocalDate.parse(dayToken) }.getOrNull() ?: return
        // Quota persistence is authoritative. A statistics write failure must never revoke a
        // granted extension, and its id makes a later retry idempotent.
        UsageStatsRepository(appContext).recordExtensionEvent(
            packageName = packageName,
            day = day,
            eventId = "${identity.take(90)}:$ordinal",
        )
    }

    fun extensionRemainingCount(packageName: String, dayToken: String): Int =
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val settings = getGlobalSettings()
            if (!settings.extensionEnabled) return@synchronized 0
            val currentDay = dayToken.take(32)
            val used = if (prefs.getString("runtime.extension.global.daily.day", "") == currentDay) {
                prefs.getInt("runtime.extension.global.daily.count", 0).coerceAtLeast(0)
            } else 0
            (settings.extensionDailyLimit - used).coerceAtLeast(0)
        }

    /** The first successful parent temporary unlock each local day does not request an ad. */
    fun isParentUnlockAdRequired(dayToken: String): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        val day = dayToken.take(32)
        if (day.isBlank()) return@synchronized false
        val prefix = "runtime.parent_auth.daily."
        val used = if (prefs.getString(prefix + "day", "") == day) {
            prefs.getInt(prefix + "count", 0).coerceAtLeast(0)
        } else {
            0
        }
        used >= 1
    }

    /** Counts only a successfully persisted temporary override; cancelled ad flows remain retryable. */
    fun recordSuccessfulParentUnlock(dayToken: String): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        val day = dayToken.take(32)
        if (day.isBlank()) return@synchronized false
        val prefix = "runtime.parent_auth.daily."
        val used = if (prefs.getString(prefix + "day", "") == day) {
            prefs.getInt(prefix + "count", 0).coerceAtLeast(0)
        } else {
            0
        }
        prefs.edit()
            .putString(prefix + "day", day)
            .putInt(prefix + "count", (used + 1).coerceAtMost(Int.MAX_VALUE))
            .commit()
    }

    fun exportPortableBackup(
        sourceVersionName: String,
        sourceVersionCode: Int,
        createdAtMillis: Long = System.currentTimeMillis(),
        includeInactiveRules: Boolean = false,
    ): PortableBackupV1 = synchronized(STORAGE_LIFECYCLE_LOCK) {
        exportPortableBackupLocked(sourceVersionName, sourceVersionCode, createdAtMillis, includeInactiveRules)
    }

    private fun exportPortableBackupLocked(
        sourceVersionName: String,
        sourceVersionCode: Int,
        createdAtMillis: Long,
        includeInactiveRules: Boolean,
    ): PortableBackupV1 {
        val portableRules = knownPackages()
            .asSequence()
            .filter(PackageNamePolicy::isValid)
            .filterNot { it == appContext.packageName }
            .map(::getRule)
            .filter { includeInactiveRules || it.hasPersonalConfiguration() }
            .sortedBy(AppRule::packageName)
            .toList()
        val settings = getGlobalSettings()
        return PortableBackupV1(
            createdAtMillis = createdAtMillis,
            sourceVersionName = sourceVersionName,
            sourceVersionCode = sourceVersionCode,
            rules = portableRules,
            groups = getGroups(),
            settings = PortableGlobalSettings(
                exitWarningEnabled = settings.exitWarningEnabled,
                fullScreenExitWarningEnabled = settings.fullScreenExitWarningEnabled,
                exitWarningVibrationEnabled = settings.exitWarningVibrationEnabled,
                usageMilestoneReminderEnabled = settings.usageMilestoneReminderEnabled,
                openUsageTipEnabled = settings.openUsageTipEnabled,
                languageMode = settings.languageMode,
                themeMode = settings.themeMode,
                themeColor = settings.themeColor,
                timeQuotesEnabled = settings.timeQuotesEnabled,
                builtInTimeQuotesEnabled = settings.builtInTimeQuotesEnabled,
                customTimeQuotes = settings.customTimeQuotes,
                automaticUpdateCheckEnabled = settings.automaticUpdateCheckEnabled,
                extensionEnabled = settings.extensionEnabled,
                extensionSeconds = settings.extensionSeconds,
                extensionDailyLimit = settings.extensionDailyLimit,
                extensionSessionLimit = settings.extensionSessionLimit,
                extensionFreeDailyLimit = settings.extensionFreeDailyLimit,
                diagnosticsEnabled = settings.diagnosticsEnabled,
                usageStatsEnabled = settings.usageStatsEnabled,
            ),
        )
    }

    /** Removes one retained app configuration, including membership in an imported group. */
    fun deletePortableConfiguration(packageName: String): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        deletePortableConfigurationLocked(packageName)
    }

    private fun deletePortableConfigurationLocked(packageName: String): Boolean {
        if (!PackageNamePolicy.isValid(packageName) || packageName == appContext.packageName) {
            return false
        }
        val group = groupForPackage(packageName)
        if (group != null && !saveGroup(group.copy(packageNames = group.packageNames - packageName))) {
            return false
        }
        // Saving an empty rule keeps a monotonic tombstone for already-running Hook processes,
        // while configuredPackages()/portable exports no longer treat it as active configuration.
        return save(AppRule(packageName = packageName))
    }

    /** Atomically replaces portable configuration while retaining device security and engine state. */
    fun replacePortableConfiguration(backup: PortableBackupV1): Boolean {
        val normalized = PortableBackupPolicy.normalize(backup)
        if (
            PortableBackupPolicy.validate(normalized, appContext.packageName) !is
            PortableBackupValidationResult.Valid
        ) return false
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val current = getGlobalSettings()
            val previousGeneration = rulesetGeneration()
            val nextGeneration = MonotonicVersionPolicy.next(
                previousVersion = previousGeneration,
                wallClockMillis = System.currentTimeMillis(),
            )
            val nextModeGeneration = MonotonicVersionPolicy.next(
                previousVersion = current.protectionModeGeneration,
                wallClockMillis = System.currentTimeMillis(),
            )
            val groupMembers = normalized.groups.flatMapTo(mutableSetOf(), AppGroup::packageNames)
            val packages = (normalized.rules.map(AppRule::packageName) + groupMembers).toSet()
            val editor = prefs.edit().clear()
                .putBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, true)
                .putBoolean(KEY_SHARED_STORAGE_INITIALIZED, true)
                .putLong(KEY_RULESET_GENERATION, nextGeneration)
                .putStringSet(KEY_PACKAGES, packages)
                .putStringSet(KEY_GROUP_IDS, normalized.groups.mapTo(mutableSetOf(), AppGroup::id))

            normalized.rules.forEach { rule ->
                writeRule(editor, rule, nextGeneration)
            }
            normalized.groups.forEach { group ->
                writeGroup(editor, group, nextGeneration)
            }
            writePortableAndDeviceSettings(
                editor = editor,
                portable = normalized.settings,
                device = current,
                protectionModeGeneration = nextModeGeneration,
            )
            if (!editor.commit()) return false
            if (!lifecyclePrefs.edit().putBoolean(KEY_PRIVATE_STORAGE_INITIALIZED, true).commit()) {
                return false
            }
            makePreferencesReadable()
            packages.forEach(::grantRuleAccess)
            return true
        }
    }

    /**
     * All portable writers (including bootstrap, migration and deletes) use the same process-wide
     * lock. The manifest keeps the authority and its writers in the manager process. Never move
     * the rollback callback outside this lock: it must persist exactly the compared snapshot.
     * A stale preview or a failed rollback write must leave the configuration untouched.
     */
    fun compareAndReplacePortableConfiguration(
        backup: PortableBackupV1,
        expectedFingerprint: String,
        persistRollback: (PortableBackupV1) -> Unit,
    ): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        val normalized = PortableBackupPolicy.normalize(backup)
        if (PortableBackupPolicy.validate(normalized, appContext.packageName) !is PortableBackupValidationResult.Valid) {
            return@synchronized false
        }
        val current = exportPortableBackupLocked(
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis(),
            includeInactiveRules = true,
        )
        PortableBackupDiffPolicy.requireCurrent(expectedFingerprint, current)
        persistRollback(current)
        replacePortableConfiguration(normalized)
    }

    fun isLegacyMigrationAuthorityConfirmed(): Boolean {
        val primaryReady = primaryPrefs.getBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, false)
        val sharedReady = runCatching {
            sharedPrefs.getBoolean(KEY_SHARED_STORAGE_INITIALIZED, false)
        }.getOrDefault(false)
        val mode = getGlobalSettings().protectionMode
        return primaryReady && (
            mode != ProtectionMode.XPOSED ||
                (sharedStore.frameworkBacked && sharedReady) ||
                configuredPackages().isEmpty()
            )
    }

    internal fun exportMigrationSnapshot(): Map<String, *> = prefs.all
        .filterKeys { key -> !key.contains(".runtime_") }
        .toMap()

    internal fun rawAuthoritativeSnapshot(): Map<String, *> = prefs.all.toMap()

    internal fun replaceRawAuthoritativeSnapshot(values: Map<String, *>): Boolean =
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            val editor = prefs.edit().clear()
            copyPreferences(values, editor)
            val previousGeneration = values[KEY_RULESET_GENERATION] as? Long ?: 0L
            val nextGeneration = MonotonicVersionPolicy.next(
                previousVersion = previousGeneration,
                wallClockMillis = System.currentTimeMillis(),
            )
            val committed = editor
                .putBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, true)
                .putBoolean(KEY_SHARED_STORAGE_INITIALIZED, true)
                .putLong(KEY_RULESET_GENERATION, nextGeneration)
                .commit()
            if (committed) {
                lifecyclePrefs.edit().putBoolean(KEY_PRIVATE_STORAGE_INITIALIZED, true).commit()
                makePreferencesReadable()
            }
            committed
        }

    fun getGroups(): List<AppGroup> = prefs.getStringSet(KEY_GROUP_IDS, emptySet())
        .orEmpty()
        .mapNotNull(::getGroup)
        .sortedBy { it.name.lowercase() }

    fun getGroup(groupId: String): AppGroup? {
        if (groupId.isBlank()) return null
        val prefix = groupPrefix(groupId)
        if (!prefs.contains("${prefix}name")) return null
        val scheduleWindows = ScheduleCodec.decode(
            prefs.getString("${prefix}schedule_windows", null),
        )
        val dailyEnabled = prefs.getBoolean("${prefix}daily_enabled", true)
        val perLaunchEnabled = prefs.getBoolean("${prefix}per_launch_enabled", false)
        val scheduleEnabled = prefs.getBoolean("${prefix}schedule_enabled", false) &&
            scheduleWindows.isNotEmpty()
        val cooldownEnabled = prefs.getBoolean("${prefix}cooldown_enabled", false) &&
            CooldownPolicy.canEnable(dailyEnabled, perLaunchEnabled)
        return AppGroup(
            id = groupId,
            name = prefs.getString("${prefix}name", null)
                .orEmpty()
                .ifBlank { DEFAULT_GROUP_NAME }
                .take(MAX_GROUP_NAME_LENGTH),
            enabled = prefs.getBoolean("${prefix}enabled", true),
            // Groups created before multi-rule support only had a shared daily quota.
            dailyEnabled = dailyEnabled,
            dailyLimitSeconds = prefs.getLong(
                "${prefix}daily_limit_seconds",
                DEFAULT_GROUP_LIMIT_SECONDS,
            ).coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitSeconds = prefs.getLong(
                "${prefix}per_launch_limit_seconds",
                DEFAULT_LIMIT_SECONDS,
            ).coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            scheduleEnabled = scheduleEnabled,
            scheduleMode = prefs.getString(
                "${prefix}schedule_mode",
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = scheduleWindows,
            cooldownEnabled = cooldownEnabled,
            cooldownSeconds = prefs.getLong(
                "${prefix}cooldown_seconds",
                DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            packageNames = prefs.getStringSet("${prefix}packages", emptySet())
                .orEmpty()
                .filter(PackageNamePolicy::isValid)
                .filterNot { it == appContext.packageName }
                .take(MAX_GROUP_MEMBERS)
                .toSet(),
            version = prefs.getLong("${prefix}version", 0L),
        )
    }

    fun groupForPackage(packageName: String): AppGroup? {
        if (!PackageNamePolicy.isValid(packageName)) return null
        val groupId = prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null)
            ?: return null
        return getGroup(groupId)?.takeIf { packageName in it.packageNames }
    }

    fun newGroupId(): String = UUID.randomUUID().toString()

    /** Returns false when membership conflicts with another group or persistence fails. */
    fun saveGroup(group: AppGroup): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        saveGroupLocked(group)
    }

    private fun saveGroupLocked(group: AppGroup): Boolean {
        val groupId = group.id.trim()
        if (groupId.isBlank() || groupId.length > MAX_GROUP_ID_LENGTH ||
            groupId.any { !it.isLetterOrDigit() && it != '-' && it != '_' }
        ) return false
        if (
            group.packageNames.size > MAX_GROUP_MEMBERS ||
            group.packageNames.any {
                !PackageNamePolicy.isValid(it) || it == appContext.packageName
            }
        ) return false
        val members = group.packageNames
            .asSequence()
            .toSet()
        val hasConflict = GroupMembershipPolicy.hasConflict(
            targetGroupId = groupId,
            packageNames = members,
            assignedGroupByPackage = members.associateWith { packageName ->
                prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null)
            },
        )
        if (hasConflict) return false
        val scheduleWindows = group.scheduleWindows
            .filter(ScheduleWindow::isValid)
            .take(ScheduleCodec.MAX_WINDOWS)
        val scheduleEnabled = group.scheduleEnabled && scheduleWindows.isNotEmpty()
        val cooldownEnabled = group.cooldownEnabled &&
            CooldownPolicy.canEnable(group.dailyEnabled, group.perLaunchEnabled)
        val hasActiveRule = group.dailyEnabled || group.perLaunchEnabled || scheduleEnabled
        val effectiveGroupEnabled = group.enabled && members.isNotEmpty() && hasActiveRule

        val prefix = groupPrefix(groupId)
        val previousMembers = prefs.getStringSet("${prefix}packages", emptySet()).orEmpty()
        if (
            GroupMembershipPolicy.hasNewMemberWithPersonalConfiguration(
                requestedMembers = members,
                existingMembers = previousMembers,
                packagesWithPersonalConfiguration = members
                    .filterTo(mutableSetOf()) { getRule(it).hasPersonalConfiguration() },
            )
        ) return false
        val previousVersion = prefs.getLong("${prefix}version", 0L)
        val nextVersion = MonotonicVersionPolicy.next(
            previousVersion = previousVersion,
            wallClockMillis = System.currentTimeMillis(),
        )
        val groupIds = prefs.getStringSet(KEY_GROUP_IDS, emptySet()).orEmpty().toMutableSet()
        if (groupId !in groupIds && groupIds.size >= MAX_GROUPS) return false
        groupIds.add(groupId)
        val configuredPackages = knownPackages().toMutableSet().apply { addAll(members) }
        val editor = prefs.edit()
            .putStringSet(KEY_GROUP_IDS, groupIds)
            .putStringSet(KEY_PACKAGES, configuredPackages)
            .putString("${prefix}name", group.name.trim().ifBlank { DEFAULT_GROUP_NAME }.take(MAX_GROUP_NAME_LENGTH))
            .putBoolean(
                "${prefix}enabled",
                effectiveGroupEnabled,
            )
            .putBoolean("${prefix}daily_enabled", group.dailyEnabled)
            .putLong(
                "${prefix}daily_limit_seconds",
                group.dailyLimitSeconds.coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putBoolean("${prefix}per_launch_enabled", group.perLaunchEnabled)
            .putLong(
                "${prefix}per_launch_limit_seconds",
                group.perLaunchLimitSeconds.coerceIn(MIN_LIMIT_SECONDS, MAX_LIMIT_SECONDS),
            )
            .putBoolean("${prefix}schedule_enabled", scheduleEnabled)
            .putString("${prefix}schedule_mode", group.scheduleMode.name)
            .putString("${prefix}schedule_windows", ScheduleCodec.encode(scheduleWindows))
            .putBoolean("${prefix}cooldown_enabled", cooldownEnabled)
            .putLong(
                "${prefix}cooldown_seconds",
                group.cooldownSeconds.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            )
            .putStringSet("${prefix}packages", members)
            .putLong("${prefix}version", nextVersion)
        if (!effectiveGroupEnabled || !cooldownEnabled) {
            removeGroupCooldownRuntime(editor, prefix)
        }
        if (!effectiveGroupEnabled || !group.perLaunchEnabled) {
            removeGroupSessionRuntime(editor, prefix)
        }
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

    fun getGroupCooldownRecord(groupId: String): SharedCooldownRecord = synchronized(GROUP_COOLDOWN_LOCK) {
        CooldownClock.requireOwner(appContext, prefs)
        if (groupId.isBlank()) return@synchronized SharedCooldownRecord()
        val prefix = groupPrefix(groupId)
        val stored = SharedCooldownRecord(
            startedAtMillis = prefs.getLong("${prefix}runtime_cooldown_started_at", 0L),
            endsAtMillis = prefs.getLong("${prefix}runtime_cooldown_ends_at", 0L),
            incidentId = prefs.getString("${prefix}runtime_cooldown_incident", null).orEmpty(),
            sourcePackage = prefs.getString(
                "${prefix}runtime_cooldown_source_package",
                null,
            ).orEmpty(),
            startedAtElapsedMillis = prefs.getLong("${prefix}runtime_cooldown_started_elapsed_at", 0L),
            endsAtElapsedMillis = prefs.getLong("${prefix}runtime_cooldown_ends_elapsed_at", 0L),
            bootCount = prefs.getInt("${prefix}runtime_cooldown_boot_count", -1),
        )
        val record = SharedCooldownPolicy.rebase(stored, System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), CooldownClock.bootCount(appContext))
        if (record.copy(validatedBootCount = -1) != stored) {
            CooldownClock.commit(prefs, prefs.edit()
                .putLong("${prefix}runtime_cooldown_started_elapsed_at", record.startedAtElapsedMillis)
                .putLong("${prefix}runtime_cooldown_ends_elapsed_at", record.endsAtElapsedMillis)
                .putInt("${prefix}runtime_cooldown_boot_count", record.bootCount))
            makePreferencesReadable()
        }
        record
    }

    /**
     * Clears only the active runtime window after it has expired. The bounded incident history is
     * deliberately retained so the same exhausted daily/session event cannot restart cooldown.
     */
    fun consumeExpiredGroupCooldown(
        groupId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): SharedCooldownRecord? = synchronized(GROUP_COOLDOWN_LOCK) {
        val record = getGroupCooldownRecord(groupId)
        if (
            record.endsAtMillis <= 0L ||
            SharedCooldownPolicy.remainingMillisDual(
                record,
                nowMillis,
                SystemClock.elapsedRealtime(),
                CooldownClock.bootCount(appContext),
            ) > 0L
        ) {
            return@synchronized null
        }
        val editor = prefs.edit()
        removeGroupCooldownRuntime(editor, groupPrefix(groupId))
        CooldownClock.commit(prefs, editor)
        makePreferencesReadable()
        record
    }

    fun claimGroupCooldown(
        groupId: String,
        incidentId: String,
        sourcePackage: String,
        occurredAtMillis: Long,
        durationMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
    ): SharedCooldownClaim = synchronized(GROUP_COOLDOWN_LOCK) {
        val cooldownBoot = CooldownClock.bootCount(appContext)
        check(durationMillis <= 0L || cooldownBoot >= 0) { "Cooldown boot identity unavailable" }
        val prefix = groupPrefix(groupId)
        val handledKey = "${prefix}runtime_cooldown_handled_incidents"
        val handled = prefs.getString(handledKey, null)
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val claim = SharedCooldownPolicy.claim(
            existingRecord = getGroupCooldownRecord(groupId),
            handledIncidentIds = handled,
            incidentId = incidentId,
            sourcePackage = sourcePackage,
            occurredAtMillis = occurredAtMillis,
            durationMillis = durationMillis,
            nowMillis = nowMillis,
            nowElapsedMillis = nowElapsedMillis,
            nowBootCount = cooldownBoot,
        )
        val editor = prefs.edit()
            .putString(handledKey, claim.handledIncidentIds.joinToString("\n"))
        if (claim.record.endsAtMillis > 0L) {
            editor
                .putLong(
                    "${prefix}runtime_cooldown_started_at",
                    claim.record.startedAtMillis,
                )
                .putLong("${prefix}runtime_cooldown_ends_at", claim.record.endsAtMillis)
                .putLong("${prefix}runtime_cooldown_started_elapsed_at", claim.record.startedAtElapsedMillis)
                .putLong("${prefix}runtime_cooldown_ends_elapsed_at", claim.record.endsAtElapsedMillis)
                .putInt("${prefix}runtime_cooldown_boot_count", claim.record.bootCount)
                .putString("${prefix}runtime_cooldown_incident", claim.record.incidentId)
                .putString(
                    "${prefix}runtime_cooldown_source_package",
                    claim.record.sourcePackage,
                )
        } else {
            removeGroupCooldownRuntime(editor, prefix)
        }
        CooldownClock.commit(prefs, editor)
        makePreferencesReadable()
        claim
    }

    fun getGroupPerLaunchSession(groupId: String): SharedGroupSessionRecord {
        if (groupId.isBlank()) return SharedGroupSessionRecord()
        val prefix = groupPrefix(groupId)
        return SharedGroupSessionRecord(
            groupVersion = prefs.getLong("${prefix}runtime_session_group_version", Long.MIN_VALUE),
            bootCount = prefs.getInt("${prefix}runtime_session_boot_count", Int.MIN_VALUE),
            sessionId = prefs.getString("${prefix}runtime_session_id", null).orEmpty(),
            usedMillis = prefs.getLong("${prefix}runtime_session_used_ms", 0L),
            activeOwnerId = prefs.getString("${prefix}runtime_session_owner", null).orEmpty(),
            ownerLastSeenElapsedMillis = prefs.getLong(
                "${prefix}runtime_session_owner_seen_elapsed_ms",
                0L,
            ),
            inactiveSinceElapsedMillis = prefs.getLong(
                "${prefix}runtime_session_inactive_elapsed_ms",
                0L,
            ),
            handledSegmentIds = prefs.getString(
                "${prefix}runtime_session_handled_segments",
                null,
            ).orEmpty().lineSequence().filter(String::isNotBlank).toList(),
        )
    }

    fun updateGroupPerLaunchSession(
        groupId: String,
        action: SharedGroupSessionAction,
        groupVersion: Long,
        bootCount: Int,
        ownerId: String,
        expectedSessionId: String,
        segmentId: String,
        segmentMillis: Long,
        nowElapsedMillis: Long,
        resetGapMillis: Long,
    ): SharedGroupSessionUpdate = synchronized(GROUP_SESSION_LOCK) {
        val prefix = groupPrefix(groupId)
        val update = SharedGroupSessionPolicy.update(
            existing = getGroupPerLaunchSession(groupId),
            action = action,
            groupVersion = groupVersion,
            bootCount = bootCount,
            ownerId = ownerId,
            expectedSessionId = expectedSessionId,
            generatedSessionId = UUID.randomUUID().toString(),
            segmentId = segmentId,
            segmentMillis = segmentMillis,
            nowElapsedMillis = nowElapsedMillis,
            resetGapMillis = resetGapMillis,
        )
        val record = update.record
        check(
            prefs.edit()
                .putLong("${prefix}runtime_session_group_version", record.groupVersion)
                .putInt("${prefix}runtime_session_boot_count", record.bootCount)
                .putString("${prefix}runtime_session_id", record.sessionId)
                .putLong("${prefix}runtime_session_used_ms", record.usedMillis)
                .putString("${prefix}runtime_session_owner", record.activeOwnerId)
                .putLong(
                    "${prefix}runtime_session_owner_seen_elapsed_ms",
                    record.ownerLastSeenElapsedMillis,
                )
                .putLong(
                    "${prefix}runtime_session_inactive_elapsed_ms",
                    record.inactiveSinceElapsedMillis,
                )
                .putString(
                    "${prefix}runtime_session_handled_segments",
                    record.handledSegmentIds.joinToString("\n"),
                )
                .commit(),
        ) { "Failed to persist group per-launch session" }
        update
    }

    fun deleteGroup(groupId: String): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        deleteGroupLocked(groupId)
    }

    private fun deleteGroupLocked(groupId: String): Boolean {
        val existing = getGroup(groupId) ?: return true
        val prefix = groupPrefix(groupId)
        val groupIds = prefs.getStringSet(KEY_GROUP_IDS, emptySet()).orEmpty().toMutableSet()
            .apply { remove(groupId) }
        val removalVersion = MonotonicVersionPolicy.next(
            previousVersion = existing.version,
            wallClockMillis = System.currentTimeMillis(),
        )
        val editor = prefs.edit().putStringSet(KEY_GROUP_IDS, groupIds)
        existing.packageNames.forEach { packageName ->
            if (prefs.getString("$KEY_PACKAGE_GROUP_PREFIX$packageName", null) == groupId) {
                editor.remove("$KEY_PACKAGE_GROUP_PREFIX$packageName")
            }
            editor.putLong(
                "$KEY_PACKAGE_GROUP_VERSION_PREFIX$packageName",
                removalVersion,
            )
        }
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        val persisted = editor.commit()
        if (persisted) makePreferencesReadable()
        return persisted
    }

    fun getGlobalSettings(): GlobalSettings {
        val protectionMode = readProtectionMode()
        val storedMode = prefs.getString(KEY_PROTECTION_MODE, null)
        val legacyRootEnabled = devicePreferences.getBoolean(KEY_ROOT_ENHANCEMENT_ENABLED, false)
        val legacyShizukuEnabled =
            storedMode == "ACCESSIBILITY_SHIZUKU" ||
                (storedMode == null && prefs.getBoolean(KEY_SHIZUKU_ENHANCEMENT_ENABLED, false))
        val accessibilityEnhancement = devicePreferences
            .getString(KEY_ACCESSIBILITY_FORCE_STOP_ENHANCEMENT, null)
            ?.let { runCatching { ForceStopEnhancement.valueOf(it) }.getOrNull() }
            ?: when {
                protectionMode == ProtectionMode.ACCESSIBILITY && legacyRootEnabled ->
                    ForceStopEnhancement.ROOT
                protectionMode == ProtectionMode.ACCESSIBILITY && legacyShizukuEnabled ->
                    ForceStopEnhancement.SHIZUKU
                else -> ForceStopEnhancement.NONE
            }
        return GlobalSettings(
        childLockEnabled = prefs.getBoolean(KEY_CHILD_LOCK_ENABLED, false),
        exitWarningEnabled = prefs.getBoolean(KEY_EXIT_WARNING_ENABLED, true),
        fullScreenExitWarningEnabled = prefs.getBoolean(
            KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
            false,
        ),
        exitWarningVibrationEnabled = prefs.getBoolean(
            KEY_EXIT_WARNING_VIBRATION_ENABLED,
            false,
        ),
        usageMilestoneReminderEnabled = prefs.getBoolean(
            KEY_USAGE_MILESTONE_REMINDER_ENABLED,
            false,
        ),
        openUsageTipEnabled = prefs.getBoolean("global.open_usage_tip_enabled", true),
        languageMode = prefs.getString(KEY_LANGUAGE_MODE, AppLanguageMode.SYSTEM.name)
            ?.let { runCatching { AppLanguageMode.valueOf(it) }.getOrNull() }
            ?: AppLanguageMode.SYSTEM,
        themeMode = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            ?.let { runCatching { AppThemeMode.valueOf(it) }.getOrNull() }
            ?: AppThemeMode.SYSTEM,
        themeColor = ThemeColorPolicy.parse(prefs.getString(KEY_THEME_COLOR, null)),
        timeQuotesEnabled = prefs.getBoolean(KEY_TIME_QUOTES_ENABLED, true),
        builtInTimeQuotesEnabled = prefs.getBoolean(KEY_BUILT_IN_TIME_QUOTES_ENABLED, true),
        customTimeQuotes = TimeQuotePolicy.parseCustomQuotes(
            prefs.getString(KEY_CUSTOM_TIME_QUOTES, "").orEmpty(),
        ),
        automaticUpdateCheckEnabled = prefs.getBoolean(
            KEY_AUTOMATIC_UPDATE_CHECK_ENABLED,
            true,
        ),
        protectionMode = protectionMode,
        protectionModeGeneration = prefs.getLong(
            KEY_PROTECTION_MODE_GENERATION,
            DEFAULT_PROTECTION_MODE_GENERATION,
        ).coerceAtLeast(DEFAULT_PROTECTION_MODE_GENERATION),
        nonRootCompatibilityMode = prefs.getString(
            KEY_NON_ROOT_COMPATIBILITY_MODE,
            NonRootCompatibilityMode.STANDARD.name,
        )?.let {
            runCatching { NonRootCompatibilityMode.valueOf(it) }.getOrNull()
        } ?: NonRootCompatibilityMode.STANDARD,
        extensionEnabled = prefs.getBoolean(KEY_EXTENSION_ENABLED, true),
        extensionSeconds = prefs.getLong(KEY_EXTENSION_SECONDS, DEFAULT_EXTENSION_SECONDS)
            .coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
        extensionDailyLimit = ExtensionQuotaPolicy.normalizeDailyLimit(
            prefs.getLong(
                KEY_EXTENSION_DAILY_LIMIT,
                ExtensionQuotaPolicy.DEFAULT_DAILY_LIMIT.toLong(),
            ).toInt(),
        ),
        extensionSessionLimit = ExtensionQuotaPolicy.normalizeSessionLimit(
            prefs.getLong(KEY_EXTENSION_SESSION_LIMIT, ExtensionQuotaPolicy.DEFAULT_SESSION_LIMIT.toLong()).toInt(),
        ),
        extensionFreeDailyLimit = ExtensionQuotaPolicy.normalizeFreeDailyLimit(
            ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT,
            ExtensionQuotaPolicy.normalizeDailyLimit(
                prefs.getLong(KEY_EXTENSION_DAILY_LIMIT, ExtensionQuotaPolicy.DEFAULT_DAILY_LIMIT.toLong()).toInt(),
            ),
        ),
        diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, true),
        launcherIconHidden = prefs.getBoolean(KEY_LAUNCHER_ICON_HIDDEN, false),
        usageStatsEnabled = prefs.getBoolean(KEY_USAGE_STATS_ENABLED, true),
        limitEnforcementMode = LimitEnforcementPolicy.parseMode(
            prefs.getString(KEY_LIMIT_ENFORCEMENT_MODE, null),
        ),
        xposedRootEnhancementEnabled = devicePreferences.getBoolean(
            KEY_XPOSED_ROOT_ENHANCEMENT_ENABLED,
            protectionMode == ProtectionMode.XPOSED && legacyRootEnabled,
        ),
        accessibilityForceStopEnhancement = accessibilityEnhancement,
        )
    }

    fun saveGlobalSettings(settings: GlobalSettings): Boolean = synchronized(STORAGE_LIFECYCLE_LOCK) {
        saveGlobalSettingsLocked(settings)
    }

    private fun saveGlobalSettingsLocked(settings: GlobalSettings): Boolean {
        val previousMode = readProtectionMode()
        val previousGeneration = prefs.getLong(
            KEY_PROTECTION_MODE_GENERATION,
            DEFAULT_PROTECTION_MODE_GENERATION,
        ).coerceAtLeast(DEFAULT_PROTECTION_MODE_GENERATION)
        val protectionModeGeneration = ProtectionModePolicy.nextGeneration(
            previousMode = previousMode,
            requestedMode = settings.protectionMode,
            previousGeneration = maxOf(previousGeneration, settings.protectionModeGeneration),
            wallClockMillis = System.currentTimeMillis(),
        )
        val persisted = prefs.edit()
            .putBoolean(KEY_CHILD_LOCK_ENABLED, settings.childLockEnabled)
            .putBoolean(KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
            .putBoolean(
                KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
                settings.fullScreenExitWarningEnabled,
            )
            .putBoolean(
                KEY_EXIT_WARNING_VIBRATION_ENABLED,
                settings.exitWarningVibrationEnabled,
            )
            .putBoolean(
                KEY_USAGE_MILESTONE_REMINDER_ENABLED,
                settings.usageMilestoneReminderEnabled,
            )
            .putString(KEY_LANGUAGE_MODE, settings.languageMode.name)
            .putBoolean("global.open_usage_tip_enabled", settings.openUsageTipEnabled)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putString(KEY_THEME_COLOR, settings.themeColor.name)
            .putBoolean(KEY_TIME_QUOTES_ENABLED, settings.timeQuotesEnabled)
            .putBoolean(
                KEY_BUILT_IN_TIME_QUOTES_ENABLED,
                settings.builtInTimeQuotesEnabled,
            )
            .putString(
                KEY_CUSTOM_TIME_QUOTES,
                TimeQuotePolicy.encode(settings.customTimeQuotes),
            )
            .putBoolean(
                KEY_AUTOMATIC_UPDATE_CHECK_ENABLED,
                settings.automaticUpdateCheckEnabled,
            )
            .putString(KEY_PROTECTION_MODE, settings.protectionMode.name)
            .putLong(KEY_PROTECTION_MODE_GENERATION, protectionModeGeneration)
            // Keep the previous booleans mirrored for downgrade compatibility. The enum remains
            // authoritative and prevents contradictory combinations in current code.
            .putBoolean(KEY_NON_ROOT_PROTECTION_ENABLED, settings.protectionMode.usesNonRoot)
            .putString(
                KEY_NON_ROOT_COMPATIBILITY_MODE,
                settings.nonRootCompatibilityMode.name,
            )
            .putBoolean(
                KEY_SHIZUKU_ENHANCEMENT_ENABLED,
                settings.protectionMode == ProtectionMode.ACCESSIBILITY &&
                    settings.accessibilityForceStopEnhancement == ForceStopEnhancement.SHIZUKU,
            )
            .putBoolean(KEY_EXTENSION_ENABLED, settings.extensionEnabled)
            .putLong(
                KEY_EXTENSION_SECONDS,
                settings.extensionSeconds.coerceIn(MIN_EXTENSION_SECONDS, MAX_EXTENSION_SECONDS),
            )
            .putLong(
                KEY_EXTENSION_DAILY_LIMIT,
                ExtensionQuotaPolicy.normalizeDailyLimit(settings.extensionDailyLimit).toLong(),
            )
            .putLong(KEY_EXTENSION_SESSION_LIMIT, ExtensionQuotaPolicy.normalizeSessionLimit(settings.extensionSessionLimit).toLong())
            .putLong(
                KEY_EXTENSION_FREE_DAILY_LIMIT,
                ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT.toLong(),
            )
            .putBoolean(KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
            .putBoolean(KEY_LAUNCHER_ICON_HIDDEN, settings.launcherIconHidden)
            .putBoolean(KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
            .putString(KEY_LIMIT_ENFORCEMENT_MODE, settings.limitEnforcementMode.name)
            .commit()
        if (persisted) {
            devicePreferences.edit()
                .putBoolean(
                    KEY_XPOSED_ROOT_ENHANCEMENT_ENABLED,
                    settings.xposedRootEnhancementEnabled,
                )
                .putString(
                    KEY_ACCESSIBILITY_FORCE_STOP_ENHANCEMENT,
                    settings.accessibilityForceStopEnhancement.name,
                )
                // Keep the legacy value truthful for a downgrade without making it authoritative.
                .putBoolean(
                    KEY_ROOT_ENHANCEMENT_ENABLED,
                    if (settings.protectionMode == ProtectionMode.XPOSED) {
                        settings.xposedRootEnhancementEnabled
                    } else {
                        settings.accessibilityForceStopEnhancement == ForceStopEnhancement.ROOT
                    },
                )
                .commit()
            makePreferencesReadable()
        }
        return persisted
    }

    private fun readProtectionMode(): ProtectionMode = ProtectionModePolicy.parse(
        storedValue = prefs.getString(KEY_PROTECTION_MODE, null),
        legacyNonRootEnabled = prefs.getBoolean(KEY_NON_ROOT_PROTECTION_ENABLED, false),
        legacyShizukuEnabled = prefs.getBoolean(KEY_SHIZUKU_ENHANCEMENT_ENABLED, false),
    )

    private fun writeRule(
        editor: SharedPreferences.Editor,
        rule: AppRule,
        version: Long,
    ) {
        val prefix = prefix(rule.packageName)
        val windows = rule.scheduleWindows.filter(ScheduleWindow::isValid).take(ScheduleCodec.MAX_WINDOWS)
        val scheduleEnabled = rule.scheduleEnabled && windows.isNotEmpty()
        val cooldownEnabled = rule.cooldownEnabled &&
            CooldownPolicy.canEnable(rule.dailyEnabled, rule.perLaunchEnabled)
        editor
            .putBoolean("${prefix}enabled", rule.enabled && (rule.dailyEnabled || rule.perLaunchEnabled || scheduleEnabled))
            .putBoolean("${prefix}session_planning_enabled", rule.sessionPlanningEnabled)
            .putBoolean("${prefix}daily_enabled", rule.dailyEnabled)
            .putLong("${prefix}daily_limit_seconds", rule.dailyLimitSeconds)
            .putBoolean("${prefix}per_launch_enabled", rule.perLaunchEnabled)
            .putLong("${prefix}per_launch_limit_seconds", rule.perLaunchLimitSeconds)
            .putBoolean("${prefix}schedule_enabled", scheduleEnabled)
            .putString("${prefix}schedule_mode", rule.scheduleMode.name)
            .putString("${prefix}schedule_windows", ScheduleCodec.encode(windows))
            .putBoolean("${prefix}cooldown_enabled", cooldownEnabled)
            .putLong("${prefix}cooldown_seconds", rule.cooldownSeconds)
            .putLong("${prefix}limit_seconds", if (rule.dailyEnabled) rule.dailyLimitSeconds else rule.perLaunchLimitSeconds)
            .putString("${prefix}mode", if (rule.dailyEnabled) RuleMode.DAILY.name else RuleMode.PER_LAUNCH.name)
            .putLong("${prefix}version", version)
    }

    private fun writeGroup(
        editor: SharedPreferences.Editor,
        group: AppGroup,
        version: Long,
    ) {
        val prefix = groupPrefix(group.id)
        val windows = group.scheduleWindows.filter(ScheduleWindow::isValid).take(ScheduleCodec.MAX_WINDOWS)
        val scheduleEnabled = group.scheduleEnabled && windows.isNotEmpty()
        val cooldownEnabled = group.cooldownEnabled &&
            CooldownPolicy.canEnable(group.dailyEnabled, group.perLaunchEnabled)
        val effectiveEnabled = group.enabled && group.packageNames.isNotEmpty() &&
            (group.dailyEnabled || group.perLaunchEnabled || scheduleEnabled)
        editor
            .putString("${prefix}name", group.name.trim().take(MAX_GROUP_NAME_LENGTH))
            .putBoolean("${prefix}enabled", effectiveEnabled)
            .putBoolean("${prefix}daily_enabled", group.dailyEnabled)
            .putLong("${prefix}daily_limit_seconds", group.dailyLimitSeconds)
            .putBoolean("${prefix}per_launch_enabled", group.perLaunchEnabled)
            .putLong("${prefix}per_launch_limit_seconds", group.perLaunchLimitSeconds)
            .putBoolean("${prefix}schedule_enabled", scheduleEnabled)
            .putString("${prefix}schedule_mode", group.scheduleMode.name)
            .putString("${prefix}schedule_windows", ScheduleCodec.encode(windows))
            .putBoolean("${prefix}cooldown_enabled", cooldownEnabled)
            .putLong("${prefix}cooldown_seconds", group.cooldownSeconds)
            .putStringSet("${prefix}packages", group.packageNames)
            .putLong("${prefix}version", version)
        group.packageNames.forEach { packageName ->
            editor
                .putString("$KEY_PACKAGE_GROUP_PREFIX$packageName", group.id)
                .putLong("$KEY_PACKAGE_GROUP_VERSION_PREFIX$packageName", version)
        }
    }

    private fun writePortableAndDeviceSettings(
        editor: SharedPreferences.Editor,
        portable: PortableGlobalSettings,
        device: GlobalSettings,
        protectionModeGeneration: Long,
    ) {
        editor
            .putBoolean(KEY_CHILD_LOCK_ENABLED, device.childLockEnabled)
            .putBoolean(KEY_EXIT_WARNING_ENABLED, portable.exitWarningEnabled)
            .putBoolean(KEY_FULL_SCREEN_EXIT_WARNING_ENABLED, portable.fullScreenExitWarningEnabled)
            .putBoolean(KEY_EXIT_WARNING_VIBRATION_ENABLED, portable.exitWarningVibrationEnabled)
            .putBoolean(
                KEY_USAGE_MILESTONE_REMINDER_ENABLED,
                portable.usageMilestoneReminderEnabled,
            )
            .putBoolean("global.open_usage_tip_enabled", portable.openUsageTipEnabled)
            .putString(KEY_LANGUAGE_MODE, portable.languageMode.name)
            .putString(KEY_THEME_MODE, portable.themeMode.name)
            .putString(KEY_THEME_COLOR, portable.themeColor.name)
            .putBoolean(KEY_TIME_QUOTES_ENABLED, portable.timeQuotesEnabled)
            .putBoolean(KEY_BUILT_IN_TIME_QUOTES_ENABLED, portable.builtInTimeQuotesEnabled)
            .putString(KEY_CUSTOM_TIME_QUOTES, TimeQuotePolicy.encode(portable.customTimeQuotes))
            .putBoolean(KEY_AUTOMATIC_UPDATE_CHECK_ENABLED, portable.automaticUpdateCheckEnabled)
            .putString(KEY_PROTECTION_MODE, device.protectionMode.name)
            .putLong(KEY_PROTECTION_MODE_GENERATION, protectionModeGeneration)
            .putBoolean(KEY_NON_ROOT_PROTECTION_ENABLED, device.protectionMode.usesNonRoot)
            .putString(KEY_NON_ROOT_COMPATIBILITY_MODE, device.nonRootCompatibilityMode.name)
            .putBoolean(
                KEY_SHIZUKU_ENHANCEMENT_ENABLED,
                device.protectionMode == ProtectionMode.ACCESSIBILITY &&
                    device.accessibilityForceStopEnhancement == ForceStopEnhancement.SHIZUKU,
            )
            .putLong(KEY_EXTENSION_SECONDS, portable.extensionSeconds)
            .putBoolean(KEY_EXTENSION_ENABLED, portable.extensionEnabled)
            .putLong(KEY_EXTENSION_DAILY_LIMIT, portable.extensionDailyLimit.toLong())
            .putLong(KEY_EXTENSION_SESSION_LIMIT, portable.extensionSessionLimit.toLong())
            .putLong(
                KEY_EXTENSION_FREE_DAILY_LIMIT,
                ExtensionQuotaPolicy.DEFAULT_FREE_DAILY_LIMIT.toLong(),
            )
            .putBoolean(KEY_DIAGNOSTICS_ENABLED, portable.diagnosticsEnabled)
            .putBoolean(KEY_LAUNCHER_ICON_HIDDEN, device.launcherIconHidden)
            .putBoolean(KEY_USAGE_STATS_ENABLED, portable.usageStatsEnabled)
            .putString(KEY_LIMIT_ENFORCEMENT_MODE, device.limitEnforcementMode.name)
    }

    fun grantRuleAccess(packageName: String): Boolean {
        if (!PackageNamePolicy.isValid(packageName) || packageName == appContext.packageName) {
            return false
        }
        return runCatching {
            appContext.grantUriPermission(
                packageName,
                RuleContract.CONTENT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Re-offers provider access after upgrades, data migration, or a reboot that discarded an
     * older temporary grant. A target process can repeat the visibility bridge handshake after
     * each reboot; Context.grantUriPermission itself is not persistable.
     */
    fun reconcileRuleAccess(): RuleAccessReconciliation {
        val packages = configuredPackages().sorted()
        val granted = packages.filter(::grantRuleAccess).toSet()
        return RuleAccessReconciliation(
            configuredPackages = packages.toSet(),
            grantedPackages = granted,
        )
    }

    private fun makePreferencesReadable() {
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            if (prefs === primaryPrefs) {
                if (MigrationStorageGate.maySynchronizeMirror(appContext)) {
                    syncSharedMirrorLocked(primaryPrefs)
                }
            } else {
                makeLegacyPreferencesReadable()
            }
        }
    }

    internal fun synchronizeAuthoritativeMirrorForMigration(): Boolean =
        synchronized(STORAGE_LIFECYCLE_LOCK) {
            syncSharedMirrorLocked(primaryPrefs)
        }

    private fun syncSharedMirrorLocked(source: SharedPreferences): Boolean {
        val primarySnapshot = source.all
        val sharedSnapshot = runCatching { sharedPrefs.all }.getOrNull()
        if (sharedSnapshot == primarySnapshot) {
            makeLegacyPreferencesReadable()
            return true
        }
        val synchronized = runCatching {
            val editor = sharedPrefs.edit().clear()
            copyPreferences(primarySnapshot, editor)
            editor.commit()
        }.getOrDefault(false)
        if (!synchronized) return false
        makeLegacyPreferencesReadable()
        return true
    }

    private fun makeLegacyPreferencesReadable() {
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

    private fun copyPreferences(
        source: SharedPreferences,
        editor: SharedPreferences.Editor,
    ) = copyPreferences(source.all, editor)

    private fun copyPreferences(
        values: Map<String, *>,
        editor: SharedPreferences.Editor,
    ) {
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(
                    key,
                    value.filterIsInstance<String>().toSet(),
                )
            }
        }
    }

    private fun prefix(packageName: String) = "rule.$packageName."
    private fun groupPrefix(groupId: String) = "group.$groupId."

    private fun removeGroupCooldownRuntime(
        editor: SharedPreferences.Editor,
        prefix: String,
    ) {
        editor
            .remove("${prefix}runtime_cooldown_started_at")
            .remove("${prefix}runtime_cooldown_ends_at")
            .remove("${prefix}runtime_cooldown_incident")
            .remove("${prefix}runtime_cooldown_source_package")
            .remove("${prefix}runtime_cooldown_started_elapsed_at")
            .remove("${prefix}runtime_cooldown_ends_elapsed_at")
            .remove("${prefix}runtime_cooldown_boot_count")
    }

    private fun removeGroupSessionRuntime(
        editor: SharedPreferences.Editor,
        prefix: String,
    ) {
        editor
            .remove("${prefix}runtime_session_group_version")
            .remove("${prefix}runtime_session_boot_count")
            .remove("${prefix}runtime_session_id")
            .remove("${prefix}runtime_session_used_ms")
            .remove("${prefix}runtime_session_owner")
            .remove("${prefix}runtime_session_owner_seen_elapsed_ms")
            .remove("${prefix}runtime_session_inactive_elapsed_ms")
            .remove("${prefix}runtime_session_handled_segments")
    }

    companion object {
        private val GROUP_COOLDOWN_LOCK = Any()
        private val GROUP_SESSION_LOCK = Any()
        private val STORAGE_LIFECYCLE_LOCK = Any()
        private const val STORAGE_LIFECYCLE_PREFS_NAME = "storage_lifecycle"
        private const val KEY_PRIVATE_STORAGE_INITIALIZED = "initialized"
        private const val PRIMARY_PREFS_NAME = "rules_manager"
        private const val KEY_PRIMARY_STORAGE_INITIALIZED = "storage.primary_initialized"
        const val KEY_SHARED_STORAGE_INITIALIZED = "storage.initialized"
        const val KEY_RULESET_GENERATION = "storage.ruleset_generation"
        const val PREFS_NAME = "rules"
        const val KEY_PACKAGES = "configured_packages"

        /** True when this package already had a rule store before the current APK was installed. */
        internal fun hadPriorRuleStorage(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val lifecycle = app.getSharedPreferences(
                STORAGE_LIFECYCLE_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            val primary = app.getSharedPreferences(
                PRIMARY_PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            lifecycle.getBoolean(KEY_PRIVATE_STORAGE_INITIALIZED, false) ||
                primary.getBoolean(KEY_PRIMARY_STORAGE_INITIALIZED, false) ||
                runCatching {
                    app.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_WORLD_READABLE,
                    ).getBoolean(KEY_SHARED_STORAGE_INITIALIZED, false)
                }.getOrDefault(false)
        }.getOrDefault(false)
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
        const val KEY_EXIT_WARNING_VIBRATION_ENABLED =
            "global.exit_warning_vibration_enabled"
        const val KEY_USAGE_MILESTONE_REMINDER_ENABLED =
            "global.usage_milestone_reminder_enabled"
        const val KEY_LANGUAGE_MODE = "global.language_mode"
        const val KEY_THEME_MODE = "global.theme_mode"
        const val KEY_THEME_COLOR = "global.theme_color"
        const val KEY_TIME_QUOTES_ENABLED = "global.time_quotes_enabled"
        const val KEY_BUILT_IN_TIME_QUOTES_ENABLED = "global.built_in_time_quotes_enabled"
        const val KEY_CUSTOM_TIME_QUOTES = "global.custom_time_quotes"
        const val KEY_AUTOMATIC_UPDATE_CHECK_ENABLED =
            "global.automatic_update_check_enabled"
        const val KEY_PROTECTION_MODE = "global.protection_mode"
        const val KEY_CHILD_LOCK_ENABLED = "global.child_lock_enabled"
        const val KEY_PROTECTION_MODE_GENERATION = "global.protection_mode_generation"
        const val KEY_NON_ROOT_PROTECTION_ENABLED =
            "global.non_root_protection_enabled"
        const val KEY_NON_ROOT_COMPATIBILITY_MODE =
            "global.non_root_compatibility_mode"
        const val KEY_SHIZUKU_ENHANCEMENT_ENABLED =
            "global.shizuku_enhancement_enabled"
        const val KEY_EXTENSION_ENABLED = "global.extension_enabled"
        const val KEY_EXTENSION_SECONDS = "global.extension_seconds"
        const val KEY_EXTENSION_DAILY_LIMIT = "global.extension_daily_limit"
        const val KEY_EXTENSION_SESSION_LIMIT = "global.extension_session_limit"
        const val KEY_EXTENSION_FREE_DAILY_LIMIT = "global.extension_free_daily_limit"
        const val KEY_DIAGNOSTICS_ENABLED = "global.diagnostics_enabled"
        const val KEY_LAUNCHER_ICON_HIDDEN = "global.launcher_icon_hidden"
        const val KEY_USAGE_STATS_ENABLED = "global.usage_stats_enabled"
        const val KEY_LIMIT_ENFORCEMENT_MODE = "global.limit_enforcement_mode"
        const val KEY_ROOT_ENHANCEMENT_ENABLED = "device.root_enhancement_enabled"
        const val KEY_XPOSED_ROOT_ENHANCEMENT_ENABLED =
            "device.xposed_root_enhancement_enabled"
        const val KEY_ACCESSIBILITY_FORCE_STOP_ENHANCEMENT =
            "device.accessibility_force_stop_enhancement"
        private const val DEVICE_SETTINGS_PREFS_NAME = "device_settings"
        const val DEFAULT_EXTENSION_SECONDS = 5L * 60L
        const val DEFAULT_COOLDOWN_SECONDS = 5L * 60L
        const val MIN_COOLDOWN_SECONDS = 60L
        const val MAX_COOLDOWN_SECONDS = 24L * 60L * 60L
        const val MIN_EXTENSION_SECONDS = 60L
        const val MAX_EXTENSION_SECONDS = 15L * 60L
        const val DEFAULT_PROTECTION_MODE_GENERATION = 1L
    }

    private data class SharedPreferenceStore(
        val preferences: SharedPreferences,
        val frameworkBacked: Boolean,
    )
}

data class RuleAccessReconciliation(
    val configuredPackages: Set<String>,
    val grantedPackages: Set<String>,
) {
    val failedPackages: Set<String>
        get() = configuredPackages - grantedPackages
}
