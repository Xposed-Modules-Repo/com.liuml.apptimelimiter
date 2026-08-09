package com.liuml.apptimelimiter.nonroot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import com.liuml.apptimelimiter.LimitBlockActivity
import com.liuml.apptimelimiter.core.GroupUsagePolicy
import com.liuml.apptimelimiter.core.QuotaIncidentPolicy
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.ScheduleBlockPolicy
import com.liuml.apptimelimiter.core.ScheduleConstraint
import com.liuml.apptimelimiter.core.ScheduleEvaluator
import com.liuml.apptimelimiter.core.UsageReportingPolicy
import com.liuml.apptimelimiter.data.AppGroup
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.AppRule
import com.liuml.apptimelimiter.data.GlobalSettings
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ForegroundControlCoordinator(
    private val service: AccessibilityService,
) {
    private val appContext = service.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "time-stop-non-root").apply { isDaemon = true }
    }
    private val repository = RuleRepository(appContext)
    private val runtimeStore = NonRootRuntimeStore(appContext)
    private val usageRepository = UsageStatsRepository(appContext)
    private val deviceUsageRepository = DeviceUsageStatsRepository(appContext)
    private val diagnostics = DiagnosticsRepository(appContext)
    private val statusRepository = NonRootProtectionStatusRepository.get(appContext)
    private val shizuku = ShizukuExecutionRepository.get(appContext)
    private val overlay = NonRootSessionPlanOverlay(service) { level, packageName, event, message ->
        log(packageName, event, message, level)
    }
    private val generation = AtomicLong(0L)
    private val foregroundGeneration = AtomicLong(0L)
    private val sessions = mutableMapOf<String, NonRootSessionState>()
    private val planPromptAttempts = mutableMapOf<String, Int>()
    private val recordedLimitIncidents = linkedSetOf<String>()
    private val lastSignalElapsedMillis = mutableMapOf<String, Long>()
    private val compatibilityRetryAfterElapsedMillis = mutableMapOf<String, Long>()
    private var lastRuntimeWarningToken = ""
    private var suppressNextGenericBreakPageToast = false
    private var lastRuntimeWarningAtElapsedMillis = Long.MIN_VALUE
    private var foregroundPackage: String? = null
    private var visibleRestrictionPackage: String? = null
    private var foregroundDetectedAtMillis = 0L
    private var lastForegroundSignal: ForegroundSignalSnapshot? = null
    private var foregroundExecutionSnapshot: ForegroundExecutionSnapshot? = null
    private var homePackages: Set<String> = resolveHomePackages()
    private var compatibilityCandidate: CompatibilitySignalState? = null
    private var uiState = NonRootUiExecutionState.IDLE
    private var pendingBreakPageAttempt: BreakPageAttempt? = null
    private var interactive = true
    private var destroyed = false
    init {
        runtimeStore.recoverInterruptedSessions(SystemClock.elapsedRealtime()).forEach {
            log(
                it,
                "NON_ROOT_SESSION_RECOVERED_PAUSED",
                "A foreground segment left active by process loss was paused without charging " +
                    "the unobserved gap",
                "WARN",
            )
        }
    }

    fun recoverForegroundFromUsageStats() {
        executor.execute {
            val recovered = deviceUsageRepository.currentForegroundSnapshot()
            if (recovered == null) {
                log(
                    appContext.packageName,
                    "NON_ROOT_FOREGROUND_RECOVERY_EMPTY",
                    "UsageStats did not return a foreground package",
                    "WARN",
                )
                return@execute
            }
            handler.post {
                if (destroyed) return@post
                val shouldAccept = ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                    current = lastForegroundSignal,
                    usagePackageName = recovered.packageName,
                    usageObservedAtMillis = recovered.observedAtMillis,
                )
                statusRepository.recordUsageReconciliation(
                    matched = recovered.packageName == foregroundPackage,
                )
                if (!shouldAccept && foregroundPackage != null) return@post
                log(
                    recovered.packageName,
                    "NON_ROOT_FOREGROUND_RECOVERED",
                    "source=UsageStats, observedAt=${recovered.observedAtMillis}",
                )
                acceptForegroundSignal(
                    packageName = recovered.packageName,
                    eventType = 0,
                    source = ForegroundSignalSource.USAGE_STATS_RECOVERY,
                    observedAtMillis = recovered.observedAtMillis,
                )
            }
        }
    }

    fun onProtectionModeChanged(
        mode: com.liuml.apptimelimiter.data.ProtectionMode,
        modeGeneration: Long,
    ) {
        val previousPackage = foregroundPackage
        val previousUiState = uiState
        generation.incrementAndGet()
        foregroundGeneration.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        overlay.dismiss("protection_mode_changed")
        pendingBreakPageAttempt = null
        visibleRestrictionPackage = null
        statusRepository.recordBreakPageConfirmed()
        sessions.clear()
        planPromptAttempts.clear()
        runtimeStore.clearAllSessions()
        foregroundPackage = null
        foregroundExecutionSnapshot = null
        foregroundDetectedAtMillis = 0L
        lastForegroundSignal = null
        compatibilityCandidate = null
        transitionUiState(
            NonRootUiExecutionState.IDLE,
            previousPackage.orEmpty().ifBlank { appContext.packageName },
            "protection_mode_changed",
        )
        log(
            appContext.packageName,
            "PROTECTION_MODE_TRANSITION_COMPLETED",
            "mode=$mode generation=$modeGeneration previousPackage=${previousPackage.orEmpty()} " +
                "previousUi=$previousUiState",
        )
    }

    fun refreshHomePackages() {
        homePackages = resolveHomePackages()
        log(
            appContext.packageName,
            "NON_ROOT_HOME_PACKAGES_REFRESHED",
            "count=${homePackages.size}, packages=${homePackages.sorted().joinToString(",").take(300)}",
        )
    }

    fun onForegroundSignal(
        packageName: String,
        eventType: Int = 0,
        source: ForegroundSignalSource,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val kind = classifyForegroundPackage(packageName)
        if (
            source == ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT &&
            kind != ForegroundPackageKind.HOME
        ) {
            handleCompatibilitySignal(packageName, eventType, observedAtMillis)
            return
        }
        acceptForegroundSignal(packageName, eventType, source, observedAtMillis)
    }

    private fun acceptForegroundSignal(
        packageName: String,
        eventType: Int,
        source: ForegroundSignalSource,
        observedAtMillis: Long,
    ) {
        if (destroyed || !interactive || packageName.isBlank()) return
        val kind = classifyForegroundPackage(packageName)
        val confirmedByAccessibility =
            ForegroundPackagePolicy.isAuthoritativeAccessibilitySignal(source)
        if (
            NonRootUiExecutionPolicy.shouldSuppressTargetSignalBehindBreakPage(
                uiState = uiState,
                signalPackageName = packageName,
                visibleRestrictionPackage = visibleRestrictionPackage,
                breakPageActivityForeground =
                    LimitBlockActivity.isRestrictionPageForegroundFor(packageName),
            )
        ) {
            log(
                packageName,
                "NON_ROOT_DUPLICATE_UI_SUPPRESSED",
                "kind=break_page, reason=target_window_behind_active_page",
            )
            return
        }
        if (kind.isTransientSurface) {
            val surfaceGeneration = foregroundGeneration.incrementAndGet()
            foregroundExecutionSnapshot = ForegroundExecutionSnapshot(
                packageName = packageName,
                kind = kind,
                source = source,
                generation = surfaceGeneration,
                observedAtMillis = observedAtMillis,
                confirmedByAccessibility = confirmedByAccessibility,
            )
            generation.incrementAndGet()
            cancelPendingActionForForeground(packageName, kind, "transient_surface")
            log(
                packageName,
                "NON_ROOT_FOREGROUND_SIGNAL",
                "source=$source, eventType=$eventType, kind=$kind, accepted=surface_only",
            )
            return
        }
        val previous = foregroundPackage
        if (previous == packageName) {
            if (
                confirmedByAccessibility &&
                foregroundExecutionSnapshot?.confirmedByAccessibility != true
            ) {
                val confirmedGeneration = foregroundGeneration.incrementAndGet()
                foregroundExecutionSnapshot = ForegroundExecutionSnapshot(
                    packageName = packageName,
                    kind = kind,
                    source = source,
                    generation = confirmedGeneration,
                    observedAtMillis = observedAtMillis,
                    confirmedByAccessibility = true,
                )
                lastForegroundSignal = ForegroundSignalSnapshot(
                    packageName = packageName,
                    source = source,
                    observedAtMillis = observedAtMillis,
                    acceptedAtElapsedMillis = SystemClock.elapsedRealtime(),
                )
                generation.incrementAndGet()
                if (kind == ForegroundPackageKind.TARGET_APP) {
                    scheduleEvaluation(packageName, 0L)
                }
            }
            if (kind == ForegroundPackageKind.HOME) {
                cancelPendingActionForForeground(packageName, kind, "home_repeated")
            }
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        lastForegroundSignal = ForegroundSignalSnapshot(
            packageName = packageName,
            source = source,
            observedAtMillis = observedAtMillis,
            acceptedAtElapsedMillis = nowElapsed,
        )
        statusRepository.recordForegroundAccepted(source, observedAtMillis)
        log(
            packageName,
            "NON_ROOT_FOREGROUND_SIGNAL",
            "source=$source, eventType=$eventType, kind=$kind, previous=${previous.orEmpty()}",
        )
        if (previous != null) pauseSession(previous, nowElapsed)
        cancelPendingActionForForeground(packageName, kind, "foreground_changed")
        overlay.dismiss("foreground_changed:$previous->$packageName")
        if (
            uiState == NonRootUiExecutionState.BREAK_PAGE_VISIBLE &&
            packageName != appContext.packageName
        ) {
            visibleRestrictionPackage = null
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                packageName,
                "break_page_backgrounded",
            )
        } else if (!uiState.isRestrictionUi) {
            transitionUiState(NonRootUiExecutionState.IDLE, packageName, "foreground_changed")
        }
        foregroundPackage = packageName
        foregroundDetectedAtMillis = observedAtMillis
        val acceptedForegroundGeneration = foregroundGeneration.incrementAndGet()
        foregroundExecutionSnapshot = ForegroundExecutionSnapshot(
            packageName = packageName,
            kind = kind,
            source = source,
            generation = acceptedForegroundGeneration,
            observedAtMillis = observedAtMillis,
            confirmedByAccessibility = confirmedByAccessibility,
        )
        generation.incrementAndGet()
        if (kind == ForegroundPackageKind.HOME) {
            log(
                packageName,
                "NON_ROOT_HOME_FOREGROUND_ACCEPTED",
                "source=$source, eventType=$eventType, previous=${previous.orEmpty()}",
            )
            return
        }
        if (
            packageName == appContext.packageName
        ) return
        val settings = repository.getGlobalSettings()
        val rule = repository.getRule(packageName)
        val group = repository.groupForPackage(packageName)
        if (!hasEffectiveRule(rule, group)) {
            clearActiveRestriction(packageName, "foreground_without_rule")
            return
        }
        if (!settings.protectionMode.usesNonRoot) {
            stopNonRootForSelectedMode(packageName)
            log(
                packageName,
                "NON_ROOT_FOREGROUND_IGNORED",
                "reason=selected_mode_${settings.protectionMode}",
                "WARN",
            )
            return
        }
        log(
            packageName,
            "NON_ROOT_FOREGROUND_ACCEPTED",
            "source=${source.name}, eventType=$eventType, previous=${previous.orEmpty()}, " +
                "ruleVersion=${rule.version}, groupVersion=${group?.version ?: 0L}",
        )
        if (!deviceUsageRepository.hasUsageAccess()) {
            log(
                packageName,
                "NON_ROOT_PERMISSION_MISSING",
                "permission=PACKAGE_USAGE_STATS",
                "WARN",
            )
            showRuntimeWarning(
                token = "usage_access",
                packageName = packageName,
                message = if (isEnglish(settings)) {
                    "Time Stop basic protection is inactive. Grant usage access."
                } else {
                    "时停普通保护未生效，请授予使用情况访问权限"
                },
            )
            return
        }
        if (confirmedByAccessibility) {
            showShizukuFallbackWarning(settings, packageName)
        }
        val restrictionReentry = runtimeStore.hasActiveRestriction(packageName)
        val previousState = (sessions[packageName] ?: runtimeStore.loadSession(packageName))
            ?.takeIf { it.protectionModeGeneration == settings.protectionModeGeneration }
        if (previousState == null) {
            runtimeStore.clearSession(packageName)
        }
        val resumed = NonRootSessionPolicy.foreground(
            previousState,
            packageName,
            nowElapsed,
            protectionModeGeneration = settings.protectionModeGeneration,
        )
        if (previousState?.sessionId != resumed.sessionId) {
            planPromptAttempts.remove(packageName)
        }
        sessions[packageName] = resumed
        persistSession(resumed, "foreground")
        splitActiveSessionAtDayBoundary(packageName, nowElapsed)
        if (restrictionReentry) {
            log(packageName, "NON_ROOT_RESTRICTION_REENTRY", "revalidating restriction")
        }
        scheduleEvaluation(packageName, 0L)
    }

    private fun handleCompatibilitySignal(
        packageName: String,
        eventType: Int,
        observedAtMillis: Long,
    ) {
        if (destroyed || !interactive || packageName.isBlank()) return
        if (classifyForegroundPackage(packageName).isTransientSurface) return
        if (packageName == foregroundPackage) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < (compatibilityRetryAfterElapsedMillis[packageName] ?: 0L)) {
            if (recordTransientIncident("compat-backoff|$packageName|${nowElapsed / 1_000L}")) {
                log(
                    packageName,
                    "NON_ROOT_SIGNAL_DEBOUNCED",
                    "source=ACCESSIBILITY_CONTENT_COMPAT, reason=reconcile_backoff",
                )
            }
            return
        }
        val signalKey = "${ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT}:$packageName"
        val lastAccepted = lastSignalElapsedMillis[signalKey]
        if (
            ForegroundSignalPolicy.shouldDebounce(
                source = ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                packageName = packageName,
                currentPackageName = foregroundPackage,
                lastAcceptedElapsedMillis = lastAccepted,
                nowElapsedMillis = nowElapsed,
            )
        ) {
            compatibilityCandidate = ForegroundSignalPolicy.updateCompatibilityCandidate(
                previous = compatibilityCandidate,
                packageName = packageName,
                nowElapsedMillis = nowElapsed,
            )
            if (recordTransientIncident("compat-debounce|$packageName|${nowElapsed / 1_000L}")) {
                log(
                    packageName,
                    "NON_ROOT_SIGNAL_DEBOUNCED",
                    "source=ACCESSIBILITY_CONTENT_COMPAT, eventType=$eventType",
                )
            }
            return
        }
        lastSignalElapsedMillis[signalKey] = nowElapsed
        compatibilityCandidate = ForegroundSignalPolicy.updateCompatibilityCandidate(
            previous = compatibilityCandidate,
            packageName = packageName,
            nowElapsedMillis = nowElapsed,
        )
        val captured = compatibilityCandidate ?: return
        if (captured.count >= 2) {
            compatibilityCandidate = null
            compatibilityRetryAfterElapsedMillis.remove(packageName)
            acceptForegroundSignal(
                packageName = packageName,
                eventType = eventType,
                source = ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                observedAtMillis = observedAtMillis,
            )
            return
        }
        handler.postDelayed(
            {
                if (
                    destroyed ||
                    compatibilityCandidate?.packageName != captured.packageName
                ) return@postDelayed
                executor.execute {
                    val usage = deviceUsageRepository.recentForegroundSnapshot()
                    handler.post {
                        val latest = compatibilityCandidate
                        if (
                            destroyed ||
                            latest?.packageName != captured.packageName
                        ) return@post
                        val confirmed = ForegroundSignalPolicy.compatibilityCandidateConfirmed(
                            candidate = latest,
                            usagePackageName = usage?.packageName,
                        )
                        statusRepository.recordUsageReconciliation(
                            matched = usage?.packageName == latest.packageName,
                        )
                        compatibilityCandidate = null
                        if (confirmed) {
                            compatibilityRetryAfterElapsedMillis.remove(latest.packageName)
                            val usageAgrees = usage?.packageName == latest.packageName
                            log(
                                latest.packageName,
                                "NON_ROOT_FOREGROUND_RECONCILED",
                                "source=compatibility, confirmedBy=" +
                                    if (usageAgrees) {
                                        "usage"
                                    } else {
                                        "stable_events, usage=${usage?.packageName.orEmpty()}"
                                    },
                            )
                            acceptForegroundSignal(
                                packageName = latest.packageName,
                                eventType = eventType,
                                source =
                                    ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                                observedAtMillis = usage
                                    ?.takeIf { usageAgrees }
                                    ?.observedAtMillis
                                    ?: observedAtMillis,
                            )
                        } else {
                            val mismatchAtElapsedMillis = SystemClock.elapsedRealtime()
                            val mismatchLogBucket =
                                mismatchAtElapsedMillis /
                                    COMPATIBILITY_MISMATCH_LOG_WINDOW_MILLIS
                            compatibilityRetryAfterElapsedMillis[latest.packageName] =
                                mismatchAtElapsedMillis + COMPATIBILITY_RECONCILE_BACKOFF_MILLIS
                            if (
                                recordTransientIncident(
                                    "compat-mismatch|${latest.packageName}|$mismatchLogBucket",
                                )
                            ) {
                                log(
                                    latest.packageName,
                                    "NON_ROOT_FOREGROUND_MISMATCH",
                                    "candidate=${latest.packageName}, " +
                                        "usage=${usage?.packageName.orEmpty()}",
                                    "WARN",
                                )
                            }
                        }
                    }
                }
            },
            ForegroundSignalPolicy.COMPATIBILITY_CONFIRM_DELAY_MILLIS,
        )
    }

    fun onScreenInteractiveChanged(isInteractive: Boolean) {
        if (interactive == isInteractive) return
        interactive = isInteractive
        val packageName = foregroundPackage
        if (packageName == null) {
            if (isInteractive) recoverForegroundFromUsageStats()
            return
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!isInteractive) {
            pauseSession(packageName, nowElapsed)
            overlay.dismiss()
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                packageName,
                "screen_not_interactive",
            )
            foregroundPackage = null
            foregroundExecutionSnapshot = null
            foregroundDetectedAtMillis = 0L
            foregroundGeneration.incrementAndGet()
            generation.incrementAndGet()
        } else {
            // Do not resume the package that was foreground before the screen turned off. The
            // lock screen, launcher or another app may now be on top; a one-shot UsageEvents
            // reconciliation will establish the real foreground package.
            recoverForegroundFromUsageStats()
        }
    }

    fun onTimeEnvironmentChanged() {
        val packageName = foregroundPackage ?: return
        splitActiveSessionAtDayBoundary(packageName, SystemClock.elapsedRealtime())
        scheduleEvaluation(packageName, 0L)
    }

    fun shouldIgnoreOwnOverlayWindowEvents(): Boolean =
        overlay.shouldIgnoreOwnWindowEvents

    fun onRestrictionPageShown(targetPackage: String, attemptId: String): Boolean {
        if (destroyed || targetPackage.isBlank()) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val pending = pendingBreakPageAttempt
        val confirmation = NonRootUiExecutionPolicy.classifyConfirmation(
            pendingAttemptId = pending?.attemptId,
            pendingPackageName = pending?.packageName,
            confirmedAttemptId = attemptId,
            confirmedPackageName = targetPackage,
        )
        if (confirmation != BreakPageConfirmation.MATCHED) {
            log(
                targetPackage,
                "NON_ROOT_PENDING_ACTION_CANCELLED",
                "stage=break_page_confirmation, confirmation=$confirmation, " +
                    "attempt=${attemptId.take(12)}, pending=${pending?.attemptId?.take(12).orEmpty()}",
                "WARN",
            )
            return false
        }
        pendingBreakPageAttempt = null
        val restrictionPersisted = runtimeStore.markRestrictionActive(targetPackage)
        if (foregroundPackage == targetPackage) {
            pauseSession(targetPackage, nowElapsed)
        }
        overlay.dismiss("restriction_page_foreground")
        transitionUiState(
            NonRootUiExecutionState.BREAK_PAGE_VISIBLE,
            targetPackage,
            "break_page_confirmed:$confirmation",
        )
        visibleRestrictionPackage = targetPackage
        foregroundPackage = appContext.packageName
        foregroundExecutionSnapshot = ForegroundExecutionSnapshot(
            packageName = appContext.packageName,
            kind = ForegroundPackageKind.TIME_STOP_ACTIVITY,
            source = ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE,
            generation = foregroundGeneration.incrementAndGet(),
            observedAtMillis = System.currentTimeMillis(),
            confirmedByAccessibility = true,
        )
        generation.incrementAndGet()
        log(
            targetPackage,
            "NON_ROOT_BREAK_PAGE_CONFIRMED",
            "attempt=${attemptId.take(12)}, confirmation=$confirmation, " +
                "elapsed=${pending?.let { nowElapsed - it.requestedAtElapsedMillis } ?: -1L}ms, " +
                "restrictionPersisted=$restrictionPersisted",
            if (confirmation == BreakPageConfirmation.MATCHED) "INFO" else "WARN",
        )
        if (!restrictionPersisted) {
            log(
                targetPackage,
                "NON_ROOT_RESTRICTION_STATE_PERSIST_FAILED",
                "stage=break_page_confirmed",
                "ERROR",
            )
        }
        return true
    }

    fun onRestrictionPageClosed(targetPackage: String) {
        if (destroyed || targetPackage.isBlank()) return
        if (visibleRestrictionPackage == targetPackage) visibleRestrictionPackage = null
        if (uiState == NonRootUiExecutionState.BREAK_PAGE_VISIBLE) {
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                targetPackage,
                "break_page_closed",
            )
        }
    }

    fun onServiceInterrupted() {
        foregroundPackage?.let { pauseSession(it, SystemClock.elapsedRealtime()) }
        foregroundPackage = null
        foregroundExecutionSnapshot = null
        overlay.dismiss("accessibility_interrupted")
        pendingBreakPageAttempt = null
        visibleRestrictionPackage = null
        transitionUiState(
            NonRootUiExecutionState.IDLE,
            appContext.packageName,
            "accessibility_interrupted",
        )
        generation.incrementAndGet()
        foregroundGeneration.incrementAndGet()
        log(
            appContext.packageName,
            "NON_ROOT_SERVICE_INTERRUPTED",
            "foreground session paused",
            "WARN",
        )
    }

    fun destroy() {
        destroyed = true
        restoreVisiblePlanAfterServiceRestart()
        foregroundPackage?.let { pauseSession(it, SystemClock.elapsedRealtime()) }
        overlay.dismiss("coordinator_destroyed")
        pendingBreakPageAttempt = null
        visibleRestrictionPackage = null
        transitionUiState(
            NonRootUiExecutionState.IDLE,
            appContext.packageName,
            "coordinator_destroyed",
        )
        handler.removeCallbacksAndMessages(null)
        runCatching {
        }
        executor.shutdownNow()
    }

    private fun restoreVisiblePlanAfterServiceRestart() {
        val packageName = foregroundPackage ?: return
        val current = sessions[packageName] ?: runtimeStore.loadSession(packageName) ?: return
        if (
            !NonRootUiRecoveryPolicy.shouldRestorePlanAfterServiceRestart(
                uiState = uiState,
                planPromptHandled = current.planPromptHandled,
                planActive = current.planActive,
            )
        ) return
        val retryable = current.copy(planPromptHandled = false)
        sessions[packageName] = retryable
        persistSession(retryable, "plan_service_restart")
        log(
            packageName,
            "NON_ROOT_OVERLAY_DETACHED",
            "kind=SESSION_PLAN, reason=service_restart, session=${current.sessionId}",
            "WARN",
        )
    }

    private fun pauseSession(packageName: String, nowElapsed: Long) {
        val state = sessions[packageName] ?: runtimeStore.loadSession(packageName) ?: return
        if (state.foregroundStartedAtElapsedMillis <= 0L) return
        val paused = NonRootSessionPolicy.background(state, nowElapsed)
        val segmentDayToken = state.foregroundDayToken.ifBlank {
            LocalDate.now().toString()
        }
        val segment = (
            paused.accumulatedForegroundMillis - state.accumulatedForegroundMillis
            ).coerceAtLeast(0L)
        sessions[packageName] = paused
        persistSession(paused, "background")
        recordForegroundDuration(packageName, segment, segmentDayToken)
    }

    private fun stopNonRootForSelectedMode(packageName: String) {
        generation.incrementAndGet()
        pauseSession(packageName, SystemClock.elapsedRealtime())
        sessions.remove(packageName)
        clearPersistedSession(packageName, "selected_xposed_mode")
        clearActiveRestriction(packageName, "selected_xposed_mode")
        overlay.dismiss("selected_xposed_mode")
        pendingBreakPageAttempt = null
        visibleRestrictionPackage = null
        transitionUiState(
            NonRootUiExecutionState.IDLE,
            packageName,
            "selected_xposed_mode",
        )
    }

    private fun splitActiveSessionAtDayBoundary(
        packageName: String,
        nowElapsed: Long,
    ) {
        val state = sessions[packageName] ?: runtimeStore.loadSession(packageName) ?: return
        val today = LocalDate.now().toString()
        if (
            state.foregroundStartedAtElapsedMillis <= 0L ||
            state.foregroundDayToken.isBlank() ||
            state.foregroundDayToken == today
        ) return
        val nowWallMillis = System.currentTimeMillis()
        val todayStartWallMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val split = NonRootSessionPolicy.splitActiveSegmentAtDayBoundary(
            state = state,
            nowElapsedMillis = nowElapsed,
            elapsedSinceCurrentDayStartMillis =
                (nowWallMillis - todayStartWallMillis).coerceAtLeast(0L),
        )
        val paused = NonRootSessionPolicy.background(state, nowElapsed)
        sessions[packageName] = paused
        persistSession(paused, "day_rollover_pause")
        recordForegroundDuration(
            packageName,
            split.previousDayMillis,
            state.foregroundDayToken,
        )
        recordForegroundDuration(packageName, split.currentDayMillis, today)
        val resumed = NonRootSessionPolicy.foreground(
            state = paused,
            packageName = packageName,
            nowElapsedMillis = nowElapsed,
            dayToken = today,
            protectionModeGeneration = repository.getGlobalSettings()
                .protectionModeGeneration,
        )
        sessions[packageName] = resumed
        persistSession(resumed, "day_rollover_resume")
        log(
            packageName,
            "NON_ROOT_DAY_ROLLOVER",
            "from=${state.foregroundDayToken}, to=$today, " +
                "previousMs=${split.previousDayMillis}, currentMs=${split.currentDayMillis}",
        )
    }

    private fun recordForegroundDuration(
        packageName: String,
        durationMillis: Long,
        dayToken: String,
    ) {
        if (durationMillis <= 0L) return
        val settings = repository.getGlobalSettings()
        val groupDailyEnabled = repository.groupForPackage(packageName)
            ?.let { it.enabled && packageName in it.packageNames && it.dailyEnabled }
            ?: false
        if (
            !UsageReportingPolicy.shouldReportDuration(
                usageStatsEnabled = settings.usageStatsEnabled,
                groupDailyEnabled = groupDailyEnabled,
            )
        ) return
        if (
            !usageRepository.record(
                packageName = packageName,
                durationMillis = durationMillis,
                launchIncrement = 0,
                limitHitIncrement = 0,
                hookVersionCode = 0,
                dayToken = dayToken,
            )
        ) {
            log(
                packageName,
                "NON_ROOT_USAGE_PERSIST_FAILED",
                "day=$dayToken, durationMs=$durationMillis",
                "ERROR",
            )
        }
    }

    private fun scheduleEvaluation(
        packageName: String,
        delayMillis: Long,
    ) {
        val capturedGeneration = generation.incrementAndGet()
        handler.postDelayed(
            {
                if (!isCurrent(packageName, capturedGeneration)) return@postDelayed
                evaluateAsync(packageName, capturedGeneration)
            },
            delayMillis.coerceAtLeast(0L),
        )
    }

    private fun evaluateAsync(
        packageName: String,
        capturedGeneration: Long,
    ) {
        val session = sessions[packageName] ?: runtimeStore.loadSession(packageName) ?: return
        val detectedAt = foregroundDetectedAtMillis
        executor.execute {
            val result = evaluateRule(packageName, session, detectedAt)
            val recentForeground = if (needsUiPreflight(result)) {
                deviceUsageRepository.recentForegroundSnapshot()
            } else {
                null
            }
            handler.post {
                if (isCurrent(packageName, capturedGeneration)) {
                    if (
                        recentForeground != null &&
                        ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                            current = lastForegroundSignal,
                            usagePackageName = recentForeground.packageName,
                            usageObservedAtMillis = recentForeground.observedAtMillis,
                        )
                    ) {
                        statusRepository.recordUsageReconciliation(matched = false)
                        log(
                            packageName,
                            "NON_ROOT_FOREGROUND_MISMATCH",
                            "stage=ui_preflight, expected=$packageName, " +
                                "usage=${recentForeground.packageName}, " +
                                "observedAt=${recentForeground.observedAtMillis}",
                            "WARN",
                        )
                        acceptForegroundSignal(
                            packageName = recentForeground.packageName,
                            eventType = 0,
                            source = ForegroundSignalSource.USAGE_STATS_RECONCILE,
                            observedAtMillis = recentForeground.observedAtMillis,
                        )
                        return@post
                    }
                    if (recentForeground?.packageName == packageName) {
                        statusRepository.recordUsageReconciliation(matched = true)
                        log(
                            packageName,
                            "NON_ROOT_FOREGROUND_RECONCILED",
                            "stage=ui_preflight, source=UsageStats",
                        )
                    }
                    applyEvaluation(result)
                }
            }
        }
    }

    private fun needsUiPreflight(result: EvaluationResult): Boolean =
        result.decision.blockingReason != null ||
            (
                result.rule.sessionPlanningEnabled &&
                    result.group == null &&
                    !result.session.planPromptHandled
                )

    private fun evaluateRule(
        packageName: String,
        session: NonRootSessionState,
        detectedAtMillis: Long,
    ): EvaluationResult {
        val settings = repository.getGlobalSettings()
        val rule = repository.getRule(packageName)
        val assignedGroup = repository.groupForPackage(packageName)
        val group = assignedGroup?.takeIf {
            it.enabled && packageName in it.packageNames
        }
        val personal = assignedGroup == null
        val trackedPackages = if (group?.dailyEnabled == true) {
            group.packageNames
        } else {
            setOf(packageName)
        }
        val systemSummaries = if (deviceUsageRepository.hasUsageAccess()) {
            deviceUsageRepository.todayUsageSummaries(trackedPackages)
        } else {
            emptyMap()
        }
        val moduleSummaries = usageRepository.summariesToday(trackedPackages)
            .associateBy { it.packageName }
        val nowElapsed = SystemClock.elapsedRealtime()
        val sessionUsed = NonRootSessionPolicy.foregroundUsedMillis(session, nowElapsed)
        val activeSegment = (
            sessionUsed - session.accumulatedForegroundMillis
            ).coerceAtLeast(0L)
        val appModuleUsed = safeAdd(
            moduleSummaries[packageName]?.durationMillis ?: 0L,
            activeSegment,
        )
        val appDailyUsed = maxOf(
            systemSummaries[packageName]?.durationMillis ?: 0L,
            appModuleUsed,
        )
        val groupDailyUsed = group?.let { activeGroup ->
            GroupUsagePolicy.authoritativeTotalMillis(
                packageNames = activeGroup.packageNames,
                systemDurations = systemSummaries.mapValues { it.value.durationMillis },
                moduleDurations = moduleSummaries.mapValues { entry ->
                    if (entry.key == packageName) {
                        safeAdd(entry.value.durationMillis, activeSegment)
                    } else {
                        entry.value.durationMillis
                    }
                },
            )
        } ?: 0L
        val constraints = buildList {
            if (personal && rule.scheduleEnabled) {
                add(ScheduleConstraint(rule.scheduleMode, rule.scheduleWindows))
            }
            if (group?.scheduleEnabled == true) {
                add(ScheduleConstraint(group.scheduleMode, group.scheduleWindows))
            }
        }
        val scheduleNow = ZonedDateTime.now()
        val scheduleDecision = ScheduleEvaluator.evaluateAll(constraints, scheduleNow)
        val scheduleIncidentToken = if (constraints.isNotEmpty() && !scheduleDecision.allowed) {
            val scheduleMode = group?.scheduleMode ?: rule.scheduleMode
            "schedule:$packageName:" + ScheduleBlockPolicy.token(
                ruleVersion = 31L * rule.version + (group?.version ?: 0L),
                mode = scheduleMode,
                nextTransitionEpochMillis = scheduleDecision.nextTransition
                    ?.toInstant()
                    ?.toEpochMilli(),
            )
        } else {
            ""
        }
        val nowMillis = System.currentTimeMillis()
        runtimeStore.consumeExpiredAppCooldown(packageName, nowMillis)
        group?.let { repository.consumeExpiredGroupCooldown(it.id, nowMillis) }
        val appCooldownEnd = if (personal && rule.cooldownEnabled) {
            runtimeStore.getAppCooldown(packageName).endsAtMillis
        } else {
            0L
        }
        val groupCooldownEnd = group?.takeIf { it.cooldownEnabled }
            ?.let { repository.getGroupCooldownRecord(it.id).endsAtMillis }
            ?: 0L
        val effectiveCooldownEnd = maxOf(appCooldownEnd, groupCooldownEnd)
        val snapshot = NonRootRuleSnapshot(
            scheduleBlocked = constraints.isNotEmpty() && !scheduleDecision.allowed,
            cooldownRemainingMillis = (effectiveCooldownEnd - nowMillis).coerceAtLeast(0L),
            appDailyEnabled = personal && rule.dailyEnabled,
            appDailyUsedMillis = appDailyUsed,
            appDailyLimitMillis = safeLimitMillis(rule.dailyLimitSeconds),
            appPerSessionEnabled = personal && rule.perLaunchEnabled,
            appPerSessionLimitMillis = safeLimitMillis(rule.perLaunchLimitSeconds),
            groupDailyEnabled = group?.dailyEnabled == true,
            groupDailyUsedMillis = groupDailyUsed,
            groupDailyLimitMillis = safeLimitMillis(
                group?.dailyLimitSeconds ?: RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS,
            ),
            groupPerSessionEnabled = group?.perLaunchEnabled == true,
            groupPerSessionLimitMillis = safeLimitMillis(
                group?.perLaunchLimitSeconds ?: RuleRepository.DEFAULT_LIMIT_SECONDS,
            ),
            sessionUsedMillis = sessionUsed,
            planActive = session.planActive,
            planRemainingMillis = NonRootSessionPolicy.planRemainingMillis(session, nowElapsed),
        )
        return EvaluationResult(
            packageName = packageName,
            foregroundDetectedAtMillis = detectedAtMillis,
            settings = settings,
            rule = rule,
            group = group,
            session = session,
            decision = NonRootRuleEvaluator.evaluate(snapshot),
            scheduleNextTransitionMillis = scheduleDecision.millisUntilTransition(
                scheduleNow,
            ),
            scheduleIncidentToken = scheduleIncidentToken,
            cooldownEndsAtMillis = effectiveCooldownEnd,
        )
    }

    private fun applyEvaluation(result: EvaluationResult) {
        val settings = repository.getGlobalSettings()
        if (!settings.protectionMode.usesNonRoot) {
            stopNonRootForSelectedMode(result.packageName)
            return
        }
        if (
            !NonRootProtectionStatusRepository.isAccessibilityConnected(appContext) ||
            !deviceUsageRepository.hasUsageAccess()
        ) return
        val latestRule = repository.getRule(result.packageName)
        val latestAssignedGroup = repository.groupForPackage(result.packageName)
        val latestGroup = latestAssignedGroup?.takeIf {
            it.enabled && result.packageName in it.packageNames
        }
        if (!hasEffectiveRule(latestRule, latestAssignedGroup)) {
            pauseSession(result.packageName, SystemClock.elapsedRealtime())
            sessions.remove(result.packageName)
            clearPersistedSession(result.packageName, "rule_removed")
            clearActiveRestriction(result.packageName, "rule_removed")
            overlay.dismiss()
            return
        }
        if (
            settings != result.settings ||
            latestRule != result.rule ||
            latestGroup != result.group
        ) {
            scheduleEvaluation(result.packageName, 0L)
            return
        }
        ensureLaunchRecorded(
            packageName = result.packageName,
            evaluatedSession = result.session,
            usageStatsEnabled = result.settings.usageStatsEnabled,
        )
        log(
            result.packageName,
            "NON_ROOT_RULE_EVALUATED",
            "reason=${result.decision.blockingReason}, " +
                "next=${result.decision.nextThresholdMillis}, " +
                "reached=${result.decision.reachedKinds.joinToString(",")}, " +
                "planEnabled=${result.rule.sessionPlanningEnabled}, " +
                "planHandled=${result.session.planPromptHandled}",
        )
        when (result.decision.blockingReason) {
            null -> {
                clearActiveRestriction(result.packageName, "restriction_released")
                if (
                    result.rule.sessionPlanningEnabled &&
                    result.group == null &&
                    !result.session.planPromptHandled &&
                    !uiState.isRestrictionUi &&
                    !runtimeStore.hasActiveRestriction(result.packageName)
                ) {
                    val promptDelay = ProtectionEnginePolicy.planPromptDelayMillis(
                        foregroundDetectedAtMillis = result.foregroundDetectedAtMillis,
                        nowMillis = System.currentTimeMillis(),
                    )
                    if (promptDelay > 0L) {
                        scheduleEvaluation(result.packageName, promptDelay)
                        return
                    }
                    showPlanPrompt(result)
                    return
                }
                scheduleNext(result)
            }

            else -> enforce(result)
        }
    }

    private fun ensureLaunchRecorded(
        packageName: String,
        evaluatedSession: NonRootSessionState,
        usageStatsEnabled: Boolean,
    ) {
        val current = sessions[packageName] ?: evaluatedSession
        if (current.launchRecorded) return
        val recorded = current.copy(launchRecorded = true)
        sessions[packageName] = recorded
        persistSession(recorded, "launch_recorded")
        if (usageStatsEnabled) {
            if (
                !usageRepository.record(
                    packageName = packageName,
                    durationMillis = 0L,
                    launchIncrement = 1,
                    limitHitIncrement = 0,
                    hookVersionCode = 0,
                    dayToken = LocalDate.now().toString(),
                )
            ) {
                log(
                    packageName,
                    "NON_ROOT_USAGE_PERSIST_FAILED",
                    "kind=launch",
                    "ERROR",
                )
            }
        }
    }

    private fun showPlanPrompt(result: EvaluationResult) {
        val packageName = result.packageName
        if (!mayExecuteDisruptiveAction(packageName, "show_plan_prompt")) return
        if (
            uiState == NonRootUiExecutionState.PLAN_PENDING ||
            uiState == NonRootUiExecutionState.PLAN_VISIBLE
        ) {
            log(
                packageName,
                "NON_ROOT_DUPLICATE_UI_SUPPRESSED",
                "kind=plan state=$uiState",
            )
            return
        }
        if (
            uiState.isRestrictionUi ||
            pendingBreakPageAttempt != null ||
            runtimeStore.hasActiveRestriction(packageName)
        ) {
            log(
                packageName,
                "SESSION_PLAN_SUPPRESSED_BLOCKED",
                "uiState=$uiState, pendingBreak=${pendingBreakPageAttempt != null}",
            )
            scheduleEvaluation(packageName, 0L)
            return
        }
        val attempts = (planPromptAttempts[packageName] ?: 0) + 1
        planPromptAttempts[packageName] = attempts
        val english = isEnglish(result.settings)
        log(
            packageName,
            "NON_ROOT_PLAN_OVERLAY_REQUESTED",
            "attempt=$attempts, maxAllowed=${result.decision.nextThresholdMillis}",
        )
        transitionUiState(
            NonRootUiExecutionState.PLAN_PENDING,
            packageName,
            "plan_request:$attempts",
        )
        val shown = overlay.showPlan(
            packageName = packageName,
            english = english,
            quoteSeed = "non-root-plan:$packageName:${result.session.sessionId}",
            maxAllowedMillis = result.decision.nextThresholdMillis,
            onShown = {
                val current = sessions[packageName] ?: result.session
                val handled = current.copy(planPromptHandled = true)
                sessions[packageName] = handled
                persistSession(handled, "plan_attached")
                transitionUiState(
                    NonRootUiExecutionState.PLAN_VISIBLE,
                    packageName,
                    "plan_attached:$attempts",
                )
                if (attempts == 1) {
                    log(packageName, "SESSION_PLAN_PROMPT", "engine=accessibility")
                } else {
                    log(
                        packageName,
                        "NON_ROOT_OVERLAY_REATTACHED",
                        "attempt=$attempts, session=${handled.sessionId}",
                    )
                }
            },
            onSelected = { durationMillis ->
                transitionUiState(
                    NonRootUiExecutionState.IDLE,
                    packageName,
                    "plan_selected",
                )
                val current = sessions[packageName] ?: result.session
                val planned = NonRootSessionPolicy.withPlan(current, durationMillis)
                sessions[packageName] = planned
                persistSession(planned, "plan_selected")
                planPromptAttempts.remove(packageName)
                log(
                    packageName,
                    "SESSION_PLAN_STARTED",
                    "duration=${durationMillis / 1000}s, engine=accessibility",
                )
                scheduleEvaluation(packageName, 0L)
            },
            onSkipped = {
                transitionUiState(
                    NonRootUiExecutionState.IDLE,
                    packageName,
                    "plan_skipped",
                )
                val current = sessions[packageName] ?: result.session
                val skipped = NonRootSessionPolicy.skipPlan(current)
                sessions[packageName] = skipped
                persistSession(skipped, "plan_skipped")
                planPromptAttempts.remove(packageName)
                log(packageName, "SESSION_PLAN_SKIPPED", "engine=accessibility")
                scheduleEvaluation(packageName, 0L)
            },
            onExit = {
                transitionUiState(
                    NonRootUiExecutionState.IDLE,
                    packageName,
                    "plan_exit",
                )
                val current = sessions[packageName] ?: result.session
                val skipped = NonRootSessionPolicy.skipPlan(current)
                sessions[packageName] = skipped
                persistSession(skipped, "plan_exit")
                planPromptAttempts.remove(packageName)
                log(packageName, "USER_EXIT_REQUESTED", "source=non_root_plan_overlay")
                fallbackToHome(
                    packageName = packageName,
                    settings = result.settings,
                    stage = "user_exit_plan_overlay",
                    retry = false,
                    showToast = false,
                )
            },
            onUnexpectedDetach = {
                handleUnexpectedPlanDetach(
                    packageName = packageName,
                    sessionId = result.session.sessionId,
                )
            },
        )
        if (!shown) {
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                packageName,
                "plan_add_failed:$attempts",
            )
            if (attempts < MAX_PLAN_PROMPT_ATTEMPTS) {
                scheduleEvaluation(
                    packageName,
                    planRetryDelayMillis(attempts),
                )
            } else {
                log(packageName, "SESSION_PLAN_PROMPT_FAILED", "attempts=$attempts")
                fallbackToHome(
                    packageName = packageName,
                    settings = result.settings,
                    stage = "session_plan_prompt_failed",
                    toastEnglish =
                        "Unable to show the session plan. Check Time Stop settings.",
                    toastChinese = "无法显示本次计划，请检查时停设置",
                )
            }
        } else {
            // The permanent quota and schedule continue to apply while the user is deciding.
            // Otherwise leaving this overlay open would pause all threshold checks indefinitely.
            scheduleNext(result)
        }
    }

    private fun handleUnexpectedPlanDetach(
        packageName: String,
        sessionId: String,
    ) {
        val current = sessions[packageName] ?: runtimeStore.loadSession(packageName) ?: return
        val attempts = planPromptAttempts[packageName] ?: 1
        val persistentRestrictionActive = runtimeStore.hasActiveRestriction(packageName)
        if (
            uiState == NonRootUiExecutionState.PLAN_PENDING ||
            uiState == NonRootUiExecutionState.PLAN_VISIBLE
        ) {
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                packageName,
                "plan_unexpected_detach:$attempts",
            )
        }
        if (
            destroyed ||
            !NonRootUiRecoveryPolicy.shouldRetryPlanDetach(
                attemptsCompleted = attempts,
                maxAttempts = MAX_PLAN_PROMPT_ATTEMPTS,
                sameForegroundPackage = foregroundPackage == packageName,
                sameSession = current.sessionId == sessionId,
                restrictionUiActive = uiState.isRestrictionUi,
                persistentRestrictionActive = persistentRestrictionActive,
            )
        ) {
            if (
                attempts >= MAX_PLAN_PROMPT_ATTEMPTS &&
                foregroundPackage == packageName &&
                current.sessionId == sessionId &&
                !uiState.isRestrictionUi &&
                !persistentRestrictionActive
            ) {
                transitionUiState(
                    NonRootUiExecutionState.IDLE,
                    packageName,
                    "plan_unexpected_detach:$attempts",
                )
                log(
                    packageName,
                    "SESSION_PLAN_PROMPT_FAILED",
                    "reason=unexpected_detach, attempts=$attempts",
                    "ERROR",
                )
                fallbackToHome(
                    packageName = packageName,
                    settings = repository.getGlobalSettings(),
                    stage = "session_plan_detached",
                )
            }
            return
        }
        log(
            packageName,
            "NON_ROOT_OVERLAY_DETACHED",
            "kind=SESSION_PLAN, attempt=$attempts, session=$sessionId",
            "WARN",
        )
        val retryable = current.copy(planPromptHandled = false)
        sessions[packageName] = retryable
        persistSession(retryable, "plan_detached")
        scheduleEvaluation(packageName, planRetryDelayMillis(attempts))
    }

    private fun planRetryDelayMillis(attemptsCompleted: Int): Long =
        NonRootUiRecoveryPolicy.retryDelayMillis(
            attemptsCompleted = attemptsCompleted,
            firstDelayMillis = FIRST_PLAN_REATTACH_MILLIS,
            secondDelayMillis = SECOND_PLAN_REATTACH_MILLIS,
        )

    private fun scheduleNext(result: EvaluationResult) {
        val packageName = result.packageName
        val threshold = result.decision.nextThresholdMillis
        val scheduleTransition = result.scheduleNextTransitionMillis
        val next = listOfNotNull(threshold, scheduleTransition)
            .filter { it > 0L }
            .minOrNull()
            ?: return
        scheduleEvaluation(packageName, next + THRESHOLD_SLOP_MILLIS)
        if (
            result.session.planActive &&
            result.decision.sessionPlanIsNextThreshold &&
            threshold != null &&
            threshold > WARNING_LEAD_MILLIS
        ) {
            val capturedSessionId = result.session.sessionId
            val capturedGeneration = generation.get()
            handler.postDelayed(
                {
                    val current = sessions[packageName] ?: return@postDelayed
                    if (
                        !isCurrent(packageName, capturedGeneration) ||
                        current.sessionId != capturedSessionId ||
                        !current.planActive
                    ) return@postDelayed
                    overlay.showExpiryWarning(
                        packageName = packageName,
                        english = isEnglish(result.settings),
                        onReplan = {
                            val latest = sessions[packageName] ?: return@showExpiryWarning
                            val remaining = NonRootSessionPolicy.planRemainingMillis(
                                latest,
                                SystemClock.elapsedRealtime(),
                            )
                            val replanning = latest.copy(
                                planActive = false,
                                planRemainingMillis = remaining,
                                planPromptHandled = false,
                            )
                            sessions[packageName] = replanning
                            persistSession(replanning, "plan_replanned")
                            log(packageName, "SESSION_PLAN_REPLANNED", "engine=accessibility")
                            scheduleEvaluation(packageName, 0L)
                        },
                        onExit = {
                            log(packageName, "USER_EXIT_REQUESTED", "source=non_root_plan_warning")
                            fallbackToHome(
                                packageName = packageName,
                                settings = result.settings,
                                stage = "user_exit_plan_warning",
                                retry = false,
                                showToast = false,
                            )
                        },
                    )
                },
                threshold - WARNING_LEAD_MILLIS,
            )
        }
    }

    private fun enforce(result: EvaluationResult) {
        if (!mayExecuteDisruptiveAction(result.packageName, "enforce_limit")) return
        val packageName = result.packageName
        overlay.dismiss()
        transitionUiState(
            NonRootUiExecutionState.IDLE,
            packageName,
            "enforcement_started",
        )
        val reason = result.decision.blockingReason ?: return
        if (reason != NonRootBlockReason.SESSION_PLAN) {
            if (!runtimeStore.markRestrictionActive(packageName)) {
                log(
                    packageName,
                    "NON_ROOT_RESTRICTION_STATE_PERSIST_FAILED",
                    "stage=enforce, reason=$reason",
                    "ERROR",
                )
            }
        }
        val nowMillis = System.currentTimeMillis()
        var cooldownEnd = result.cooldownEndsAtMillis
        var incidentId = ""
        var newIncident = false
        if (reason == NonRootBlockReason.QUOTA) {
            incidentId = QuotaIncidentPolicy.incidentId(
                packageName = packageName,
                ruleVersion = result.rule.version,
                groupId = result.group?.id.orEmpty(),
                groupVersion = result.group?.version ?: 0L,
                dayToken = LocalDate.now().toString(),
                processSessionId = result.session.sessionId,
                reachedKinds = result.decision.reachedKinds,
            ).orEmpty()
            if (incidentId.isNotBlank()) {
                val durationMillis = (
                    result.group?.takeIf { it.cooldownEnabled }?.cooldownSeconds
                        ?: result.rule.takeIf {
                            result.group == null && it.cooldownEnabled
                        }?.cooldownSeconds
                        ?: 0L
                    ).coerceIn(
                    0L,
                    RuleRepository.MAX_COOLDOWN_SECONDS,
                ) * 1_000L
                if (result.group != null) {
                    runCatching {
                        repository.claimGroupCooldown(
                            groupId = result.group.id,
                            incidentId = incidentId,
                            sourcePackage = packageName,
                            occurredAtMillis = nowMillis,
                            durationMillis = durationMillis,
                            nowMillis = nowMillis,
                        )
                    }.onSuccess { claim ->
                        cooldownEnd = claim.record.endsAtMillis
                        newIncident = claim.isNewIncident
                    }.onFailure { error ->
                        newIncident = recordTransientIncident(
                            "quota-claim-failed|$incidentId",
                        )
                        log(
                            packageName,
                            "NON_ROOT_COOLDOWN_CLAIM_FAILED",
                            "scope=group, incident=$incidentId, " +
                                "error=${error.javaClass.simpleName}",
                            "ERROR",
                        )
                    }
                } else {
                    runCatching {
                        runtimeStore.claimAppCooldown(
                            packageName = packageName,
                            incidentId = incidentId,
                            occurredAtMillis = nowMillis,
                            durationMillis = durationMillis,
                            nowMillis = nowMillis,
                        )
                    }.onSuccess { claim ->
                        cooldownEnd = claim.record.endsAtMillis
                        newIncident = claim.isNewIncident
                    }.onFailure { error ->
                        newIncident = recordTransientIncident(
                            "quota-claim-failed|$incidentId",
                        )
                        log(
                            packageName,
                            "NON_ROOT_COOLDOWN_CLAIM_FAILED",
                            "scope=app, incident=$incidentId, " +
                                "error=${error.javaClass.simpleName}",
                            "ERROR",
                        )
                    }
                }
            }
        }
        val scheduleIncidentIsNew =
            if (
                reason == NonRootBlockReason.SCHEDULE &&
                result.scheduleIncidentToken.isNotBlank()
            ) {
                runCatching {
                    runtimeStore.claimLimitIncident(result.scheduleIncidentToken)
                }.getOrElse { error ->
                    log(
                        packageName,
                        "NON_ROOT_LIMIT_INCIDENT_PERSIST_FAILED",
                        "incident=${result.scheduleIncidentToken}, " +
                            "error=${error.javaClass.simpleName}",
                        "ERROR",
                    )
                    recordTransientIncident(result.scheduleIncidentToken)
                }
            } else {
                false
            }
        val recordHit = NonRootLimitHitPolicy.shouldRecord(
            reason = reason,
            quotaIncidentIsNew = newIncident,
            scheduleIncidentIsNew = scheduleIncidentIsNew,
        )
        if (recordHit && result.settings.usageStatsEnabled) {
            if (
                !usageRepository.record(
                    packageName = packageName,
                    durationMillis = 0L,
                    launchIncrement = 0,
                    limitHitIncrement = 1,
                    hookVersionCode = 0,
                    dayToken = LocalDate.now().toString(),
                )
            ) {
                log(
                    packageName,
                    "NON_ROOT_USAGE_PERSIST_FAILED",
                    "kind=limit_hit, reason=$reason",
                    "ERROR",
                )
            }
        }
        pauseSession(packageName, SystemClock.elapsedRealtime())
        val pausedSession = sessions[packageName]
        val perSessionReached =
            QuotaKind.APP_PER_LAUNCH in result.decision.reachedKinds ||
                QuotaKind.GROUP_PER_LAUNCH in result.decision.reachedKinds
        val dailyReached =
            QuotaKind.APP_DAILY in result.decision.reachedKinds ||
                QuotaKind.GROUP_DAILY in result.decision.reachedKinds
        val sessionResetAtMillis = NonRootSessionResetPolicy.resetAtMillis(
            perSessionReached = perSessionReached,
            nowWallMillis = nowMillis,
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            graceEndsAtElapsedMillis = pausedSession?.graceEndsAtElapsedMillis ?: 0L,
            cooldownEndsAtMillis = cooldownEnd,
        )
        log(
            packageName,
            "NON_ROOT_LIMIT_TRIGGERED",
            "reason=$reason, incident=$incidentId, new=$newIncident, cooldownEnd=$cooldownEnd",
        )
        val finishAction: (Boolean) -> Unit = { targetForceStopped ->
            if (reason == NonRootBlockReason.SESSION_PLAN) {
                if (foregroundPackage == packageName) {
                    fallbackToHome(
                        packageName = packageName,
                        settings = result.settings,
                        stage = "session_plan_expired",
                        toastEnglish = "This session plan has ended",
                        toastChinese = "本次计划时间已结束",
                    )
                }
            } else {
                if (targetForceStopped) {
                    if (
                        perSessionReached &&
                        !dailyReached &&
                        cooldownEnd <= nowMillis
                    ) {
                        sessions.remove(packageName)
                        clearPersistedSession(packageName, "shizuku_force_stop")
                    }
                    log(
                        packageName,
                        "NON_ROOT_SHIZUKU_ENFORCED",
                        "reason=$reason; restriction page not required",
                    )
                    handler.postDelayed(
                        {
                            if (foregroundPackage == packageName) {
                                log(
                                    packageName,
                                    "NON_ROOT_SHIZUKU_FOREGROUND_STALE",
                                    "target still reported foreground after force-stop",
                                    "WARN",
                                )
                                fallbackToHome(
                                    packageName = packageName,
                                    settings = result.settings,
                                    stage = "shizuku_foreground_confirmation",
                                )
                            }
                        },
                        SHIZUKU_FOREGROUND_CONFIRM_MILLIS,
                    )
                } else {
                    if (foregroundPackage != packageName) {
                        log(
                            packageName,
                            "NON_ROOT_BREAK_PAGE_SKIPPED",
                            "target no longer foreground; current=${foregroundPackage.orEmpty()}",
                            "WARN",
                        )
                    } else if (
                        !showBreakPage(
                            result = result,
                            cooldownEndsAtMillis = cooldownEnd,
                            sessionResetAtMillis = sessionResetAtMillis,
                        )
                    ) {
                        fallbackToHome(
                            packageName = packageName,
                            settings = result.settings,
                            stage = "start_exception_or_token_failure",
                        )
                    }
                }
            }
        }
        if (
            result.settings.protectionMode.usesShizuku &&
            shizuku.state.value == ShizukuExecutionState.READY
        ) {
            val completion = AtomicBoolean(false)
            val timeout = Runnable {
                if (!completion.compareAndSet(false, true) || destroyed) return@Runnable
                log(
                    packageName,
                    "SHIZUKU_FORCE_STOP_TIMEOUT",
                    "No result within ${SHIZUKU_EXECUTION_TIMEOUT_MILLIS}ms; " +
                        "falling back to restriction page",
                    "ERROR",
                )
                finishAction(false)
            }
            handler.postDelayed(timeout, SHIZUKU_EXECUTION_TIMEOUT_MILLIS)
            shizuku.forceStop(packageName) { execution ->
                handler.post {
                    if (!completion.compareAndSet(false, true)) return@post
                    handler.removeCallbacks(timeout)
                    if (destroyed) return@post
                    log(
                        packageName,
                        "SHIZUKU_FORCE_STOP",
                        "result=$execution",
                    )
                    finishAction(execution == ShizukuExecutionResult.SUCCESS)
                }
            }
        } else {
            finishAction(false)
        }
    }

    private fun showBreakPage(
        result: EvaluationResult,
        cooldownEndsAtMillis: Long,
        sessionResetAtMillis: Long,
    ): Boolean {
        if (!mayExecuteDisruptiveAction(result.packageName, "show_break_page")) return false
        pendingBreakPageAttempt?.takeIf {
            it.packageName == result.packageName
        }?.let {
            log(
                result.packageName,
                "NON_ROOT_BREAK_PAGE_REQUEST_REUSED",
                "attempt=${it.attemptId.take(12)}, age=" +
                    "${SystemClock.elapsedRealtime() - it.requestedAtElapsedMillis}ms",
            )
            log(
                result.packageName,
                "NON_ROOT_DUPLICATE_UI_SUPPRESSED",
                "kind=break_page attempt=${it.attemptId.take(12)}",
            )
            return true
        }
        log(
            result.packageName,
            "NON_ROOT_BREAK_PAGE_TOKEN_REQUESTED",
            "reason=${result.decision.blockingReason}, " +
                "reached=${result.decision.reachedKinds.joinToString(",")}",
        )
        val tokenCall = runCatching {
            appContext.contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_CREATE_BREAK_SESSION,
                result.packageName,
                Bundle().apply {
                    putBoolean(RuleContract.KEY_BREAK_SESSION_NON_ROOT, true)
                },
            )
        }
        tokenCall.exceptionOrNull()?.let {
            log(
                result.packageName,
                "NON_ROOT_BREAK_PAGE_TOKEN_FAILED",
                describeThrowable(it),
                "ERROR",
            )
        }
        val tokenResult = tokenCall.getOrNull()
        val token = tokenResult?.takeIf {
            it.getBoolean(RuleContract.KEY_OK, false)
        }?.getString(RuleContract.KEY_BREAK_SESSION_TOKEN).orEmpty()
        if (token.isBlank()) {
            log(
                result.packageName,
                "NON_ROOT_BREAK_PAGE_TOKEN_REJECTED",
                "providerOk=${tokenResult?.getBoolean(RuleContract.KEY_OK, false) == true}",
                "ERROR",
            )
            return false
        }
        val english = isEnglish(result.settings)
        val attemptId = UUID.randomUUID().toString()
        val intent = Intent(appContext, LimitBlockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            putExtra(LimitBlockActivity.EXTRA_TARGET_PACKAGE, result.packageName)
            putExtra(LimitBlockActivity.EXTRA_BREAK_SESSION_TOKEN, token)
            putExtra(LimitBlockActivity.EXTRA_LAUNCH_ATTEMPT_ID, attemptId)
            putExtra(LimitBlockActivity.EXTRA_RULE_VERSION, result.rule.version)
            putExtra(LimitBlockActivity.EXTRA_GROUP_VERSION, result.group?.version ?: 0L)
            putExtra(LimitBlockActivity.EXTRA_COOLDOWN_ENDS_AT, cooldownEndsAtMillis)
            putExtra(LimitBlockActivity.EXTRA_SESSION_RESET_AT, sessionResetAtMillis)
            putExtra(
                LimitBlockActivity.EXTRA_REACHED_KINDS,
                result.decision.reachedKinds.joinToString(",") { it.name },
            )
            putExtra(LimitBlockActivity.EXTRA_DAY_TOKEN, LocalDate.now().toString())
            putExtra(LimitBlockActivity.EXTRA_ENGLISH, english)
            putExtra(LimitBlockActivity.EXTRA_NON_ROOT, true)
        }
        val attempt = BreakPageAttempt(
            attemptId = attemptId,
            packageName = result.packageName,
            requestedAtElapsedMillis = SystemClock.elapsedRealtime(),
            foregroundGeneration = foregroundExecutionSnapshot?.generation ?: -1L,
        )
        pendingBreakPageAttempt = attempt
        statusRepository.recordBreakPageRequested()
        transitionUiState(
            NonRootUiExecutionState.BREAK_PAGE_PENDING,
            result.packageName,
            "break_page_requested:${attemptId.take(12)}",
        )
        log(
            result.packageName,
            "NON_ROOT_BREAK_PAGE_START_REQUESTED",
            "attempt=${attemptId.take(12)}, sdk=${Build.VERSION.SDK_INT}, " +
                "device=${Build.MANUFACTURER}/${Build.MODEL}, current=${foregroundPackage.orEmpty()}",
        )
        return try {
            if (
                !mayExecuteDisruptiveAction(
                    packageName = result.packageName,
                    stage = "break_page_start",
                    expectedForegroundGeneration = attempt.foregroundGeneration,
                )
            ) {
                pendingBreakPageAttempt = null
                transitionUiState(
                    NonRootUiExecutionState.IDLE,
                    result.packageName,
                    "break_page_start_cancelled",
                )
                return false
            }
            service.startActivity(intent)
            log(
                result.packageName,
                "NON_ROOT_BREAK_PAGE_START_ACCEPTED",
                "attempt=${attemptId.take(12)}; waiting for foreground confirmation",
            )
            handler.postDelayed(
                {
                    val stillPending = pendingBreakPageAttempt
                    if (
                        !NonRootUiExecutionPolicy.shouldRunConfirmationTimeout(
                            pendingAttemptId = stillPending?.attemptId,
                            timeoutAttemptId = attemptId,
                        )
                    ) return@postDelayed
                    val pendingGeneration = stillPending?.foregroundGeneration
                        ?: return@postDelayed
                    if (
                        !mayExecuteDisruptiveAction(
                            packageName = result.packageName,
                            stage = "break_page_confirmation_timeout",
                            expectedForegroundGeneration = pendingGeneration,
                        )
                    ) {
                        pendingBreakPageAttempt = null
                        if (uiState == NonRootUiExecutionState.BREAK_PAGE_PENDING) {
                            transitionUiState(
                                NonRootUiExecutionState.IDLE,
                                result.packageName,
                                "break_page_timeout_cancelled",
                            )
                        }
                        return@postDelayed
                    }
                    pendingBreakPageAttempt = null
                    transitionUiState(
                        NonRootUiExecutionState.IDLE,
                        result.packageName,
                        "break_page_confirmation_timeout",
                    )
                    log(
                        result.packageName,
                        "NON_ROOT_BREAK_PAGE_CONFIRM_TIMEOUT",
                        "attempt=${attemptId.take(12)}, timeout=${BREAK_PAGE_CONFIRM_TIMEOUT_MILLIS}ms, " +
                            "current=${foregroundPackage.orEmpty()}, interactive=$interactive",
                        "ERROR",
                    )
                    recordBreakPageCompatibilityFailure(
                        packageName = result.packageName,
                        stage = BreakPageCompatibilityStage.CONFIRMATION_TIMEOUT,
                        detail = "attempt=${attemptId.take(12)} timeout=${BREAK_PAGE_CONFIRM_TIMEOUT_MILLIS}ms",
                        settings = repository.getGlobalSettings(),
                    )
                    fallbackToHome(
                        packageName = result.packageName,
                        settings = repository.getGlobalSettings(),
                        stage = "foreground_confirmation_timeout",
                    )
                },
                BREAK_PAGE_CONFIRM_TIMEOUT_MILLIS,
            )
            true
        } catch (error: Throwable) {
            if (pendingBreakPageAttempt?.attemptId == attemptId) {
                pendingBreakPageAttempt = null
            }
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                result.packageName,
                "break_page_start_failed",
            )
            log(
                result.packageName,
                "NON_ROOT_BREAK_PAGE_START_FAILED",
                "attempt=${attemptId.take(12)}, error=${describeThrowable(error)}",
                "ERROR",
            )
            recordBreakPageCompatibilityFailure(
                packageName = result.packageName,
                stage = BreakPageCompatibilityStage.START_EXCEPTION,
                detail = describeThrowable(error),
                settings = result.settings,
            )
            false
        }
    }

    private fun recordBreakPageCompatibilityFailure(
        packageName: String,
        stage: BreakPageCompatibilityStage,
        detail: String,
        settings: GlobalSettings,
    ) {
        val shouldNotify = statusRepository.recordBreakPageFailure(packageName, stage, detail)
        log(
            packageName,
            "BREAK_PAGE_COMPATIBILITY_REQUIRED",
            "stage=$stage manufacturer=${Build.MANUFACTURER} detail=${detail.take(240)}",
            "ERROR",
        )
        if (!shouldNotify) return
        statusRepository.markBreakPageGuidanceShown()
        suppressNextGenericBreakPageToast = true
        Toast.makeText(
            service,
            if (isEnglish(settings)) {
                "The restriction page may be blocked. Check background pop-up permission in Time Stop settings."
            } else {
                "独立限制页可能被系统拦截，请在时停设置中检查后台弹出权限"
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun fallbackToHome(
        packageName: String,
        settings: GlobalSettings,
        stage: String,
        retry: Boolean = true,
        showToast: Boolean = true,
        toastEnglish: String = "The restriction page was blocked. Returned Home.",
        toastChinese: String = "限制页被系统拦截，已返回桌面",
    ) {
        if (!mayExecuteDisruptiveAction(packageName, "home_fallback:$stage")) return
        val shouldShowToast = showToast && !suppressNextGenericBreakPageToast
        suppressNextGenericBreakPageToast = false
        if (uiState == NonRootUiExecutionState.BREAK_PAGE_PENDING) {
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                packageName,
                "home_fallback:$stage",
            )
        }
        val accepted = runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }.getOrElse {
            log(
                packageName,
                "NON_ROOT_HOME_FALLBACK_EXCEPTION",
                "stage=$stage, error=${describeThrowable(it)}",
                "ERROR",
            )
            false
        }
        log(
            packageName,
            "NON_ROOT_HOME_FALLBACK_REQUESTED",
            "stage=$stage, accepted=$accepted, current=${foregroundPackage.orEmpty()}",
            if (accepted) "WARN" else "ERROR",
        )
        if (shouldShowToast) {
            Toast.makeText(
                service,
                if (isEnglish(settings)) toastEnglish else toastChinese,
                Toast.LENGTH_LONG,
            ).show()
        }
        handler.postDelayed(
            {
                executor.execute {
                    val recentForeground = deviceUsageRepository.recentForegroundSnapshot()
                    handler.post {
                        if (destroyed) return@post
                        val reconciled = recentForeground != null &&
                            ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                                current = lastForegroundSignal,
                                usagePackageName = recentForeground.packageName,
                                usageObservedAtMillis = recentForeground.observedAtMillis,
                            )
                        if (reconciled) {
                            statusRepository.recordUsageReconciliation(matched = false)
                            acceptForegroundSignal(
                                packageName = recentForeground.packageName,
                                eventType = 0,
                                source = ForegroundSignalSource.USAGE_STATS_RECONCILE,
                                observedAtMillis = recentForeground.observedAtMillis,
                            )
                        } else if (recentForeground != null) {
                            statusRepository.recordUsageReconciliation(
                                matched = recentForeground.packageName == foregroundPackage,
                            )
                        }
                        val stillTarget = foregroundPackage == packageName
                        log(
                            packageName,
                            "NON_ROOT_HOME_FALLBACK_RESULT",
                            "stage=$stage, current=${foregroundPackage.orEmpty()}, " +
                                "usage=${recentForeground?.packageName.orEmpty()}, " +
                                "reconciled=$reconciled, stillTarget=$stillTarget",
                            if (stillTarget) "ERROR" else "INFO",
                        )
                        if (stillTarget && retry) {
                            fallbackToHome(
                                packageName = packageName,
                                settings = settings,
                                stage = "${stage}_retry",
                                retry = false,
                                showToast = false,
                                toastEnglish = toastEnglish,
                                toastChinese = toastChinese,
                            )
                        }
                    }
                }
            },
            HOME_FALLBACK_CONFIRM_MILLIS,
        )
    }

    private fun describeThrowable(error: Throwable): String {
        val cause = error.cause
        return buildString {
            append(error.javaClass.name)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (cause != null && cause !== error) {
                append("; cause=").append(cause.javaClass.name)
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }
    }

    private fun isCurrent(packageName: String, capturedGeneration: Long): Boolean =
        !destroyed &&
            interactive &&
            foregroundPackage == packageName &&
            generation.get() == capturedGeneration

    private fun mayExecuteDisruptiveAction(
        packageName: String,
        stage: String,
        expectedForegroundGeneration: Long? = null,
    ): Boolean {
        val allowed = NonRootActionGuard.mayExecuteDisruptiveAction(
            targetPackageName = packageName,
            current = foregroundExecutionSnapshot,
            expectedGeneration = expectedForegroundGeneration,
        )
        if (!allowed) {
            val current = foregroundExecutionSnapshot
            log(
                packageName,
                "NON_ROOT_DISRUPTIVE_ACTION_REJECTED",
                "stage=$stage, current=${current?.packageName.orEmpty()}, " +
                    "kind=${current?.kind}, source=${current?.source}, " +
                    "generation=${current?.generation}, expected=$expectedForegroundGeneration",
                "WARN",
            )
        }
        return allowed
    }

    private fun cancelPendingActionForForeground(
        newPackageName: String,
        newKind: ForegroundPackageKind,
        stage: String,
    ) {
        val pending = pendingBreakPageAttempt ?: return
        if (
            !NonRootActionGuard.shouldCancelPendingAction(
                targetPackageName = pending.packageName,
                newForegroundPackageName = newPackageName,
                newForegroundKind = newKind,
                ownPackageName = appContext.packageName,
            )
        ) return
        pendingBreakPageAttempt = null
        if (uiState == NonRootUiExecutionState.BREAK_PAGE_PENDING) {
            transitionUiState(
                NonRootUiExecutionState.IDLE,
                pending.packageName,
                "pending_cancelled:$stage",
            )
        }
        log(
            pending.packageName,
            "NON_ROOT_PENDING_ACTION_CANCELLED",
            "stage=$stage, newPackage=$newPackageName, kind=$newKind, " +
                "attempt=${pending.attemptId.take(12)}",
        )
    }

    private fun hasEffectiveRule(rule: AppRule, group: AppGroup?): Boolean =
        group?.let {
            it.enabled &&
                (it.dailyEnabled || it.perLaunchEnabled || it.scheduleEnabled)
        } ?: (
            rule.sessionPlanningEnabled ||
                rule.enabled ||
                rule.dailyEnabled ||
                rule.perLaunchEnabled ||
                rule.scheduleEnabled
            )

    private fun isBasicProtectionReady(): Boolean =
        repository.getGlobalSettings().protectionMode.usesNonRoot &&
            NonRootProtectionStatusRepository.isAccessibilityConnected(appContext) &&
            deviceUsageRepository.hasUsageAccess()

    private fun showShizukuFallbackWarning(
        settings: GlobalSettings,
        packageName: String,
    ) {
        if (!settings.protectionMode.usesShizuku) return
        val state = shizuku.state.value
        if (
            state == ShizukuExecutionState.READY ||
            state == ShizukuExecutionState.CONNECTING
        ) return
        val message = if (isEnglish(settings)) {
            when (state) {
                ShizukuExecutionState.PERMISSION_REQUIRED ->
                    "Shizuku is not authorized. Limits will use the restriction page."
                else ->
                    "Shizuku is unavailable. Limits will use the restriction page."
            }
        } else {
            when (state) {
                ShizukuExecutionState.PERMISSION_REQUIRED ->
                    "Shizuku 尚未授权，到限后将使用独立限制页"
                else ->
                    "Shizuku 当前不可用，到限后将使用独立限制页"
            }
        }
        showRuntimeWarning(
            token = "shizuku:$state",
            packageName = packageName,
            message = message,
        )
    }

    private fun showRuntimeWarning(
        token: String,
        packageName: String,
        message: String,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (
            token == lastRuntimeWarningToken &&
            lastRuntimeWarningAtElapsedMillis != Long.MIN_VALUE &&
            nowElapsed - lastRuntimeWarningAtElapsedMillis <
            RUNTIME_WARNING_REPEAT_MILLIS
        ) return
        lastRuntimeWarningToken = token
        lastRuntimeWarningAtElapsedMillis = nowElapsed
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
        log(
            packageName,
            "NON_ROOT_PERMISSION_WARNING",
            "issue=$token",
        )
    }

    private fun safeLimitMillis(seconds: Long): Long =
        seconds.coerceIn(
            RuleRepository.MIN_LIMIT_SECONDS,
            RuleRepository.MAX_LIMIT_SECONDS,
        ) * 1_000L

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun recordTransientIncident(token: String): Boolean {
        if (!recordedLimitIncidents.add(token)) return false
        while (recordedLimitIncidents.size > MAX_TRANSIENT_INCIDENTS) {
            recordedLimitIncidents.remove(recordedLimitIncidents.first())
        }
        return true
    }

    private fun persistSession(
        state: NonRootSessionState,
        stage: String,
    ): Boolean {
        val persisted = runtimeStore.saveSession(state)
        if (!persisted) {
            log(
                state.packageName,
                "NON_ROOT_SESSION_PERSIST_FAILED",
                "stage=$stage, session=${state.sessionId}",
                "ERROR",
            )
        }
        return persisted
    }

    private fun clearPersistedSession(
        packageName: String,
        stage: String,
    ): Boolean {
        val cleared = runtimeStore.clearSession(packageName)
        if (!cleared) {
            log(
                packageName,
                "NON_ROOT_SESSION_CLEAR_FAILED",
                "stage=$stage",
                "ERROR",
            )
        }
        return cleared
    }

    private fun clearActiveRestriction(
        packageName: String,
        stage: String,
    ): Boolean {
        val cleared = runtimeStore.clearActiveRestriction(packageName)
        if (!cleared) {
            log(
                packageName,
                "NON_ROOT_RESTRICTION_CLEAR_FAILED",
                "stage=$stage",
                "ERROR",
            )
        }
        return cleared
    }

    private fun transitionUiState(
        state: NonRootUiExecutionState,
        packageName: String,
        event: String,
    ) {
        if (uiState == state && statusRepository.healthSnapshot.value.lastUiEvent == event) return
        val previous = uiState
        uiState = state
        statusRepository.recordUiState(state, event)
        log(
            packageName,
            "NON_ROOT_UI_STATE_CHANGED",
            "from=$previous, to=$state, event=$event",
        )
    }

    private fun isEnglish(settings: GlobalSettings): Boolean =
        settings.languageMode == AppLanguageMode.ENGLISH ||
            (
                settings.languageMode == AppLanguageMode.SYSTEM &&
                    appContext.resources.configuration.locales[0].language != "zh"
                )

    private fun log(
        packageName: String,
        event: String,
        message: String,
        level: String = "INFO",
    ) {
        if (!repository.getGlobalSettings().diagnosticsEnabled) return
        val dedupeWindow = when (event) {
            "NON_ROOT_FOREGROUND_SIGNAL" -> 1_000L
            "NON_ROOT_SIGNAL_DEBOUNCED",
            "NON_ROOT_RULE_EVALUATED",
            -> 5_000L
            else -> 0L
        }
        if (dedupeWindow > 0L) {
            diagnostics.appendRateLimited(
                level = level,
                packageName = packageName,
                event = event,
                stateSignature = message.take(320),
                message = message,
                windowMillis = dedupeWindow,
            )
        } else {
            diagnostics.append(level, packageName, event, message)
        }
    }

    private fun classifyForegroundPackage(packageName: String): ForegroundPackageKind {
        val inputMethodPackage = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty().substringBefore('/')
            .takeIf(String::isNotBlank)
        return ForegroundPackagePolicy.classify(
            packageName = packageName,
            ownPackageName = appContext.packageName,
            targetPackages = repository.configuredPackages(),
            homePackages = homePackages,
            inputMethodPackage = inputMethodPackage,
            overlayOwnedByTimeStop =
                packageName == appContext.packageName && overlay.shouldIgnoreOwnWindowEvents,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolveHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packageManager = appContext.packageManager
        return buildSet {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNullTo(this) { it.activityInfo?.packageName }
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNullTo(this) { it.activityInfo?.packageName }
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }

    private data class EvaluationResult(
        val packageName: String,
        val foregroundDetectedAtMillis: Long,
        val settings: GlobalSettings,
        val rule: AppRule,
        val group: AppGroup?,
        val session: NonRootSessionState,
        val decision: NonRootRuleDecision,
        val scheduleNextTransitionMillis: Long?,
        val scheduleIncidentToken: String,
        val cooldownEndsAtMillis: Long,
    )

    private data class BreakPageAttempt(
        val attemptId: String,
        val packageName: String,
        val requestedAtElapsedMillis: Long,
        val foregroundGeneration: Long,
    )

    private companion object {
        const val WARNING_LEAD_MILLIS = 5_000L
        const val THRESHOLD_SLOP_MILLIS = 80L
        const val BREAK_PAGE_CONFIRM_TIMEOUT_MILLIS = 2_500L
        const val HOME_FALLBACK_CONFIRM_MILLIS = 700L
        const val SHIZUKU_FOREGROUND_CONFIRM_MILLIS = 700L
        const val SHIZUKU_EXECUTION_TIMEOUT_MILLIS = 3_000L
        const val FIRST_PLAN_REATTACH_MILLIS = 300L
        const val SECOND_PLAN_REATTACH_MILLIS = 800L
        const val COMPATIBILITY_RECONCILE_BACKOFF_MILLIS = 1_000L
        const val COMPATIBILITY_MISMATCH_LOG_WINDOW_MILLIS = 5_000L
        const val MAX_PLAN_PROMPT_ATTEMPTS = 3
        const val MAX_TRANSIENT_INCIDENTS = 256
        const val RUNTIME_WARNING_REPEAT_MILLIS = 60_000L
    }
}
