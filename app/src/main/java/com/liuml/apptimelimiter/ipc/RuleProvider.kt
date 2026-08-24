package com.liuml.apptimelimiter.ipc

import android.app.AppOpsManager
import android.content.ContentProvider
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
import com.liuml.apptimelimiter.core.BreakSessionPolicy
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.core.ParentOverrideDurationPolicy
import com.liuml.apptimelimiter.core.ProtectionExecutionPolicy
import com.liuml.apptimelimiter.core.RuleActivationPolicy
import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import com.liuml.apptimelimiter.core.GroupUsagePolicy
import com.liuml.apptimelimiter.core.SharedCooldownClaimStatus
import com.liuml.apptimelimiter.core.SharedGroupSessionAction
import com.liuml.apptimelimiter.core.SharedGroupSessionPolicy
import com.liuml.apptimelimiter.core.TimeQuotePolicy
import com.liuml.apptimelimiter.core.UsageReportingPolicy
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.security.ParentAuthStatus
import com.liuml.apptimelimiter.security.ParentAuthStore
import java.time.LocalDate
import java.security.SecureRandom
import java.util.concurrent.Executors

class RuleProvider : ContentProvider() {
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
    private val secureRandom = SecureRandom()

    override fun onCreate(): Boolean {
        context?.let { deviceUsageStatsRepository = DeviceUsageStatsRepository(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context ?: return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
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
                    putLong(RuleContract.KEY_EXTENSION_SECONDS, settings.extensionSeconds)
                    putBoolean(RuleContract.KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
                    putBoolean(RuleContract.KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
                    putString(
                        RuleContract.KEY_LIMIT_ENFORCEMENT_MODE,
                        settings.limitEnforcementMode.name,
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
                val challenge = ParentAuthStore.issue(
                    token = token,
                    identity = identity,
                    incidentId = incidentId,
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
                )
                    ?: return denied()
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

            RuleContract.METHOD_HAS_PARENT_OVERRIDE -> {
                val packageName = arg.orEmpty()
                val ownRequest = Binder.getCallingUid() == Process.myUid()
                if (!ownRequest && !isCallerAllowed(packageName)) return denied()
                val identity = currentOverrideIdentity(ruleRepository, packageName, extras)
                    ?: return denied()
                val interactive = runCatching {
                    appContext.getSystemService(android.os.PowerManager::class.java)?.isInteractive
                }.getOrNull() == true
                val nowElapsedMillis = SystemClock.elapsedRealtime()
                val parentOverride = ParentAuthStore.getOverride(
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
                    hookVersionCode = hookVersionCode,
                    hookModeGeneration = extras?.getLong(
                        RuleContract.KEY_HOOK_MODE_GENERATION,
                        0L,
                    )?.coerceAtLeast(0L) ?: 0L,
                    dayToken = extras?.getString(RuleContract.KEY_DAY_TOKEN),
                )
                if (persisted && hookVersionCode > 0) {
                    appContext.contentResolver.notifyChange(
                        RuleContract.HOOK_STATUS_URI,
                        null,
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
            true
        }.getOrDefault(false)
    }

    private fun isConfiguredPackage(repository: RuleRepository, packageName: String): Boolean {
        if (Binder.getCallingUid() == Process.myUid()) return true
        if (!PackageNamePolicy.isValid(packageName)) return false
        return RuleActivationPolicy.hasEffectiveRule(
            rule = repository.getRule(packageName),
            assignedGroup = repository.groupForPackage(packageName),
        )
    }

    private fun denied() = Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }

    private fun denied(reason: String) = denied().apply {
        putString(RuleContract.KEY_MESSAGE, reason.take(MAX_MESSAGE_LENGTH))
    }

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
        const val KEY_BREAK_SESSION_RECORDS = "records"
        const val BREAK_SESSION_TOKEN_BYTES = 24
        const val MAX_BREAK_SESSION_TOKEN_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 160
        const val MAX_AUTH_REASON_LENGTH = 80
        const val MAX_GROUP_SESSION_SEGMENT_MILLIS = 24L * 60L * 60L * 1_000L
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
