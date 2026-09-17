package com.liuml.apptimelimiter.ipc

import android.app.AppOpsManager
import android.content.ContentProvider
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.BreakSessionPolicy
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.core.ParentOverrideDurationPolicy
import com.liuml.apptimelimiter.core.ProtectionExecutionPolicy
import com.liuml.apptimelimiter.core.RuleActivationPolicy
import com.liuml.apptimelimiter.core.RestrictionPagePresentationPolicy
import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import com.liuml.apptimelimiter.core.GroupUsagePolicy
import com.liuml.apptimelimiter.core.SharedCooldownClaimStatus
import com.liuml.apptimelimiter.core.SharedGroupSessionAction
import com.liuml.apptimelimiter.core.SharedGroupSessionPolicy
import com.liuml.apptimelimiter.core.TimeQuotePolicy
import com.liuml.apptimelimiter.core.UsageReportingPolicy
import com.liuml.apptimelimiter.core.UsageMilestonePolicy
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.core.RestrictionExecutionResult
import com.liuml.apptimelimiter.core.RestrictionRequest
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.nonroot.RootExecutor
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.security.ParentAuthStatus
import com.liuml.apptimelimiter.security.ParentAuthStore
import com.liuml.apptimelimiter.security.ControlRuntimeStore
import com.liuml.apptimelimiter.security.ManagerControlDatabase
import com.liuml.apptimelimiter.core.ControlRuntimeState
import com.liuml.apptimelimiter.core.ControlSessionToken
import com.liuml.apptimelimiter.migration.MigrationCoordinator
import java.time.LocalDate
import java.security.SecureRandom
import java.util.concurrent.Executors

class RuleProvider : ContentProvider() {
    private val resumeEvidence by lazy {
        val ctx = requireNotNull(context)
        com.liuml.apptimelimiter.security.ControlResumeEvidence(
            ctx.getSharedPreferences("control_resume_private", Context.MODE_PRIVATE),
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1))
    }
    private val controlRuntime by lazy {
        val ctx = requireNotNull(context)
        ControlRuntimeStore(ManagerControlDatabase.get(ctx),
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1))
    }
    private val openTipTimes = linkedMapOf<String, Long>()
    private var deviceUsageStatsRepository: DeviceUsageStatsRepository? = null
    private val usageRefreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "time-stop-usage-refresh").apply { isDaemon = true }
    }
    private val usageCacheLock = Any()
    private var usageRefreshInFlight = false
    private var lastUsageRefreshAttemptElapsedMillis = Long.MIN_VALUE
    private var usageSnapshot: SystemUsageSnapshot? = null
    private val warningVibrationLock = Any()
    private val lastWarningVibrationByPackage = mutableMapOf<String, Long>()
    private val breakSessionLock = Any()
    private val restrictionUiLock = Any()
    private val rewardedAdPendingLock = Any()
    private val rootForceStopLock = Any()
    private val rootForceStopExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "time-stop-root-force-stop").apply { isDaemon = true }
    }
    private val secureRandom = SecureRandom()

    private fun runtimeToken(repository: RuleRepository, pkg: String, session: String): ControlSessionToken {
        val settings = repository.getGlobalSettings()
        val group = repository.groupForPackage(pkg)
        return ControlSessionToken(pkg, group?.id.orEmpty(), session, repository.getRule(pkg).version,
            group?.version ?: 0L, settings.protectionMode, settings.protectionModeGeneration, 0L)
    }

    private fun hasForegroundEvidence(ctx: Context, pkg: String, ownRequest: Boolean, request: Bundle?): Boolean {
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        val session = request?.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty()
        val cleared = Binder.clearCallingIdentity()
        return try {
            if (ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true ||
                ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false) false
            else if (ownRequest) com.liuml.apptimelimiter.security.ControlResumeEvidence.accessibilityMatches(pkg, session, SystemClock.elapsedRealtime())
            else resumeEvidence.matches(session, pkg, callerUid, callerPid,
                request?.getString(RuleContract.KEY_CONTROL_RESUME_CAPABILITY).orEmpty())
        } finally { Binder.restoreCallingIdentity(cleared) }
    }

    override fun onCreate(): Boolean {
        // Providers are created before Application.onCreate: explicitly establish migration first.
        context?.let { runCatching { initializeControlAuthority(it) } }
        return true
    }

    private fun initializeControlAuthority(ctx: Context): Boolean {
        val migration = MigrationCoordinator.get(ctx)
        if (migration.state == com.liuml.apptimelimiter.migration.MigrationState.Checking) migration.initialize()
        if (BuildConfig.MODERN_XPOSED_ENABLED && !migration.canInitializeRepositories()) return false
        ParentAuthStore.initialize(ctx)
        if (deviceUsageStatsRepository == null) deviceUsageStatsRepository = DeviceUsageStatsRepository(ctx)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return runCatching {
            if (!initializeControlAuthority(requireNotNull(context))) return denied("migration_not_ready")
            if (method in atomicControlMethods) {
                ManagerControlDatabase.get(requireNotNull(context)).refreshDiagnosticsSetting()
                ParentAuthStore.atomicOperation({ result: Bundle? -> result?.getBoolean(RuleContract.KEY_OK, false) == true }) {
                    callInternal(method, arg, extras)
                }
            } else callInternal(method, arg, extras)
        }.getOrElse { error ->
            context?.let { appContext ->
                runCatching {
                    DiagnosticsRepository(appContext).append(
                        level = "ERROR",
                        packageName = arg.orEmpty().take(160),
                        event = "IPC_CALL_FAILED",
                        message = "method=${method.take(80)}, exception=${error.javaClass.simpleName}",
                    )
                }
            }
            denied("provider_exception")
        }
    }

    private val atomicControlMethods = setOf(
        RuleContract.METHOD_CREATE_PARENT_AUTH_CHALLENGE, RuleContract.METHOD_CONSUME_PARENT_AUTH_CHALLENGE,
        RuleContract.METHOD_COMPLETE_PARENT_AUTH_CHALLENGE, RuleContract.METHOD_MARK_PARENT_AUTH_VERIFIED_FOR_AD,
        RuleContract.METHOD_MARK_PARENT_AUTH_AD_REWARDED, RuleContract.METHOD_GET_PARENT_AUTH_STATUS,
        RuleContract.METHOD_HAS_PARENT_OVERRIDE, RuleContract.METHOD_ACTIVATE_PARENT_OVERRIDE,
        RuleContract.METHOD_REVOKE_PARENT_OVERRIDE, RuleContract.METHOD_CLAIM_RESTRICTION_UI,
        RuleContract.METHOD_ENTER_CONTROL_RESTRICTION, RuleContract.METHOD_FINISH_CONTROL_RESTRICTION,
        RuleContract.METHOD_TRANSITION_CONTROL_RUNTIME, RuleContract.METHOD_RELEASE_RESTRICTION_UI,
    )

    private fun callInternal(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context ?: return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
        if (
            BuildConfig.MODERN_XPOSED_ENABLED &&
            !MigrationCoordinator.get(appContext).canInitializeRepositories()
        ) {
            return denied("migration_not_ready")
        }
        val ruleRepository = RuleRepository(appContext)
        return when (method) {
            RuleContract.METHOD_ENSURE_RULE_ACCESS -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (!isCallerAllowed(packageName)) return denied("caller_mismatch")
                if (!isConfiguredPackage(ruleRepository, packageName)) {
                    return denied("rule_not_configured")
                }
                val identity = Binder.clearCallingIdentity()
                val granted = try {
                    ruleRepository.grantRuleAccess(packageName)
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
                if (!granted) return denied("grant_failed")
                val settings = ruleRepository.getGlobalSettings()
                diagnosticParentAuth(
                    appContext,
                    settings.diagnosticsEnabled,
                    packageName,
                    "RULE_PROVIDER_ACCESS_REPAIRED",
                    "temporary_grant=true",
                )
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
            }
            RuleContract.METHOD_GET_RULE -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                val rule = ruleRepository.getRule(packageName)
                val assignedGroup = ruleRepository.groupForPackage(packageName)
                val group = assignedGroup
                    ?.takeIf { it.enabled && it.packageNames.isNotEmpty() }
                val personalRuleActive = assignedGroup == null
                val settings = ruleRepository.getGlobalSettings()
                val trustedNonRootRequest =
                    Binder.getCallingUid() == Process.myUid() &&
                        extras?.getBoolean(
                            RuleContract.KEY_REQUEST_NON_ROOT_RULE_SNAPSHOT,
                            false,
                        ) == true
                val ruleSnapshotMayExecute = ProtectionExecutionPolicy.ruleSnapshotMayExecute(
                    mode = settings.protectionMode,
                    trustedNonRootRequest = trustedNonRootRequest,
                )
                val expiredGroupCooldown = assignedGroup?.let {
                    ruleRepository.consumeExpiredGroupCooldown(it.id)
                }
                if (expiredGroupCooldown != null && settings.diagnosticsEnabled) {
                    DiagnosticsRepository(appContext).append(
                        level = "INFO",
                        packageName = expiredGroupCooldown.sourcePackage.ifBlank { packageName },
                        event = "GROUP_COOLDOWN_EXPIRED",
                        message = "group=${assignedGroup?.id}, incident=${expiredGroupCooldown.incidentId}, endedAt=${expiredGroupCooldown.endsAtMillis}",
                    )
                }
                val systemUsageRepository = deviceUsageStatsRepository
                    ?: DeviceUsageStatsRepository(appContext).also {
                        deviceUsageStatsRepository = it
                    }
                val trackedPackages = buildSet {
                    if (personalRuleActive && rule.dailyEnabled) add(packageName)
                    if (group?.dailyEnabled == true) addAll(group.packageNames)
                }
                val hasUsageAccess = trackedPackages.isNotEmpty() && systemUsageRepository.hasUsageAccess()
                val usageLookup = if (hasUsageAccess) {
                    lookupSystemUsage(
                        repository = ruleRepository,
                        usageRepository = systemUsageRepository,
                        requiredPackages = trackedPackages,
                    )
                } else {
                    SystemUsageLookup.EMPTY
                }
                val systemTodayUsedMillis = if (
                    personalRuleActive &&
                    rule.dailyEnabled &&
                    usageLookup.available
                ) {
                    usageLookup.durations[packageName] ?: 0L
                } else {
                    -1L
                }
                val groupTodayUsedMillis = group?.takeIf { it.dailyEnabled }?.let { activeGroup ->
                    val moduleDurations = UsageStatsRepository(appContext)
                        .summariesToday(activeGroup.packageNames)
                        .associate { it.packageName to it.durationMillis }
                    GroupUsagePolicy.authoritativeTotalMillis(
                        packageNames = activeGroup.packageNames,
                        systemDurations = usageLookup.durations,
                        moduleDurations = moduleDurations,
                    )
                } ?: -1L
                val groupMeasuredAtElapsedMillis = usageLookup.measuredAtElapsedMillis
                    .takeIf { usageLookup.available }
                    ?: SystemClock.elapsedRealtime()
                val groupCooldownRecord = group
                    ?.takeIf { it.cooldownEnabled }
                    ?.let { ruleRepository.getGroupCooldownRecord(it.id) }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_PROTECTION_MODE, settings.protectionMode.name)
                    putLong(
                        RuleContract.KEY_PROTECTION_MODE_GENERATION,
                        settings.protectionModeGeneration,
                    )
                    putBoolean(
                        RuleContract.KEY_ENABLED,
                        ruleSnapshotMayExecute &&
                            if (personalRuleActive) rule.enabled else group != null,
                    )
                    putBoolean(
                        RuleContract.KEY_SESSION_PLANNING_ENABLED,
                        ruleSnapshotMayExecute &&
                            personalRuleActive && rule.sessionPlanningEnabled,
                    )
                    putBoolean(
                        RuleContract.KEY_DAILY_ENABLED,
                        ruleSnapshotMayExecute &&
                            personalRuleActive && rule.dailyEnabled,
                    )
                    putLong(RuleContract.KEY_DAILY_LIMIT_SECONDS, rule.dailyLimitSeconds)
                    putBoolean(
                        RuleContract.KEY_PER_LAUNCH_ENABLED,
                        ruleSnapshotMayExecute &&
                            personalRuleActive && rule.perLaunchEnabled,
                    )
                    putLong(RuleContract.KEY_PER_LAUNCH_LIMIT_SECONDS, rule.perLaunchLimitSeconds)
                    putBoolean(
                        RuleContract.KEY_SCHEDULE_ENABLED,
                        ruleSnapshotMayExecute &&
                            personalRuleActive && rule.scheduleEnabled,
                    )
                    putString(RuleContract.KEY_SCHEDULE_MODE, rule.scheduleMode.name)
                    putString(
                        RuleContract.KEY_SCHEDULE_WINDOWS,
                        ScheduleCodec.encode(rule.scheduleWindows),
                    )
                    putBoolean(
                        RuleContract.KEY_COOLDOWN_ENABLED,
                        ruleSnapshotMayExecute &&
                            personalRuleActive && rule.cooldownEnabled,
                    )
                    putLong(RuleContract.KEY_COOLDOWN_SECONDS, rule.cooldownSeconds)
                    putLong(RuleContract.KEY_VERSION, rule.version)
                    putLong(
                        RuleContract.KEY_RULESET_GENERATION,
                        ruleRepository.rulesetGeneration(),
                    )
                    putBoolean(RuleContract.KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
                    putBoolean(
                        RuleContract.KEY_USAGE_MILESTONE_REMINDER_ENABLED,
                        settings.usageMilestoneReminderEnabled,
                    )
                    putBoolean(
                        RuleContract.KEY_CHILD_LOCK_ENABLED,
                        settings.childLockEnabled && ChildLockRepository(appContext).isEnabled(),
                    )
                    putBoolean(
                        RuleContract.KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
                        settings.fullScreenExitWarningEnabled,
                    )
                    putBoolean(
                        RuleContract.KEY_EXIT_WARNING_VIBRATION_ENABLED,
                        settings.exitWarningVibrationEnabled,
                    )
                    putString(RuleContract.KEY_LANGUAGE_MODE, settings.languageMode.name)
                    putString(RuleContract.KEY_THEME_MODE, settings.themeMode.name)
                    putString(RuleContract.KEY_THEME_COLOR, settings.themeColor.name)
                    putBoolean(
                        RuleContract.KEY_TIME_QUOTES_ENABLED,
                        settings.timeQuotesEnabled,
                    )
                    putBoolean(
                        RuleContract.KEY_BUILT_IN_TIME_QUOTES_ENABLED,
                        settings.builtInTimeQuotesEnabled,
                    )
                    putString(
                        RuleContract.KEY_CUSTOM_TIME_QUOTES,
                        TimeQuotePolicy.encode(settings.customTimeQuotes),
                    )
                    putBoolean(RuleContract.KEY_EXTENSION_ENABLED, settings.extensionEnabled)
                    putLong(RuleContract.KEY_EXTENSION_SECONDS, settings.extensionSeconds)
                    putInt(RuleContract.KEY_EXTENSION_DAILY_LIMIT, settings.extensionDailyLimit)
                    putInt(RuleContract.KEY_EXTENSION_SESSION_LIMIT, settings.extensionSessionLimit)
                    putInt(RuleContract.KEY_EXTENSION_FREE_DAILY_LIMIT, settings.extensionFreeDailyLimit)
                    putInt(
                        RuleContract.KEY_EXTENSION_REMAINING_COUNT,
                        ruleRepository.extensionRemainingCount(packageName, LocalDate.now().toString()),
                    )
                    putBoolean(RuleContract.KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
                    putBoolean(RuleContract.KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
                    putString(
                        RuleContract.KEY_LIMIT_ENFORCEMENT_MODE,
                        settings.limitEnforcementMode.name,
                    )
                    // Device-local setting. It is returned only through the caller-validated
                    // provider response and is never written to the world-readable rules mirror.
                    putBoolean(
                        RuleContract.KEY_ROOT_ENHANCEMENT_ENABLED,
                        settings.protectionMode == ProtectionMode.XPOSED &&
                            settings.xposedRootEnhancementEnabled,
                    )
                    putLong(RuleContract.KEY_SYSTEM_TODAY_USED_MS, systemTodayUsedMillis)
                    putLong(
                        RuleContract.KEY_SYSTEM_USAGE_MEASURED_AT_ELAPSED_MS,
                        usageLookup.measuredAtElapsedMillis,
                    )
                    putBoolean(RuleContract.KEY_SYSTEM_USAGE_PENDING, usageLookup.refreshPending)
                    putBoolean(
                        RuleContract.KEY_GROUP_ENABLED,
                        ruleSnapshotMayExecute &&
                            group != null,
                    )
                    putString(RuleContract.KEY_GROUP_ID, assignedGroup?.id.orEmpty())
                    putString(RuleContract.KEY_GROUP_NAME, assignedGroup?.name.orEmpty())
                    putBoolean(
                        RuleContract.KEY_GROUP_DAILY_ENABLED,
                        ruleSnapshotMayExecute && group?.dailyEnabled == true,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_DAILY_LIMIT_SECONDS,
                        group?.dailyLimitSeconds ?: RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS,
                    )
                    putLong(RuleContract.KEY_GROUP_TODAY_USED_MS, groupTodayUsedMillis)
                    putBoolean(
                        RuleContract.KEY_GROUP_PER_LAUNCH_ENABLED,
                        ruleSnapshotMayExecute && group?.perLaunchEnabled == true,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_PER_LAUNCH_LIMIT_SECONDS,
                        group?.perLaunchLimitSeconds ?: RuleRepository.DEFAULT_LIMIT_SECONDS,
                    )
                    putBoolean(
                        RuleContract.KEY_GROUP_SCHEDULE_ENABLED,
                        ruleSnapshotMayExecute && group?.scheduleEnabled == true,
                    )
                    putString(
                        RuleContract.KEY_GROUP_SCHEDULE_MODE,
                        group?.scheduleMode?.name.orEmpty(),
                    )
                    putString(
                        RuleContract.KEY_GROUP_SCHEDULE_WINDOWS,
                        ScheduleCodec.encode(group?.scheduleWindows.orEmpty()),
                    )
                    putBoolean(
                        RuleContract.KEY_GROUP_COOLDOWN_ENABLED,
                        ruleSnapshotMayExecute && group?.cooldownEnabled == true,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_COOLDOWN_SECONDS,
                        group?.cooldownSeconds ?: RuleRepository.DEFAULT_COOLDOWN_SECONDS,
                    )
                    putLong(RuleContract.KEY_GROUP_VERSION, group?.version ?: 0L)
                    putString(RuleContract.KEY_GROUP_DAY_TOKEN, LocalDate.now().toString())
                    putLong(
                        RuleContract.KEY_GROUP_MEASURED_AT_ELAPSED_MS,
                        groupMeasuredAtElapsedMillis,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_COOLDOWN_STARTED_AT_MS,
                        groupCooldownRecord?.startedAtMillis ?: 0L,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS,
                        groupCooldownRecord?.endsAtMillis ?: 0L,
                    )
                    putLong("group_cooldown_ends_elapsed_ms", groupCooldownRecord?.endsAtElapsedMillis ?: 0L)
                    putInt("group_cooldown_boot_count", groupCooldownRecord?.bootCount ?: -1)
                    putString(
                        RuleContract.KEY_GROUP_COOLDOWN_INCIDENT_ID,
                        groupCooldownRecord?.incidentId.orEmpty(),
                    )
                    putString(
                        RuleContract.KEY_GROUP_COOLDOWN_SOURCE_PACKAGE,
                        groupCooldownRecord?.sourcePackage.orEmpty(),
                    )
                }
            }

            RuleContract.METHOD_CLAIM_GROUP_COOLDOWN -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                if (
                    !ProtectionExecutionPolicy.acceptHookSideEffect(
                        ruleRepository.getGlobalSettings().protectionMode,
                    )
                ) {
                    return denied()
                }
                val group = ruleRepository.groupForPackage(packageName)
                    ?.takeIf { it.enabled && packageName in it.packageNames }
                    ?: return denied()
                val requestedGroupId = extras?.getString(RuleContract.KEY_GROUP_ID).orEmpty()
                val incidentId = extras?.getString(RuleContract.KEY_INCIDENT_ID)
                    .orEmpty()
                    .take(MAX_INCIDENT_ID_LENGTH)
                if (
                    requestedGroupId != group.id ||
                    incidentId.isBlank() ||
                    incidentId.any { it == '\n' || it == '\r' }
                ) return denied()
                val nowMillis = System.currentTimeMillis()
                val nowElapsedMillis = SystemClock.elapsedRealtime()
                val occurredAtMillis = extras?.getLong(
                    RuleContract.KEY_INCIDENT_OCCURRED_AT_MS,
                    nowMillis,
                ) ?: nowMillis
                val claim = runCatching {
                    ruleRepository.claimGroupCooldown(
                        groupId = group.id,
                        incidentId = incidentId,
                        sourcePackage = packageName,
                        occurredAtMillis = occurredAtMillis,
                        durationMillis = if (group.cooldownEnabled) {
                            group.cooldownSeconds.coerceIn(
                                RuleRepository.MIN_COOLDOWN_SECONDS,
                                RuleRepository.MAX_COOLDOWN_SECONDS,
                            ) * 1000L
                        } else {
                            0L
                        },
                        nowMillis = nowMillis,
                    )
                }.getOrNull() ?: return denied()
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putBoolean(RuleContract.KEY_INCIDENT_NEW, claim.isNewIncident)
                    putBoolean(
                        RuleContract.KEY_GROUP_COOLDOWN_STARTED,
                        claim.cooldownStarted,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_COOLDOWN_STARTED_AT_MS,
                        claim.record.startedAtMillis,
                    )
                    putLong(
                        RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS,
                        claim.record.endsAtMillis,
                    )
                    putLong("group_cooldown_ends_elapsed_ms", claim.record.endsAtElapsedMillis)
                    putInt("group_cooldown_boot_count", claim.record.bootCount)
                    putString(
                        RuleContract.KEY_GROUP_COOLDOWN_INCIDENT_ID,
                        claim.record.incidentId,
                    )
                    putString(
                        RuleContract.KEY_GROUP_COOLDOWN_SOURCE_PACKAGE,
                        claim.record.sourcePackage,
                    )
                    putString(
                        RuleContract.KEY_EVENT,
                        when (claim.status) {
                            SharedCooldownClaimStatus.STARTED -> "GROUP_COOLDOWN_STARTED"
                            SharedCooldownClaimStatus.ABSORBED_BY_ACTIVE ->
                                "GROUP_COOLDOWN_REUSED"
                            SharedCooldownClaimStatus.ALREADY_HANDLED ->
                                "QUOTA_INCIDENT_DUPLICATE"
                            SharedCooldownClaimStatus.HANDLED_WITHOUT_COOLDOWN ->
                                "GROUP_QUOTA_INCIDENT_RECORDED"
                        },
                    )
                }
            }

            RuleContract.METHOD_CLAIM_EXTENSION -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (!isCallerAllowed(packageName)) return denied("caller_mismatch")
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied("rule_not_configured")
                if (!ProtectionExecutionPolicy.acceptHookSideEffect(
                        ruleRepository.getGlobalSettings().protectionMode,
                    )
                ) return denied("inactive_protection_mode")
                if (!ruleRepository.getGlobalSettings().extensionEnabled) {
                    return Bundle().apply {
                        putBoolean(RuleContract.KEY_OK, true)
                        putBoolean(RuleContract.KEY_EXTENSION_ALLOWED, false)
                        putBoolean(RuleContract.KEY_EXTENSION_REQUIRES_AD, false)
                        putInt(RuleContract.KEY_EXTENSION_REMAINING_COUNT, 0)
                    }
                }
                val dayToken = extras?.getString(RuleContract.KEY_DAY_TOKEN).orEmpty()
                val sessionId = extras?.getString(RuleContract.KEY_EXTENSION_SESSION_ID).orEmpty()
                if (dayToken.isBlank() || dayToken.length > 32 || dayToken.any { it == '\n' || it == '\r' } ||
                    sessionId.isBlank() || sessionId.length > 160 || sessionId.any { it == '\n' || it == '\r' }) {
                    return denied("invalid_day_token")
                }
                val decision = runCatching {
                    ruleRepository.claimExtension(packageName, dayToken, sessionId)
                }.getOrNull() ?: return denied("extension_quota_persist_failed")
                if (ruleRepository.getGlobalSettings().diagnosticsEnabled) {
                    DiagnosticsRepository(appContext).append(
                        level = "INFO",
                        packageName = packageName,
                        event = "GROUP_DELAY_COUNT_UPDATED",
                        message = "day=$dayToken allowed=${decision.allowed} remaining=${decision.remainingCount}",
                    )
                }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putBoolean(RuleContract.KEY_EXTENSION_ALLOWED, decision.allowed)
                    putBoolean(RuleContract.KEY_EXTENSION_REQUIRES_AD, decision.requiresAd)
                    putInt(RuleContract.KEY_EXTENSION_REMAINING_COUNT, decision.remainingCount)
                }
            }

            RuleContract.METHOD_CHECK_REWARDED_AD_ELIGIBILITY -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (Binder.getCallingUid() != Process.myUid()) return denied("manager_only")
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied("rule_not_configured")
                val request = extras ?: return denied("missing_ad_request")
                val tx = request.getString(RuleContract.KEY_AD_TRANSACTION_ID).orEmpty()
                val session = request.getString(RuleContract.KEY_AD_SESSION_ID).orEmpty()
                if (tx.isBlank() || tx.length > 100 || session.isBlank() || session.length > 160) {
                    return denied("invalid_ad_identity")
                }
                val rule = ruleRepository.getRule(packageName)
                val currentGroupVersion = ruleRepository.groupForPackage(packageName)?.version ?: 0L
                val ruleVersion = request.getLong(RuleContract.KEY_AD_RULE_VERSION, Long.MIN_VALUE)
                val groupVersion = request.getLong(RuleContract.KEY_AD_GROUP_VERSION, Long.MIN_VALUE)
                val modeGeneration = request.getLong(RuleContract.KEY_AD_MODE_GENERATION, Long.MIN_VALUE)
                if (rule.version != ruleVersion ||
                    currentGroupVersion != groupVersion ||
                    ruleRepository.getGlobalSettings().protectionModeGeneration != modeGeneration
                ) return denied("stale_ad_request")
                if (!ruleRepository.getGlobalSettings().extensionEnabled) return denied("extension_disabled")
                val settings = ruleRepository.getGlobalSettings()
                val configuredRewardMillis = settings.extensionSeconds
                    .coerceIn(RuleRepository.MIN_LIMIT_SECONDS, RuleRepository.MAX_LIMIT_SECONDS) * 1_000L
                val groupId = ruleRepository.groupForPackage(packageName)?.id.orEmpty()
                val dailyIdentity = if (groupId.isBlank()) "package:$packageName" else "group:$groupId"
                val sessionIdentity = "$dailyIdentity:session:$session"
                val extensionDecision = runCatching {
                    ruleRepository.previewRewardedExtension(packageName, LocalDate.now().toString(), session)
                }.getOrNull() ?: return denied("rewarded_extension_quota_unavailable")
                if (!extensionDecision.allowed) {
                    val reason = if (extensionDecision.remainingCount <= 0) {
                        "extension_daily_limit_reached"
                    } else {
                        "extension_session_limit_reached"
                    }
                    return Bundle().apply {
                        putBoolean(RuleContract.KEY_OK, false)
                        putString(RuleContract.KEY_MESSAGE, reason)
                        putInt(RuleContract.KEY_EXTENSION_REMAINING_COUNT, extensionDecision.remainingCount)
                    }
                }
                val adDecision = runCatching {
                    com.liuml.apptimelimiter.ads.RewardedAdStateRepository(appContext).previewClaim(
                        dailyIdentity = dailyIdentity,
                        sessionIdentity = sessionIdentity,
                        dayToken = LocalDate.now().toString(),
                        configuredExtensionMillis = configuredRewardMillis,
                        ruleRemainingMillis = configuredRewardMillis,
                        transactionId = tx,
                    )
                }.getOrNull() ?: return denied("rewarded_ad_quota_unavailable")
                if (!adDecision.allowed) {
                    return Bundle().apply {
                        putBoolean(RuleContract.KEY_OK, false)
                        putString(RuleContract.KEY_MESSAGE, "rewarded_ad_quota_reached")
                        putInt(RuleContract.KEY_AD_DAILY_REMAINING_COUNT, adDecision.remainingDailyCount)
                        putLong(RuleContract.KEY_AD_DAILY_REMAINING_MILLIS, adDecision.remainingDailyMillis)
                    }
                }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putLong(RuleContract.KEY_AD_REWARD_MILLIS, adDecision.rewardMillis)
                    putInt(RuleContract.KEY_EXTENSION_REMAINING_COUNT, extensionDecision.remainingCount)
                    putInt(RuleContract.KEY_AD_DAILY_REMAINING_COUNT, adDecision.remainingDailyCount)
                    putLong(RuleContract.KEY_AD_DAILY_REMAINING_MILLIS, adDecision.remainingDailyMillis)
                }
            }

            RuleContract.METHOD_CLAIM_REWARDED_AD -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (Binder.getCallingUid() != Process.myUid()) return denied("manager_only")
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied("rule_not_configured")
                val request = extras ?: return denied("missing_ad_request")
                val tx = request.getString(RuleContract.KEY_AD_TRANSACTION_ID).orEmpty()
                val session = request.getString(RuleContract.KEY_AD_SESSION_ID).orEmpty()
                if (tx.isBlank() || tx.length > 100 || session.isBlank() || session.length > 160) {
                    return denied("invalid_ad_identity")
                }
                val rule = ruleRepository.getRule(packageName)
                val currentGroupVersion = ruleRepository.groupForPackage(packageName)?.version ?: 0L
                val ruleVersion = request.getLong(RuleContract.KEY_AD_RULE_VERSION, Long.MIN_VALUE)
                val groupVersion = request.getLong(RuleContract.KEY_AD_GROUP_VERSION, Long.MIN_VALUE)
                val modeGeneration = request.getLong(RuleContract.KEY_AD_MODE_GENERATION, Long.MIN_VALUE)
                if (rule.version != ruleVersion ||
                    currentGroupVersion != groupVersion ||
                    ruleRepository.getGlobalSettings().protectionModeGeneration != modeGeneration
                ) return denied("stale_ad_request")
                if (!ruleRepository.getGlobalSettings().extensionEnabled) {
                    return denied("extension_disabled")
                }
                val controlIdentity = runtimeToken(ruleRepository, packageName, session)
                val controlIncident = request.getString(RuleContract.KEY_INCIDENT_ID).orEmpty()
                if (!controlRuntime.transition(controlIdentity, controlIncident, ControlRuntimeState.OVERRIDE_PENDING,
                        SystemClock.elapsedRealtime())) return denied("stale_ad_control_event")
                val configuredRewardMillis = ruleRepository.getGlobalSettings().extensionSeconds
                    .coerceIn(
                        RuleRepository.MIN_LIMIT_SECONDS,
                        RuleRepository.MAX_LIMIT_SECONDS,
                    ) * 1_000L
                val groupId = ruleRepository.groupForPackage(packageName)?.id.orEmpty()
                val dailyIdentity = if (groupId.isBlank()) "package:$packageName" else "group:$groupId"
                val sessionIdentity = "$dailyIdentity:session:$session"
                val decision = runCatching {
                    com.liuml.apptimelimiter.ads.RewardedAdStateRepository(appContext).claim(
                        dailyIdentity = dailyIdentity,
                        sessionIdentity = sessionIdentity,
                        dayToken = LocalDate.now().toString(),
                        configuredExtensionMillis = configuredRewardMillis,
                        ruleRemainingMillis = configuredRewardMillis,
                        transactionId = tx,
                    )
                }.getOrNull() ?: return denied("ad_state_persist_failed")
                if (!decision.allowed) return Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, false)
                    putInt(RuleContract.KEY_AD_DAILY_REMAINING_COUNT, decision.remainingDailyCount)
                    putLong(RuleContract.KEY_AD_DAILY_REMAINING_MILLIS, decision.remainingDailyMillis)
                }
                if (!putRewardedAdPending(packageName, session, tx, decision.rewardMillis,
                        rule.version, currentGroupVersion, modeGeneration)) {
                    com.liuml.apptimelimiter.ads.RewardedAdStateRepository(appContext).rollbackClaim(
                        dailyIdentity = dailyIdentity,
                        sessionIdentity = sessionIdentity,
                        transactionId = tx,
                        rewardMillis = decision.rewardMillis,
                    )
                    return denied("ad_pending_persist_failed")
                }
                val extensionDecision = runCatching {
                    ruleRepository.claimRewardedExtension(
                        packageName = packageName,
                        dayToken = LocalDate.now().toString(),
                        sessionId = session,
                    )
                }.getOrNull()
                if (extensionDecision?.allowed != true) {
                    removeRewardedAdPending(packageName, session, tx)
                    com.liuml.apptimelimiter.ads.RewardedAdStateRepository(appContext).rollbackClaim(
                        dailyIdentity, sessionIdentity, tx, decision.rewardMillis,
                    )
                    if (extensionDecision == null) return denied("rewarded_extension_quota_persist_failed")
                    return Bundle().apply {
                        putBoolean(RuleContract.KEY_OK, false)
                        putInt(RuleContract.KEY_EXTENSION_REMAINING_COUNT, extensionDecision.remainingCount)
                    }
                }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putLong(RuleContract.KEY_AD_REWARD_MILLIS, decision.rewardMillis)
                    putInt(RuleContract.KEY_AD_DAILY_REMAINING_COUNT, decision.remainingDailyCount)
                    putLong(RuleContract.KEY_AD_DAILY_REMAINING_MILLIS, decision.remainingDailyMillis)
                }
            }

            RuleContract.METHOD_CONSUME_REWARDED_AD -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName) || !isCallerAllowed(packageName)) return denied("caller_mismatch")
                val request = extras ?: return denied("missing_ad_consume_request")
                val session = request.getString(RuleContract.KEY_AD_SESSION_ID).orEmpty()
                val tx = request.getString(RuleContract.KEY_AD_TRANSACTION_ID).orEmpty()
                if (session.isBlank()) return denied("invalid_ad_consume_identity")
                synchronized(rewardedAdPendingLock) {
                    val pending = readRewardedAdPending(packageName, session, tx)
                        ?: return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
                    val rule = ruleRepository.getRule(packageName)
                    if (rule.version != pending.ruleVersion ||
                        (ruleRepository.groupForPackage(packageName)?.version ?: 0L) != pending.groupVersion ||
                        ruleRepository.getGlobalSettings().protectionModeGeneration != pending.modeGeneration) {
                        return denied("stale_ad_reward")
                    }
                    val runtimeIdentity = runtimeToken(ruleRepository, packageName, session)
                    val runtime = controlRuntime.read(runtimeIdentity, SystemClock.elapsedRealtime())
                        ?: return denied("stale_ad_control_session")
                    if (!controlRuntime.transition(runtimeIdentity, runtime.incident, ControlRuntimeState.OVERRIDE_ACTIVE,
                            SystemClock.elapsedRealtime())) return denied("ad_runtime_activation_failed")
                    if (!removeRewardedAdPending(packageName, session, pending.transactionId)) {
                        return denied("ad_reward_consume_failed")
                    }
                    return Bundle().apply {
                        putBoolean(RuleContract.KEY_OK, true)
                        putLong(RuleContract.KEY_AD_REWARD_MILLIS, pending.rewardMillis)
                        putString(RuleContract.KEY_AD_TRANSACTION_ID, pending.transactionId)
                    }
                }
            }

            RuleContract.METHOD_RESET_REWARDED_AD_SESSION -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (!isCallerAllowed(packageName)) return denied("caller_mismatch")
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied("rule_not_configured")
                val session = extras?.getString(RuleContract.KEY_AD_SESSION_ID).orEmpty()
                if (session.isBlank() || session.length > 160 || session.any { it == '\n' || it == '\r' }) {
                    return denied("invalid_ad_session")
                }
                val groupId = ruleRepository.groupForPackage(packageName)?.id.orEmpty()
                val dailyIdentity = if (groupId.isBlank()) "package:$packageName" else "group:$groupId"
                val reset = runCatching {
                    com.liuml.apptimelimiter.ads.RewardedAdStateRepository(appContext).resetSession(
                        "$dailyIdentity:session:$session",
                    )
                }.isSuccess
                if (!reset) return denied("ad_session_reset_failed")
                if (ruleRepository.getGlobalSettings().diagnosticsEnabled) {
                    DiagnosticsRepository(appContext).append(
                        level = "INFO",
                        packageName = packageName,
                        event = "REWARDED_AD_SESSION_RESET",
                        message = "identity=${dailyIdentity.take(80)}",
                    )
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
            }

            RuleContract.METHOD_SYNC_GROUP_PER_LAUNCH_SESSION -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (!isCallerAllowed(packageName)) return denied("caller_mismatch")
                if (
                    !ProtectionExecutionPolicy.acceptHookSideEffect(
                        ruleRepository.getGlobalSettings().protectionMode,
                    )
                ) {
                    return denied("inactive_protection_mode")
                }
                val request = extras ?: return denied("missing_group_session_request")
                val groupId = request.getString(RuleContract.KEY_GROUP_ID).orEmpty()
                val group = ruleRepository.groupForPackage(packageName)
                    ?.takeIf {
                        it.id == groupId && it.enabled && it.perLaunchEnabled &&
                            packageName in it.packageNames
                    }
                    ?: return denied("group_session_not_configured")
                val action = request.getString(RuleContract.KEY_GROUP_SESSION_ACTION)
                    ?.let { runCatching { SharedGroupSessionAction.valueOf(it) }.getOrNull() }
                    ?: return denied("invalid_group_session_action")
                val ownerId = request.getString(RuleContract.KEY_GROUP_SESSION_OWNER_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                val expectedSessionId = request.getString(RuleContract.KEY_GROUP_SESSION_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                val segmentId = request.getString(RuleContract.KEY_GROUP_SESSION_SEGMENT_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                if (
                    ownerId.isBlank() || !ownerId.startsWith("$packageName|") ||
                    ownerId.hasLineBreak() ||
                    expectedSessionId.hasLineBreak() || segmentId.hasLineBreak()
                ) return denied("invalid_group_session_fields")
                val segmentMillis = request.getLong(RuleContract.KEY_DURATION_MS, 0L)
                    .coerceIn(0L, MAX_GROUP_SESSION_SEGMENT_MILLIS)
                val resetGapMillis = if (group.cooldownEnabled) {
                    group.cooldownSeconds.coerceIn(
                        RuleRepository.MIN_COOLDOWN_SECONDS,
                        RuleRepository.MAX_COOLDOWN_SECONDS,
                    ) * 1_000L
                } else {
                    SharedGroupSessionPolicy.DEFAULT_RESET_GAP_MILLIS
                }
                val bootCount = Settings.Global.getInt(
                    appContext.contentResolver,
                    Settings.Global.BOOT_COUNT,
                    -1,
                )
                val update = runCatching {
                    ruleRepository.updateGroupPerLaunchSession(
                        groupId = group.id,
                        action = action,
                        groupVersion = group.version,
                        bootCount = bootCount,
                        ownerId = ownerId,
                        expectedSessionId = expectedSessionId,
                        segmentId = segmentId,
                        segmentMillis = segmentMillis,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        resetGapMillis = resetGapMillis,
                    )
                }.getOrElse { error ->
                    diagnosticParentAuth(
                        appContext,
                        ruleRepository.getGlobalSettings().diagnosticsEnabled,
                        packageName,
                        "GROUP_SESSION_SYNC_FAILED",
                        "action=$action error=${error.javaClass.simpleName}",
                    )
                    return denied("group_session_persist_failed")
                }
                val event = when {
                    update.staleRequest -> "GROUP_SESSION_STALE_UPDATE"
                    update.restarted -> "GROUP_SESSION_STARTED"
                    update.ownerTransferred -> "GROUP_SESSION_HANDOFF"
                    action == SharedGroupSessionAction.LEAVE -> "GROUP_SESSION_PAUSED"
                    else -> "GROUP_SESSION_SYNCED"
                }
                if (
                    update.restarted || update.ownerTransferred || update.staleRequest ||
                    action == SharedGroupSessionAction.LEAVE
                ) {
                    diagnosticParentAuth(
                        appContext,
                        ruleRepository.getGlobalSettings().diagnosticsEnabled,
                        packageName,
                        event,
                        "group=${group.id} session=${update.record.sessionId.take(48)} " +
                            "used=${update.record.usedMillis} action=$action " +
                            "accepted=${update.segmentAccepted}",
                    )
                }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_EVENT, event)
                    putString(RuleContract.KEY_GROUP_SESSION_ID, update.record.sessionId)
                    putLong(RuleContract.KEY_GROUP_SESSION_USED_MS, update.record.usedMillis)
                    putBoolean(RuleContract.KEY_GROUP_SESSION_RESTARTED, update.restarted)
                    putBoolean(
                        RuleContract.KEY_GROUP_SESSION_OWNER_TRANSFERRED,
                        update.ownerTransferred,
                    )
                    putBoolean(
                        RuleContract.KEY_GROUP_SESSION_SEGMENT_ACCEPTED,
                        update.segmentAccepted,
                    )
                    putBoolean(
                        RuleContract.KEY_GROUP_SESSION_STALE_REQUEST,
                        update.staleRequest,
                    )
                }
            }

            RuleContract.METHOD_CREATE_BREAK_SESSION -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                val settings = ruleRepository.getGlobalSettings()
                val nonRootRequest = extras?.getBoolean(
                    RuleContract.KEY_BREAK_SESSION_NON_ROOT,
                    false,
                ) == true
                if (nonRootRequest) {
                    if (
                        Binder.getCallingUid() != Process.myUid() ||
                        !ProtectionExecutionPolicy.nonRootMayExecute(settings.protectionMode)
                    ) return denied()
                } else if (
                    !ProtectionExecutionPolicy.acceptHookSideEffect(settings.protectionMode) ||
                    settings.limitEnforcementMode != LimitEnforcementMode.EXTERNAL_BREAK_PAGE
                ) {
                    return denied()
                }
                val nowMillis = System.currentTimeMillis()
                val nowElapsedMillis = SystemClock.elapsedRealtime()
                val token = generateBreakSessionToken()
                val expiresAtMillis = synchronized(breakSessionLock) {
                    val prefs = appContext.getSharedPreferences(
                        BREAK_SESSION_PREFS,
                        android.content.Context.MODE_PRIVATE,
                    )
                    val updated = BreakSessionPolicy.issue(
                        existing = BreakSessionPolicy.decode(
                            prefs.getString(KEY_BREAK_SESSION_RECORDS, null),
                        ),
                        token = token,
                        targetPackage = packageName,
                        nowMillis = nowMillis,
                        nowElapsedMillis = nowElapsedMillis,
                    )
                    if (
                        !prefs.edit()
                            .putString(
                                KEY_BREAK_SESSION_RECORDS,
                                BreakSessionPolicy.encode(updated),
                            )
                            .commit()
                    ) return denied()
                    updated.lastOrNull { it.token == token }?.expiresAtMillis ?: 0L
                }
                if (expiresAtMillis <= nowMillis) return denied()
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_BREAK_SESSION_TOKEN, token)
                    putLong(
                        RuleContract.KEY_BREAK_SESSION_EXPIRES_AT_MS,
                        expiresAtMillis,
                    )
                }
            }

            RuleContract.METHOD_CONSUME_BREAK_SESSION -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied()
                val packageName = arg.orEmpty()
                val token = extras?.getString(RuleContract.KEY_BREAK_SESSION_TOKEN)
                    .orEmpty()
                    .take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                if (packageName.isBlank() || token.isBlank()) return denied()
                val accepted = synchronized(breakSessionLock) {
                    val prefs = appContext.getSharedPreferences(
                        BREAK_SESSION_PREFS,
                        android.content.Context.MODE_PRIVATE,
                    )
                    val consumed = BreakSessionPolicy.consume(
                        existing = BreakSessionPolicy.decode(
                            prefs.getString(KEY_BREAK_SESSION_RECORDS, null),
                        ),
                        token = token,
                        targetPackage = packageName,
                        nowMillis = System.currentTimeMillis(),
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                    )
                    val persisted = prefs.edit()
                        .putString(
                            KEY_BREAK_SESSION_RECORDS,
                            BreakSessionPolicy.encode(consumed.records),
                        )
                        .commit()
                    consumed.accepted && persisted
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, accepted) }
            }

            RuleContract.METHOD_CLAIM_RESTRICTION_UI -> {
                val packageName = arg.orEmpty()
                if (Binder.getCallingUid() != Process.myUid()) return denied("manager_only")
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied("rule_not_configured")
                val incidentId = extras?.getString(RuleContract.KEY_INCIDENT_ID).orEmpty()
                if (!isValidRestrictionUiIncident(incidentId)) return denied("invalid_incident")
                val session = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty()
                if (session.isBlank() || session.length > MAX_SESSION_ID_LENGTH) return denied("missing_control_session")
                val identity = runtimeToken(ruleRepository, packageName, session)
                if (extras?.getLong(RuleContract.KEY_VERSION, Long.MIN_VALUE) != identity.ruleVersion ||
                    extras?.getLong(RuleContract.KEY_GROUP_VERSION, Long.MIN_VALUE) != identity.groupVersion ||
                    extras?.getLong(RuleContract.KEY_PROTECTION_MODE_GENERATION, Long.MIN_VALUE) != identity.modeGeneration) return denied("stale_control_identity")
                val now = SystemClock.elapsedRealtime()
                val record = controlRuntime.claim(identity, incidentId, now) ?: return denied("restriction_ui_active")
                if (record.state == ControlRuntimeState.LIMIT_CLAIMED &&
                    !controlRuntime.transition(identity, record.incident, ControlRuntimeState.EXECUTING_RESTRICTION, now)) return denied("runtime_persist_failed")
                val current = controlRuntime.read(identity, now) ?: return denied("runtime_unavailable")
                if (current.state == ControlRuntimeState.EXECUTING_RESTRICTION &&
                    !controlRuntime.transition(identity, record.incident, ControlRuntimeState.RESTRICTION_VISIBLE, now)) return denied("runtime_persist_failed")
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_INCIDENT_ID, record.incident)
                    putString(RuleContract.KEY_RESTRICTION_UI_STATE, controlRuntime.read(identity, now)?.state?.name)
                }
            }

            RuleContract.METHOD_REGISTER_CONTROL_RESUME -> {
                val pkg = arg.orEmpty()
                if (!PackageNamePolicy.isValid(pkg) || !isCallerAllowed(pkg) || !isConfiguredPackage(ruleRepository, pkg) ||
                    Binder.getCallingUid() == Process.myUid() || ruleRepository.getGlobalSettings().protectionMode != ProtectionMode.XPOSED) return denied("caller_mismatch")
                val capability = resumeEvidence.register(extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty(),
                    pkg, Binder.getCallingUid(), Binder.getCallingPid(), SystemClock.elapsedRealtime()) ?: return denied("resume_registration_failed")
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true); putString(RuleContract.KEY_CONTROL_RESUME_CAPABILITY, capability) }
            }

            RuleContract.METHOD_ENTER_CONTROL_RESTRICTION -> {
                val pkg = arg.orEmpty()
                if (!PackageNamePolicy.isValid(pkg) || !isCallerAllowed(pkg) || !isConfiguredPackage(ruleRepository, pkg)) return denied("caller_mismatch")
                val request = extras ?: return denied("missing_control_identity")
                val identity = runtimeToken(ruleRepository, pkg, request.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty())
                if (identity.sessionId.length > MAX_SESSION_ID_LENGTH ||
                    request.getLong(RuleContract.KEY_VERSION, Long.MIN_VALUE) != identity.ruleVersion ||
                    request.getLong(RuleContract.KEY_GROUP_VERSION, Long.MIN_VALUE) != identity.groupVersion ||
                    request.getLong(RuleContract.KEY_PROTECTION_MODE_GENERATION, Long.MIN_VALUE) != identity.modeGeneration ||
                    (Binder.getCallingUid() != Process.myUid() && identity.protectionMode != ProtectionMode.XPOSED)) return denied("stale_control_identity")
                val now = SystemClock.elapsedRealtime()
                val overrideIdentity = TemporaryOverrideIdentity(pkg, identity.sessionId, identity.ruleVersion, identity.groupVersion, identity.modeGeneration)
                if (ParentAuthStore.hasAllowance(overrideIdentity, now)) return denied("parent_allowance_exists")
                val record = controlRuntime.claim(identity, request.getString(RuleContract.KEY_INCIDENT_ID).orEmpty(), now,
                    replaceSession = hasForegroundEvidence(appContext, pkg, Binder.getCallingUid() == Process.myUid(), request))
                    ?: return denied("control_session_owned")
                val accepted = record.state == ControlRuntimeState.RESTRICTION_VISIBLE ||
                    controlRuntime.transition(identity, record.incident, ControlRuntimeState.EXECUTING_RESTRICTION, now)
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, accepted)
                    putString(RuleContract.KEY_INCIDENT_ID, record.incident)
                    putString(RuleContract.KEY_CONTROL_RUNTIME_STATE, controlRuntime.read(identity, now)?.state?.name)
                }
            }

            RuleContract.METHOD_FINISH_CONTROL_RESTRICTION -> {
                val pkg = arg.orEmpty()
                if (!PackageNamePolicy.isValid(pkg) || !isCallerAllowed(pkg)) return denied("caller_mismatch")
                val identity = runtimeToken(ruleRepository, pkg, extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty())
                val now = SystemClock.elapsedRealtime()
                val record = controlRuntime.read(identity, now) ?: return denied("control_session_missing")
                if (record.state != ControlRuntimeState.EXECUTING_RESTRICTION) return denied("control_session_not_executing")
                Bundle().apply { putBoolean(RuleContract.KEY_OK,
                    controlRuntime.transition(identity, record.incident, ControlRuntimeState.CANCELLED, now)) }
            }

            RuleContract.METHOD_TRANSITION_CONTROL_RUNTIME -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied("manager_only")
                val pkg = arg.orEmpty()
                if (!isConfiguredPackage(ruleRepository, pkg)) return denied("rule_not_configured")
                val request = extras ?: return denied("missing_control_identity")
                val identity = runtimeToken(ruleRepository, pkg, request.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty())
                if (request.getLong(RuleContract.KEY_VERSION, Long.MIN_VALUE) != identity.ruleVersion ||
                    request.getLong(RuleContract.KEY_GROUP_VERSION, Long.MIN_VALUE) != identity.groupVersion ||
                    request.getLong(RuleContract.KEY_PROTECTION_MODE_GENERATION, Long.MIN_VALUE) != identity.modeGeneration) return denied("stale_control_identity")
                val next = runCatching { ControlRuntimeState.valueOf(request.getString(RuleContract.KEY_CONTROL_RUNTIME_STATE).orEmpty()) }.getOrNull()
                    ?: return denied("invalid_control_state")
                if (next !in setOf(ControlRuntimeState.WAITING_AD, ControlRuntimeState.EXECUTING_RESTRICTION,
                        ControlRuntimeState.RESTRICTION_VISIBLE, ControlRuntimeState.CANCELLED)) return denied("state_not_ui_owned")
                val accepted = controlRuntime.transition(identity, request.getString(RuleContract.KEY_INCIDENT_ID).orEmpty(), next, SystemClock.elapsedRealtime())
                Bundle().apply { putBoolean(RuleContract.KEY_OK, accepted) }
            }

            RuleContract.METHOD_RELEASE_RESTRICTION_UI -> {
                val packageName = arg.orEmpty()
                if (Binder.getCallingUid() != Process.myUid()) return denied("manager_only")
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                val incidentId = extras?.getString(RuleContract.KEY_INCIDENT_ID).orEmpty()
                if (!isValidRestrictionUiIncident(incidentId)) return denied("invalid_incident")
                val identity = runtimeToken(ruleRepository, packageName, extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID).orEmpty())
                val current = controlRuntime.read(identity, SystemClock.elapsedRealtime())
                // Finishing the page after PIN/ad handoff must not cancel a pending activation.
                val released = current?.incident == incidentId && (current.state == ControlRuntimeState.OVERRIDE_PENDING ||
                    current.state == ControlRuntimeState.OVERRIDE_ACTIVE || controlRuntime.transition(identity, incidentId,
                    ControlRuntimeState.CANCELLED, SystemClock.elapsedRealtime()))
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, released)
                    putString(RuleContract.KEY_RESTRICTION_UI_STATE, "CANCELLED")
                }
            }

            RuleContract.METHOD_CREATE_PARENT_AUTH_CHALLENGE -> {
                val packageName = arg.orEmpty()
                if (!PackageNamePolicy.isValid(packageName)) return denied("invalid_package")
                val ownRequest = Binder.getCallingUid() == Process.myUid()
                if (!ownRequest && !isCallerAllowed(packageName)) {
                    return denied("caller_mismatch")
                }
                if (!isConfiguredPackage(ruleRepository, packageName)) {
                    return denied("rule_not_configured")
                }
                val settings = ruleRepository.getGlobalSettings()
                if (!ownRequest && settings.protectionMode.usesNonRoot) {
                    return denied("hook_not_controller")
                }
                if (!settings.childLockEnabled) return denied("child_lock_disabled")
                if (!ChildLockRepository(appContext).isEnabled()) {
                    return denied("child_lock_pin_missing")
                }
                val sessionId = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                val incidentId = extras?.getString(RuleContract.KEY_INCIDENT_ID)
                    .orEmpty().take(MAX_INCIDENT_ID_LENGTH)
                val reason = extras?.getString(RuleContract.KEY_PARENT_AUTH_REASON)
                    .orEmpty().take(MAX_AUTH_REASON_LENGTH)
                if (
                    sessionId.isBlank() || incidentId.isBlank() || reason.isBlank() ||
                    sessionId.hasLineBreak() || incidentId.hasLineBreak() || reason.hasLineBreak()
                ) return denied("invalid_challenge_fields")
                val rule = ruleRepository.getRule(packageName)
                val groupVersion = ruleRepository.groupForPackage(packageName)?.version ?: 0L
                val identity = TemporaryOverrideIdentity(
                    packageName = packageName,
                    processSessionId = sessionId,
                    ruleVersion = rule.version,
                    groupVersion = groupVersion,
                    protectionModeGeneration = settings.protectionModeGeneration,
                )
                val nowMillis = System.currentTimeMillis()
                val token = generateBreakSessionToken()
                val runtimeIdentity = runtimeToken(ruleRepository, packageName, sessionId)
                val runtime = controlRuntime.claim(runtimeIdentity, incidentId, SystemClock.elapsedRealtime())
                    ?: return denied("control_session_owned")
                if (!controlRuntime.transition(runtimeIdentity, runtime.incident,
                        ControlRuntimeState.WAITING_PARENT_AUTH, SystemClock.elapsedRealtime())) return denied("runtime_auth_transition_failed")
                val challenge = ParentAuthStore.issue(
                    token = token,
                    identity = identity,
                    incidentId = runtime.incident,
                    reason = reason,
                    nowMillis = nowMillis,
                )
                diagnosticParentAuth(
                    appContext,
                    settings.diagnosticsEnabled,
                    packageName,
                    "PARENT_AUTH_CHALLENGE_STARTED",
                    "reason=$reason, incident=${incidentId.take(80)}",
                )
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_PARENT_AUTH_TOKEN, token)
                    putLong(RuleContract.KEY_BREAK_SESSION_EXPIRES_AT_MS, challenge.expiresAtMillis)
                }
            }

            RuleContract.METHOD_CONSUME_PARENT_AUTH_CHALLENGE -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied()
                val token = extras?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN)
                    .orEmpty().take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                val challenge = ParentAuthStore.consumeForUi(token, System.currentTimeMillis())
                    ?: return denied()
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_PARENT_AUTH_TOKEN, challenge.token)
                    putString(RuleContract.KEY_PROCESS_SESSION_ID, challenge.identity.processSessionId)
                    putString(RuleContract.KEY_INCIDENT_ID, challenge.incidentId)
                    putString(RuleContract.KEY_PARENT_AUTH_REASON, challenge.reason)
                    putString("target_package", challenge.identity.packageName)
                    putLong(
                        RuleContract.KEY_BREAK_SESSION_EXPIRES_AT_MS,
                        challenge.expiresAtMillis,
                    )
                }
            }

            RuleContract.METHOD_COMPLETE_PARENT_AUTH_CHALLENGE -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied()
                val token = extras?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN)
                    .orEmpty().take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                val granted = extras?.getBoolean(RuleContract.KEY_PARENT_AUTH_GRANTED, false) == true
                val requestedMinutes = extras?.getInt(
                    RuleContract.KEY_PARENT_OVERRIDE_DURATION_MINUTES,
                    ParentOverrideDurationPolicy.DEFAULT_MINUTES,
                ) ?: ParentOverrideDurationPolicy.DEFAULT_MINUTES
                val durationMinutes = ParentOverrideDurationPolicy.normalizeMinutes(requestedMinutes)
                val completed = ParentAuthStore.complete(
                    token = token,
                    granted = granted,
                    durationMillis = ParentOverrideDurationPolicy.durationMillis(durationMinutes),
                    nowMillis = System.currentTimeMillis(),
                    nowElapsedMillis = SystemClock.elapsedRealtime(),
                    isIdentityCurrent = { identity ->
                        val currentSettings = ruleRepository.getGlobalSettings()
                        currentSettings.childLockEnabled &&
                            ChildLockRepository(appContext).isEnabled() &&
                            isConfiguredPackage(ruleRepository, identity.packageName) &&
                            identity.protectionModeGeneration == currentSettings.protectionModeGeneration &&
                            identity.ruleVersion == ruleRepository.getRule(identity.packageName).version &&
                            identity.groupVersion == (ruleRepository.groupForPackage(identity.packageName)?.version ?: 0L) &&
                            ParentAuthStore.challenge(token)?.let { challenge ->
                                val runtimeIdentity = runtimeToken(ruleRepository, identity.packageName, identity.processSessionId)
                                val runtime = controlRuntime.read(runtimeIdentity, SystemClock.elapsedRealtime())
                                runtime?.incident == challenge.incidentId &&
                                    (runtime.state in setOf(ControlRuntimeState.OVERRIDE_PENDING, ControlRuntimeState.OVERRIDE_ACTIVE) ||
                                        controlRuntime.transition(runtimeIdentity, challenge.incidentId,
                                            ControlRuntimeState.OVERRIDE_PENDING, SystemClock.elapsedRealtime()))
                            } == true
                    },
                    enforceDailyAd = true,
                    deferActivation = true,
                )
                    ?: return denied(if (granted && ParentAuthStore.needsAdForCompletion(token,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                        "parent_ad_required" else "parent_auth_completion_failed")
                if (!granted && !controlRuntime.transition(
                        runtimeToken(ruleRepository, completed.challenge.identity.packageName,
                            completed.challenge.identity.processSessionId), completed.challenge.incidentId,
                        ControlRuntimeState.EXECUTING_RESTRICTION, SystemClock.elapsedRealtime())) {
                    return denied("runtime_auth_denial_transition_failed")
                }
                val settings = ruleRepository.getGlobalSettings()
                diagnosticParentAuth(
                    appContext,
                    settings.diagnosticsEnabled,
                    completed.challenge.identity.packageName,
                    if (granted) "PARENT_AUTH_SUCCEEDED" else "PARENT_AUTH_FAILED",
                    "reason=${completed.challenge.reason}, incident=${completed.challenge.incidentId.take(80)}, " +
                        "durationMinutes=${if (granted) durationMinutes else 0}",
                )
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    completed.parentOverride?.let { parentOverride ->
                        putInt(
                            RuleContract.KEY_PARENT_OVERRIDE_DURATION_MINUTES,
                            durationMinutes,
                        )
                        putLong(
                            RuleContract.KEY_PARENT_OVERRIDE_GRANTED_AT_ELAPSED_MS,
                            parentOverride.grantedAtElapsedMillis,
                        )
                        putLong(
                            RuleContract.KEY_PARENT_OVERRIDE_EXPIRES_AT_ELAPSED_MS,
                            parentOverride.expiresAtElapsedMillis,
                        )
                    }
                }
            }

            RuleContract.METHOD_MARK_PARENT_AUTH_VERIFIED_FOR_AD -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied()
                val token = extras?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN)
                    .orEmpty().take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                val verified = ParentAuthStore.markVerifiedWaitingForAd(token, System.currentTimeMillis())
                    ?: return denied("parent_auth_verification_expired")
                if (!controlRuntime.transition(runtimeToken(ruleRepository, verified.identity.packageName,
                        verified.identity.processSessionId), verified.incidentId, ControlRuntimeState.WAITING_AD,
                        SystemClock.elapsedRealtime())) return denied("runtime_ad_transition_failed")
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_PARENT_AUTH_STATUS, verified.status.name)
                }
            }

            RuleContract.METHOD_MARK_PARENT_AUTH_AD_REWARDED -> {
                if (Binder.getCallingUid() != Process.myUid()) return denied()
                val token = extras?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN)
                    .orEmpty().take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                val rewarded = ParentAuthStore.markAdRewarded(token, System.currentTimeMillis())
                    ?: return denied("parent_auth_ad_reward_rejected")
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putString(RuleContract.KEY_PARENT_AUTH_STATUS, rewarded.status.name)
                }
            }

            RuleContract.METHOD_GET_PARENT_AUTH_STATUS -> {
                val packageName = arg.orEmpty()
                val ownRequest = Binder.getCallingUid() == Process.myUid()
                if (!ownRequest && !isCallerAllowed(packageName)) return denied()
                val token = extras?.getString(RuleContract.KEY_PARENT_AUTH_TOKEN)
                    .orEmpty().take(MAX_BREAK_SESSION_TOKEN_LENGTH)
                val sessionId = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                val status = ParentAuthStore.status(
                    token,
                    packageName,
                    sessionId,
                    System.currentTimeMillis(),
                )
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, status != ParentAuthStatus.INVALID)
                    putString(RuleContract.KEY_PARENT_AUTH_STATUS, status.name)
                }
            }

            RuleContract.METHOD_HAS_PARENT_OVERRIDE, RuleContract.METHOD_ACTIVATE_PARENT_OVERRIDE -> {
                val packageName = arg.orEmpty()
                val ownRequest = Binder.getCallingUid() == Process.myUid()
                if (!ownRequest && !isCallerAllowed(packageName)) return denied()
                if (!ruleRepository.getGlobalSettings().childLockEnabled ||
                    !ChildLockRepository(appContext).isEnabled()) return denied()
                val identity = currentOverrideIdentity(ruleRepository, packageName, extras)
                    ?: return denied()
                val interactive = runCatching {
                    appContext.getSystemService(android.os.PowerManager::class.java)?.isInteractive
                }.getOrNull() == true
                val nowElapsedMillis = SystemClock.elapsedRealtime()
                val activation = method == RuleContract.METHOD_ACTIVATE_PARENT_OVERRIDE
                val runtimeToken = runtimeToken(ruleRepository, packageName, identity.processSessionId)
                val record = controlRuntime.read(runtimeToken, nowElapsedMillis)
                val parentOverride = if (activation) {
                    val existing = ParentAuthStore.getOverride(identity, interactive, System.currentTimeMillis(), nowElapsedMillis)
                    if (existing != null) existing else {
                        if (!interactive || !hasForegroundEvidence(appContext, packageName, ownRequest, extras)) null else {
                            if (record == null || record.state !in setOf(ControlRuntimeState.OVERRIDE_PENDING, ControlRuntimeState.OVERRIDE_ACTIVE)) return denied("inactive_control_session")
                            // Gate, fixed deadline, quota and outbox commit together at the Provider boundary.
                            if (!controlRuntime.transition(runtimeToken, record.incident, ControlRuntimeState.OVERRIDE_ACTIVE, nowElapsedMillis)) return denied("runtime_persist_failed")
                            ParentAuthStore.activateOverride(identity, interactive, nowElapsedMillis, foregroundVerified = true)
                                ?: return denied("parent_activation_failed")
                        }
                    }
                } else ParentAuthStore.getOverride(
                    identity = identity,
                    screenInteractive = interactive,
                    nowMillis = System.currentTimeMillis(),
                    nowElapsedMillis = nowElapsedMillis,
                )
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putBoolean(
                        RuleContract.KEY_PARENT_AUTH_GRANTED,
                        parentOverride != null,
                    )
                    val pending = ParentAuthStore.pendingOverride(identity, nowElapsedMillis)
                        ?.takeIf { record?.state in setOf(ControlRuntimeState.OVERRIDE_PENDING, ControlRuntimeState.OVERRIDE_ACTIVE) }
                    putBoolean(RuleContract.KEY_PARENT_OVERRIDE_PENDING, pending != null)
                    putLong("parent_pending_created_elapsed_ms", pending?.grantedAtElapsedMillis ?: 0L)
                    parentOverride?.let {
                        putLong(
                            RuleContract.KEY_PARENT_OVERRIDE_GRANTED_AT_ELAPSED_MS,
                            it.grantedAtElapsedMillis,
                        )
                        putLong(
                            RuleContract.KEY_PARENT_OVERRIDE_EXPIRES_AT_ELAPSED_MS,
                            it.expiresAtElapsedMillis,
                        )
                        putLong(
                            RuleContract.KEY_PARENT_OVERRIDE_REMAINING_MS,
                            (it.expiresAtElapsedMillis - nowElapsedMillis).coerceAtLeast(0L),
                        )
                    }
                }
            }

            RuleContract.METHOD_CLAIM_OPEN_USAGE_TIP -> {
                val packageName = arg.orEmpty()
                if (Binder.getCallingUid() != Process.myUid() && !isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName) ||
                    !ruleRepository.getGlobalSettings().openUsageTipEnabled) return denied()
                val claimed = synchronized(openTipTimes) {
                    val now = SystemClock.elapsedRealtime()
                    if (!com.liuml.apptimelimiter.core.OpenUsageTipPolicy.mayShow(now, openTipTimes[packageName])) false
                    else {
                        openTipTimes[packageName] = now
                        while (openTipTimes.size > 128) openTipTimes.remove(openTipTimes.keys.first())
                        true
                    }
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, claimed) }
            }

            RuleContract.METHOD_REVOKE_PARENT_OVERRIDE -> {
                val packageName = arg.orEmpty()
                val ownRequest = Binder.getCallingUid() == Process.myUid()
                if (!ownRequest && !isCallerAllowed(packageName)) return denied()
                val sessionId = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID)
                    .orEmpty().take(MAX_SESSION_ID_LENGTH)
                if (sessionId.isBlank()) return denied()
                val revoked = ParentAuthStore.revoke(packageName, sessionId)
                if (revoked) {
                    diagnosticParentAuth(
                        appContext,
                        ruleRepository.getGlobalSettings().diagnosticsEnabled,
                        packageName,
                        "TEMPORARY_OVERRIDE_REVOKED",
                        "session=${sessionId.take(40)}",
                    )
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
            }

            RuleContract.METHOD_APPEND_LOG -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                if (!ruleRepository.getGlobalSettings().diagnosticsEnabled) {
                    return Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
                }
                DiagnosticsRepository(appContext).append(
                    level = extras?.getString(RuleContract.KEY_LEVEL)
                        .orEmpty()
                        .uppercase()
                        .takeIf { it in ALLOWED_LOG_LEVELS }
                        ?: "INFO",
                    packageName = packageName,
                    event = extras?.getString(RuleContract.KEY_EVENT)
                        .orEmpty()
                        .ifBlank { "UNKNOWN" }
                        .take(MAX_EVENT_LENGTH),
                    message = extras?.getString(RuleContract.KEY_MESSAGE)
                        .orEmpty()
                        .take(MAX_MESSAGE_LENGTH),
                )
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
            }

            RuleContract.METHOD_RECORD_USAGE -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                val settings = ruleRepository.getGlobalSettings()
                val groupDailyEnabled = ruleRepository.groupForPackage(packageName)
                    ?.let { it.enabled && packageName in it.packageNames && it.dailyEnabled }
                    ?: false
                val durationAllowed = UsageReportingPolicy.shouldReportDuration(
                    usageStatsEnabled = settings.usageStatsEnabled,
                    groupDailyEnabled = groupDailyEnabled,
                )
                val hookVersionCode = extras?.getInt(
                    RuleContract.KEY_HOOK_VERSION_CODE,
                    0,
                )?.coerceIn(0, MAX_HOOK_VERSION_CODE) ?: 0
                if (
                    !ProtectionExecutionPolicy.acceptUsageReport(
                        mode = settings.protectionMode,
                        trustedManagerRequest = Binder.getCallingUid() == Process.myUid(),
                    )
                ) return denied()
                val persisted = UsageStatsRepository(appContext).record(
                    packageName = packageName,
                    durationMillis = if (durationAllowed) {
                        (extras?.getLong(RuleContract.KEY_DURATION_MS, 0L) ?: 0L)
                            .coerceIn(0L, MAX_REPORTED_DURATION_MS)
                    } else {
                        0L
                    },
                    launchIncrement = if (settings.usageStatsEnabled) {
                        (extras?.getInt(RuleContract.KEY_LAUNCH_INCREMENT, 0) ?: 0)
                            .coerceIn(0, MAX_COUNTER_INCREMENT)
                    } else {
                        0
                    },
                    limitHitIncrement = if (settings.usageStatsEnabled) {
                        extras?.getInt(
                            RuleContract.KEY_LIMIT_HIT_INCREMENT,
                            0,
                        )?.coerceIn(0, MAX_COUNTER_INCREMENT) ?: 0
                    } else {
                        0
                    },
                    reminderIncrement = if (settings.usageStatsEnabled) {
                        extras?.getInt(RuleContract.KEY_REMINDER_INCREMENT, 0)
                            ?.coerceIn(0, 1) ?: 0
                    } else {
                        0
                    },
                    hookVersionCode = hookVersionCode,
                    hookModeGeneration = extras?.getLong(
                        RuleContract.KEY_HOOK_MODE_GENERATION,
                        0L,
                    )?.coerceAtLeast(0L) ?: 0L,
                    dayToken = extras?.getString(RuleContract.KEY_DAY_TOKEN),
                    eventId = extras?.getString(RuleContract.KEY_USAGE_EVENT_ID),
                )
                if (persisted && hookVersionCode > 0) {
                    appContext.contentResolver.notifyChange(
                        RuleContract.HOOK_STATUS_URI,
                        null,
                    )
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, persisted) }
            }

            RuleContract.METHOD_CLAIM_USAGE_MILESTONE_REMINDER -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                val settings = ruleRepository.getGlobalSettings()
                if (!settings.usageMilestoneReminderEnabled) return denied()
                val day = extras?.getString(RuleContract.KEY_DAY_TOKEN)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return denied()
                // Delayed callbacks crossing midnight must not create yesterday's reminder.
                if (day != LocalDate.now()) return denied()
                val index = extras?.getInt(RuleContract.KEY_USAGE_MILESTONE_INDEX, 0) ?: 0
                if (!UsageMilestonePolicy.isValidMilestoneIndex(index)) return denied()
                val phase = extras?.getString("milestone_phase")
                if (phase != null && Binder.getCallingUid() != Process.myUid()) return denied()
                val repository = UsageStatsRepository(appContext)
                val persisted = if (phase == null) repository.claimUsageMilestoneReminder(packageName, day, index)
                    else repository.reserveUsageMilestone(packageName, day, index,
                        extras.getString("milestone_owner").orEmpty(), phase, SystemClock.elapsedRealtime())
                if (persisted && settings.diagnosticsEnabled && (phase == null || phase == "confirm")) {
                    DiagnosticsRepository(appContext).append(
                        "INFO",
                        packageName,
                        "USAGE_MILESTONE_REMINDER_RECORDED",
                        "day=$day,milestone=$index",
                    )
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, persisted) }
            }

            RuleContract.METHOD_REPORT_HOOK_STATUS -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                val hookVersionCode = extras?.getInt(
                    RuleContract.KEY_HOOK_VERSION_CODE,
                    0,
                )?.coerceIn(1, MAX_HOOK_VERSION_CODE) ?: return denied()
                val modeGeneration = extras.getLong(
                    RuleContract.KEY_HOOK_MODE_GENERATION,
                    0L,
                ).coerceAtLeast(0L)
                val persisted = UsageStatsRepository(appContext).recordHookHeartbeat(
                    packageName = packageName,
                    hookVersionCode = hookVersionCode,
                    hookModeGeneration = modeGeneration,
                )
                if (persisted) {
                    appContext.contentResolver.notifyChange(RuleContract.HOOK_STATUS_URI, null)
                }
                Bundle().apply { putBoolean(RuleContract.KEY_OK, persisted) }
            }

            RuleContract.METHOD_VIBRATE_WARNING -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
                val settings = ruleRepository.getGlobalSettings()
                if (
                    !ProtectionExecutionPolicy.acceptHookSideEffect(settings.protectionMode) ||
                    !settings.exitWarningVibrationEnabled
                ) {
                    return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
                }
                Bundle().apply {
                    putBoolean(
                        RuleContract.KEY_OK,
                        vibrateWarning(appContext, packageName),
                    )
                }
            }

            RuleContract.METHOD_ROOT_FORCE_STOP_SELF -> {
                rootForceStopSelf(appContext, ruleRepository, arg, extras)
            }

            else -> super.call(method, arg, extras)
        }
    }

    private fun isCallerAllowed(requestedPackage: String): Boolean {
        if (requestedPackage.isBlank()) return false
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return true
        val appOps = context?.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            // checkPackage is authoritative and is not affected by Android package-visibility filters.
            appOps.checkPackage(callingUid, requestedPackage)
            // Keep an explicit PackageManager check as a second guard for shared-UID callers and
            // vendor AppOps implementations that only validate the operation owner.
            val packages = context?.packageManager?.getPackagesForUid(callingUid)
            packages?.contains(requestedPackage) == true
        }.getOrDefault(false)
    }

    private fun isValidRestrictionUiIncident(incidentId: String): Boolean =
        incidentId.isNotBlank() &&
            incidentId.length <= MAX_INCIDENT_ID_LENGTH &&
            incidentId.none { it == '\n' || it == '\r' }

    private fun encodeRestrictionUiClaim(record: RestrictionUiClaim): String =
        "${Base64.encodeToString(record.incidentId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}.${record.expiresAtMillis}"

    private fun decodeRestrictionUiClaim(raw: String?): RestrictionUiClaim? {
        val parts = raw?.split('.', limit = 2) ?: return null
        if (parts.size != 2) return null
        val incidentId = runCatching {
            String(Base64.decode(parts[0], Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        if (!isValidRestrictionUiIncident(incidentId)) return null
        val expiresAtMillis = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return RestrictionUiClaim(incidentId, expiresAtMillis)
    }

    private data class RestrictionUiClaim(
        val incidentId: String,
        val expiresAtMillis: Long,
    )

    private data class RestrictionUiClaimResult(
        val accepted: Boolean,
        val newClaim: Boolean,
        val activeIncidentId: String,
    )

    private data class PendingReward(
        val rewardMillis: Long,
        val transactionId: String,
        val ruleVersion: Long,
        val groupVersion: Long,
        val modeGeneration: Long,
    )

    private fun putRewardedAdPending(
        packageName: String,
        sessionId: String,
        transactionId: String,
        rewardMillis: Long,
        ruleVersion: Long,
        groupVersion: Long,
        modeGeneration: Long,
    ): Boolean {
        if (packageName.contains('|') || sessionId.contains('|') || transactionId.contains('|')) return false
        val key = "${packageName.take(100)}.${sessionId.take(120)}"
        return synchronized(rewardedAdPendingLock) {
            context?.getSharedPreferences("rewarded_ad_pending_private", Context.MODE_PRIVATE)
                ?.let {
                    val existing = readRewardedAdPending(packageName, sessionId, "")
                    if (existing != null && existing.transactionId != transactionId) return@synchronized false
                    val nowElapsedMillis = SystemClock.elapsedRealtime()
                    val nowWallMillis = System.currentTimeMillis()
                    it.edit().putString(key, listOf(rewardMillis, transactionId.take(100), ruleVersion, groupVersion, modeGeneration)
                        .joinToString("|"))
                        .putLong("${key}.expires", safeAdd(nowElapsedMillis, REWARDED_AD_PENDING_TTL_MILLIS))
                        .putLong("${key}.wall_expires", safeAdd(nowWallMillis, REWARDED_AD_PENDING_TTL_MILLIS))
                        .commit()
                } ?: false
        }
    }

    private fun readRewardedAdPending(packageName: String, sessionId: String, transactionId: String): PendingReward? {
        val key = "${packageName.take(100)}.${sessionId.take(120)}"
        val prefs = context?.getSharedPreferences("rewarded_ad_pending_private", Context.MODE_PRIVATE) ?: return null
        val raw = prefs.getString(key, null) ?: return null
        val values = raw.split('|')
        if (values.size != 5) return null
        val pending = runCatching {
            PendingReward(values[0].toLong(), values[1], values[2].toLong(), values[3].toLong(), values[4].toLong())
        }.getOrNull()
        val active = pending != null &&
            (transactionId.isBlank() || pending.transactionId == transactionId) &&
            prefs.getLong("${key}.expires", 0L) > SystemClock.elapsedRealtime() &&
            prefs.getLong("${key}.wall_expires", 0L) > System.currentTimeMillis()
        if (!active) {
            prefs.edit().remove(key).remove("${key}.expires").remove("${key}.wall_expires").commit()
            return null
        }
        return pending
    }

    private fun removeRewardedAdPending(packageName: String, sessionId: String, transactionId: String): Boolean {
        val key = "${packageName.take(100)}.${sessionId.take(120)}"
        val prefs = context?.getSharedPreferences("rewarded_ad_pending_private", Context.MODE_PRIVATE) ?: return false
        if (prefs.getString(key, null)?.split('|')?.getOrNull(1) != transactionId) return false
        return prefs.edit().remove(key).remove("${key}.expires").remove("${key}.wall_expires").commit()
    }

    private fun isConfiguredPackage(repository: RuleRepository, packageName: String): Boolean {
        if (Binder.getCallingUid() == Process.myUid()) return true
        if (!PackageNamePolicy.isValid(packageName)) return false
        return RuleActivationPolicy.hasEffectiveRule(
            rule = repository.getRule(packageName),
            assignedGroup = repository.groupForPackage(packageName),
        )
    }

    /**
     * The target process is intentionally not allowed to execute su. It can only ask this
     * provider to perform a validated, package-scoped operation in the manager process.
     */
    private fun rootForceStopSelf(
        appContext: android.content.Context,
        repository: RuleRepository,
        packageNameArg: String?,
        extras: Bundle?,
    ): Bundle {
        val packageName = packageNameArg.orEmpty()
        val callingUid = Binder.getCallingUid()
        val incidentId = extras?.getString(RuleContract.KEY_INCIDENT_ID).orEmpty()
        if (!PackageNamePolicy.isValid(packageName) || !isCallerAllowed(packageName)) {
            return denied("caller_mismatch")
        }
        if (!isConfiguredPackage(repository, packageName)) return denied("rule_not_configured")
        if (
            incidentId.isBlank() || incidentId.length > MAX_INCIDENT_ID_LENGTH ||
            incidentId.any { it == '\n' || it == '\r' }
        ) {
            return denied("invalid_incident")
        }
        val settings = repository.getGlobalSettings()
        if (
            settings.protectionMode != ProtectionMode.XPOSED ||
                !settings.xposedRootEnhancementEnabled
        ) {
            return denied("root_not_enabled")
        }
        val appInfo = runCatching {
            appContext.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return denied("package_not_found")
        val protectedPackages = setOf(
            appContext.packageName,
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
        )
        val homePackage = runCatching {
            appContext.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        if (
            appInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
            packageName in protectedPackages || packageName == homePackage
        ) {
            return denied("protected_package")
        }
        val request = RestrictionRequest(
            packageName = packageName,
            userId = (callingUid / 100_000).coerceAtLeast(0),
            reason = "root_force_stop",
            incidentId = incidentId,
            ruleVersion = repository.getRule(packageName).version,
            groupVersion = repository.groupForPackage(packageName)?.version ?: 0L,
            modeGeneration = settings.protectionModeGeneration,
            foregroundPackage = packageName,
            foregroundGeneration = extras?.getLong("foreground_generation", 0L) ?: 0L,
            sessionId = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID)
                .orEmpty().take(MAX_SESSION_ID_LENGTH).ifBlank { "root:$incidentId" },
            allowDelay = false,
            allowPin = false,
            allowAd = false,
        )
        diagnosticParentAuth(
            appContext,
            settings.diagnosticsEnabled,
            packageName,
            "ROOT_FORCE_STOP_REQUESTED",
            "incident=${incidentId.take(80)}",
        )
        if (!claimRootForceStopIncident(appContext, incidentId)) {
            if (settings.diagnosticsEnabled) {
                diagnosticParentAuth(
                    appContext,
                    true,
                    packageName,
                    "ROOT_FORCE_STOP_RESULT",
                    "result=duplicate; incident=${incidentId.take(80)}",
                )
            }
            return Bundle().apply {
                putBoolean(RuleContract.KEY_OK, true)
                putString(RuleContract.KEY_MESSAGE, "ALREADY_QUEUED")
            }
        }
        rootForceStopExecutor.execute {
            val result = if (isRootForceStopRequestStillValid(appContext, request)) {
                RootExecutor(appContext).execute(request)
            } else {
                RestrictionExecutionResult.REJECTED
            }
            if (settings.diagnosticsEnabled) {
                diagnosticParentAuth(
                    appContext,
                    true,
                    packageName,
                    "ROOT_FORCE_STOP_RESULT",
                    "result=${result.name.lowercase(java.util.Locale.ROOT)}; incident=${incidentId.take(80)}",
                )
            }
        }
        return Bundle().apply {
            putBoolean(RuleContract.KEY_OK, true)
            putString(RuleContract.KEY_MESSAGE, "QUEUED")
        }
    }

    private fun isRootForceStopRequestStillValid(
        appContext: Context,
        request: RestrictionRequest,
    ): Boolean {
        val repository = RuleRepository(appContext)
        val settings = repository.getGlobalSettings()
        if (
            settings.protectionMode != ProtectionMode.XPOSED ||
                !settings.xposedRootEnhancementEnabled
        ) {
            return false
        }
        val rule = repository.getRule(request.packageName) ?: return false
        val group = repository.groupForPackage(request.packageName)
        if (settings.childLockEnabled && ParentAuthStore.hasAllowance(TemporaryOverrideIdentity(
                request.packageName, request.sessionId, rule.version, group?.version ?: 0L,
                settings.protectionModeGeneration), SystemClock.elapsedRealtime())) return false
        return isConfiguredPackage(repository, request.packageName) &&
            rule.version == request.ruleVersion &&
            (group?.version ?: 0L) == request.groupVersion &&
            settings.protectionModeGeneration == request.modeGeneration
    }

    private fun claimRootForceStopIncident(appContext: Context, incidentId: String): Boolean =
        synchronized(rootForceStopLock) {
            val prefs = appContext.getSharedPreferences(ROOT_FORCE_STOP_PREFS, Context.MODE_PRIVATE)
            val key = "$ROOT_FORCE_STOP_INCIDENT_PREFIX$incidentId"
            if (prefs.contains(key)) return@synchronized false
            val records = prefs.all
                .asSequence()
                .filter { (storedKey, value) ->
                    storedKey.startsWith(ROOT_FORCE_STOP_INCIDENT_PREFIX) && value is Long
                }
                .sortedBy { (_, value) -> value as Long }
                .toList()
            val editor = prefs.edit().putLong(key, System.currentTimeMillis())
            records.take((records.size + 1 - MAX_ROOT_FORCE_STOP_INCIDENTS).coerceAtLeast(0))
                .forEach { (oldKey, _) -> editor.remove(oldKey) }
            editor.commit()
        }

    private fun denied() = Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }

    private fun denied(reason: String) = denied().apply {
        putString(RuleContract.KEY_MESSAGE, reason.take(MAX_MESSAGE_LENGTH))
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun generateBreakSessionToken(): String {
        val bytes = ByteArray(BREAK_SESSION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    private fun currentOverrideIdentity(
        repository: RuleRepository,
        packageName: String,
        extras: Bundle?,
    ): TemporaryOverrideIdentity? {
        if (!PackageNamePolicy.isValid(packageName) || packageName !in repository.configuredPackages()) {
            return null
        }
        val sessionId = extras?.getString(RuleContract.KEY_PROCESS_SESSION_ID)
            .orEmpty().take(MAX_SESSION_ID_LENGTH)
        if (sessionId.isBlank() || sessionId.hasLineBreak()) return null
        val rule = repository.getRule(packageName)
        return TemporaryOverrideIdentity(
            packageName = packageName,
            processSessionId = sessionId,
            ruleVersion = rule.version,
            groupVersion = repository.groupForPackage(packageName)?.version ?: 0L,
            protectionModeGeneration = repository.getGlobalSettings().protectionModeGeneration,
        )
    }

    private fun String.hasLineBreak(): Boolean = any { it == '\n' || it == '\r' }

    private fun diagnosticParentAuth(
        context: android.content.Context,
        enabled: Boolean,
        packageName: String,
        event: String,
        message: String,
    ) {
        if (!enabled) return
        // The authoritative outbox is committed with PIN state. Never emit a premature success
        // text log (or take diagnostics file locks) while that transaction can still roll back.
        if (ManagerControlDatabase.get(context).hasTransaction()) return
        DiagnosticsRepository(context).append("INFO", packageName, event, message)
    }

    private fun lookupSystemUsage(
        repository: RuleRepository,
        usageRepository: DeviceUsageStatsRepository,
        requiredPackages: Set<String>,
    ): SystemUsageLookup {
        val today = LocalDate.now()
        val nowElapsed = SystemClock.elapsedRealtime()
        val snapshot = synchronized(usageCacheLock) { usageSnapshot }
        val availableSnapshot = snapshot?.takeIf {
            it.day == today &&
                it.packageNames.containsAll(requiredPackages)
        }
        val fresh = availableSnapshot != null &&
            nowElapsed - availableSnapshot.measuredAtElapsedMillis <=
            SYSTEM_USAGE_REFRESH_INTERVAL_MS
        if (!fresh) {
            val packagesToRefresh = repository.configuredPackages()
                .ifEmpty { requiredPackages }
                .toSet()
            requestSystemUsageRefresh(usageRepository, packagesToRefresh)
        }
        return if (availableSnapshot != null) {
            SystemUsageLookup(
                durations = availableSnapshot.durations,
                measuredAtElapsedMillis = availableSnapshot.measuredAtElapsedMillis,
                available = true,
                refreshPending = !fresh,
            )
        } else {
            SystemUsageLookup(
                durations = emptyMap(),
                measuredAtElapsedMillis = nowElapsed,
                available = false,
                refreshPending = true,
            )
        }
    }

    private fun requestSystemUsageRefresh(
        repository: DeviceUsageStatsRepository,
        packageNames: Set<String>,
    ) {
        if (packageNames.isEmpty()) return
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(usageCacheLock) {
            if (usageRefreshInFlight) return
            if (
                lastUsageRefreshAttemptElapsedMillis != Long.MIN_VALUE &&
                nowElapsed - lastUsageRefreshAttemptElapsedMillis <
                MIN_SYSTEM_USAGE_REFRESH_ATTEMPT_INTERVAL_MS
            ) return
            usageRefreshInFlight = true
            lastUsageRefreshAttemptElapsedMillis = nowElapsed
        }
        usageRefreshExecutor.execute {
            try {
                val durations = repository.todayDurations(packageNames)
                if (durations.isNotEmpty()) {
                    synchronized(usageCacheLock) {
                        usageSnapshot = SystemUsageSnapshot(
                            day = LocalDate.now(),
                            packageNames = packageNames,
                            durations = durations.toMap(),
                            measuredAtElapsedMillis = SystemClock.elapsedRealtime(),
                        )
                    }
                }
            } finally {
                synchronized(usageCacheLock) { usageRefreshInFlight = false }
            }
        }
    }

    private fun vibrateWarning(appContext: android.content.Context, packageName: String): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(warningVibrationLock) {
            val last = lastWarningVibrationByPackage[packageName] ?: Long.MIN_VALUE
            if (last != Long.MIN_VALUE && nowElapsed - last < WARNING_VIBRATION_MIN_INTERVAL_MS) {
                return true
            }
        }
        val identity = Binder.clearCallingIdentity()
        val vibrated = try {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Vibrator::class.java)
                } ?: return false
                if (!vibrator.hasVibrator()) return false
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        WARNING_VIBRATION_DURATION_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
                true
            }.getOrDefault(false)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        if (vibrated) {
            synchronized(warningVibrationLock) {
                lastWarningVibrationByPackage[packageName] = nowElapsed
            }
        }
        return vibrated
    }

    private companion object {
        const val MAX_REPORTED_DURATION_MS = 24L * 60L * 60L * 1000L
        const val MAX_COUNTER_INCREMENT = 100
        const val MAX_HOOK_VERSION_CODE = 1_000_000
        const val MAX_EVENT_LENGTH = 80
        const val MAX_MESSAGE_LENGTH = 2_000
        const val MAX_INCIDENT_ID_LENGTH = 320
        const val SYSTEM_USAGE_REFRESH_INTERVAL_MS = 15_000L
        const val MIN_SYSTEM_USAGE_REFRESH_ATTEMPT_INTERVAL_MS = 5_000L
        const val WARNING_VIBRATION_DURATION_MS = 1_200L
        const val WARNING_VIBRATION_MIN_INTERVAL_MS = 30_000L
        const val BREAK_SESSION_PREFS = "break_sessions"
        const val RESTRICTION_UI_PREFS = "restriction_ui_private"
        const val RESTRICTION_UI_PREFIX = "active."
        const val RESTRICTION_UI_TTL_MS = 10L * 60L * 1_000L
        const val ROOT_FORCE_STOP_PREFS = "root_force_stop_private"
        const val ROOT_FORCE_STOP_INCIDENT_PREFIX = "incident."
        const val MAX_ROOT_FORCE_STOP_INCIDENTS = 128
        const val KEY_BREAK_SESSION_RECORDS = "records"
        const val BREAK_SESSION_TOKEN_BYTES = 24
        const val MAX_BREAK_SESSION_TOKEN_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 160
        const val MAX_AUTH_REASON_LENGTH = 80
        const val MAX_GROUP_SESSION_SEGMENT_MILLIS = 24L * 60L * 60L * 1_000L
        const val REWARDED_AD_PENDING_TTL_MILLIS = 5L * 60L * 1_000L
        val ALLOWED_LOG_LEVELS = setOf("DEBUG", "INFO", "WARN", "ERROR")
    }

    private data class SystemUsageSnapshot(
        val day: LocalDate,
        val packageNames: Set<String>,
        val durations: Map<String, Long>,
        val measuredAtElapsedMillis: Long,
    )

    private data class SystemUsageLookup(
        val durations: Map<String, Long>,
        val measuredAtElapsedMillis: Long,
        val available: Boolean,
        val refreshPending: Boolean,
    ) {
        companion object {
            val EMPTY = SystemUsageLookup(emptyMap(), 0L, false, false)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
