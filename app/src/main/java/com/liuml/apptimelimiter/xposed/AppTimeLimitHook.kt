package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.LimitBlockActivity
import com.liuml.apptimelimiter.core.ActivityCallbackPolicy
import com.liuml.apptimelimiter.core.GroupRulePolicy
import com.liuml.apptimelimiter.core.LimitBlockReason
import com.liuml.apptimelimiter.core.LimitEnforcementPolicy
import com.liuml.apptimelimiter.core.LimitGateSnapshot
import com.liuml.apptimelimiter.core.QuotaIncidentPolicy
import com.liuml.apptimelimiter.core.QuotaKind
import com.liuml.apptimelimiter.core.RestPagePolicy
import com.liuml.apptimelimiter.core.SharedCooldownPolicy
import com.liuml.apptimelimiter.core.SharedCooldownRecord
import com.liuml.apptimelimiter.core.UsageMath
import com.liuml.apptimelimiter.core.ProcessTerminationPolicy
import com.liuml.apptimelimiter.core.ScheduleDecision
import com.liuml.apptimelimiter.core.ScheduleBlockPolicy
import com.liuml.apptimelimiter.core.ScheduleConstraint
import com.liuml.apptimelimiter.core.ScheduleEvaluator
import com.liuml.apptimelimiter.core.SessionPlanPolicy
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.AppLanguageMode
import com.liuml.apptimelimiter.data.LimitEnforcementMode
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.ipc.RuleContract
import com.liuml.apptimelimiter.localization.AppLocaleController
import com.liuml.apptimelimiter.localization.SupportedLanguage
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class AppTimeLimitHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE) return

        // Install lifecycle hooks for every package in the LSPosed scope. Rules are read when
        // an Activity resumes, so enabling a rule no longer depends on cross-process prefs at load time.
        val preferences = XSharedPreferences(MODULE_PACKAGE, RuleRepository.PREFS_NAME)
        preferences.makeWorldReadable()
        runCatching { preferences.reload() }
        val installMediaHooks = LimitEnforcementPolicy.shouldInstallMediaHooks(
            LimitEnforcementPolicy.parseMode(
                preferences.getString(RuleRepository.KEY_LIMIT_ENFORCEMENT_MODE, null),
            ),
        )
        val mediaPauseController = MediaPauseController(lpparam.classLoader)
        val limiter = RuntimeLimiter(
            lpparam.packageName,
            lpparam.processName,
            preferences,
            mediaPauseController,
        )
        val installedEntries = mutableListOf<String>()
        val installErrors = mutableListOf<String>()
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                null,
                "callActivityOnResume",
                Activity::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.args.firstOrNull() as? Activity)?.let(limiter::onActivityResumed)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                null,
                "callActivityOnPause",
                Activity::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.args.firstOrNull() as? Activity)?.let(limiter::onActivityPaused)
                    }
                },
            )
            installedEntries += "Instrumentation"
        }.onFailure { error ->
            installErrors += "Instrumentation:${error.javaClass.simpleName}"
            XposedBridge.log(
                "AppTimeLimiter: HOOK_ENTRY_FAILED package=${lpparam.packageName} process=${lpparam.processName} entry=Instrumentation",
            )
            XposedBridge.log(error)
        }
        if (installMediaHooks) {
            runCatching {
                val mediaEntries = mediaPauseController.installHooks()
                if (mediaEntries.isNotEmpty()) {
                    installedEntries += "MediaPause(${mediaEntries.distinct().joinToString("+")})"
                }
            }.onFailure { error ->
                installErrors += "MediaPause:${error.javaClass.simpleName}"
                XposedBridge.log(
                    "AppTimeLimiter: MEDIA_HOOK_FAILED package=${lpparam.packageName} process=${lpparam.processName}",
                )
                XposedBridge.log(error)
            }
        }
        runCatching {
            // Some protected or legacy apps replace Instrumentation and do not dispatch through
            // the base callActivityOnResume/Pause methods. Activity methods are a stable fallback;
            // callbacks are posted so the Instrumentation path wins normally and RuntimeLimiter
            // can deduplicate the second delivery without changing foreground accounting.
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                null,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? Activity)?.let(limiter::onActivityResumedFallback)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                null,
                "onPause",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? Activity)?.let(limiter::onActivityPausedFallback)
                    }
                },
            )
            installedEntries += "ActivityFallback"
        }.onFailure { error ->
            installErrors += "ActivityFallback:${error.javaClass.simpleName}"
            XposedBridge.log(
                "AppTimeLimiter: HOOK_ENTRY_FAILED package=${lpparam.packageName} process=${lpparam.processName} entry=ActivityFallback",
            )
            XposedBridge.log(error)
        }
        val processBits = if (Process.is64Bit()) 64 else 32
        if (installedEntries.isNotEmpty()) {
            XposedBridge.log(
                "AppTimeLimiter: HOOK_INSTALLED package=${lpparam.packageName} process=${lpparam.processName} bits=$processBits abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) entries=${installedEntries.joinToString("+")} errors=${installErrors.joinToString(",").ifBlank { "none" }}",
            )
        } else {
            XposedBridge.log(
                "AppTimeLimiter: HOOK_FAILED package=${lpparam.packageName} process=${lpparam.processName} bits=$processBits errors=${installErrors.joinToString(",")}",
            )
        }
    }

    private companion object {
        const val MODULE_PACKAGE = "com.liuml.apptimelimiter"
    }
}

private class RuntimeLimiter(
    private val packageName: String,
    private val processName: String,
    private val preferences: XSharedPreferences,
    private val mediaPauseController: MediaPauseController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processSessionId =
        "${Process.myPid()}-${SystemClock.elapsedRealtime()}-${processName.hashCode()}"
    private var activeActivity = WeakReference<Activity>(null)
    private var foregroundStartedAt = NOT_RUNNING
    private var foregroundDayToken = -1
    private var perLaunchCommittedMs = 0L
    private var loadedRuleVersion = Long.MIN_VALUE
    private var loadedGroupVersion = Long.MIN_VALUE
    private var exitScheduled = false
    private var hookReadyLogged = false
    private var lastRuleSummary = ""
    private var providerFailureLogged = false
    private var diagnosticsEnabled = true
    private var grantedExtensionMs = 0L
    private var warningShownForExtensionMs = Long.MIN_VALUE
    private var warningVibratedForExtensionMs = Long.MIN_VALUE
    private var warningBanner: TopWarningBanner? = null
    private var warningCountdown: Runnable? = null
    private var sessionLaunchReported = false
    private var statsContext: Context? = null
    private val pendingStatsByDay = linkedMapOf<String, PendingUsageBatch>()
    private var statsRetryCount = 0
    private var statsSuccessLogged = false
    private var statsFailureLogged = false
    private var statsOutboxLoaded = false
    private var groupSegmentBaselineUsedMs = 0L
    private var groupSegmentBaselineDay = ""
    private var groupSegmentIdentity = ""
    private var loadedGroupIdentity = ""
    private var lastLoadedRule: HookRule? = null
    private var sessionPlanPromptHandled = false
    private var sessionPlanRemainingMs = NOT_RUNNING
    private var sessionPlanForegroundStartedAt = NOT_RUNNING
    private var sessionPlanWarningShown = false
    private var sessionPlanWarningVibrated = false
    private var sessionPlanDialog: SessionPlanDialog? = null
    private var activeSessionPlanDialogMode: SessionPlanDialogMode? = null
    private var sessionPlanWaitingForUsage = false
    private var sessionPlanPromptAttempts = 0
    private var activityGeneration = 0L
    private var sessionPlanPromptRunnable: Runnable? = null
    private var blockingState: BlockingState? = null
    private var blockingOverlayFallback = false
    private val incidentOccurredAtMillis = mutableMapOf<String, Long>()
    private var perLaunchCycleGeneration = 0L

    private val deadline = Runnable { checkDeadline() }
    private val warningDeadline = Runnable { showExitWarning() }
    private val scheduleDeadline = Runnable { checkScheduleBoundary() }
    private val scheduleWarningDeadline = Runnable { showScheduleWarning() }
    private val midnightDeadline = Runnable { checkMidnightRollover() }
    private val groupUsageSync = Runnable { syncGroupUsage() }
    private val statsRetry = Runnable {
        statsContext?.let(::flushUsageEvents)
    }
    private val sessionPlanDeadline = Runnable { checkSessionPlanDeadline() }
    private val sessionPlanWarningDeadline = Runnable { showSessionPlanWarning() }
    private val activityHandoffFinalize = Runnable {
        if (activeActivity.get() == null) {
            warningShownForExtensionMs = Long.MIN_VALUE
            sessionPlanWarningShown = false
        }
    }
    fun onActivityResumedFallback(activity: Activity) {
        mainHandler.post { onActivityResumed(activity) }
    }

    fun onActivityPausedFallback(activity: Activity) {
        mainHandler.post { onActivityPaused(activity) }
    }

    fun onActivityResumed(activity: Activity) {
        if (exitScheduled) return
        if (activeActivity.get() === activity) return
        activityGeneration++
        mainHandler.removeCallbacks(activityHandoffFinalize)
        cancelPendingSessionPlanPrompt()
        val rule = readRule(activity, reloadFallback = true)
        if (!hookReadyLogged) {
            hookReadyLogged = true
            diagnostic(
                activity,
                event = "HOOK_READY",
                message = "生命周期 Hook 已运行；version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})；process=$processName；bits=${if (Process.is64Bit()) 64 else 32}；abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}；规则来源=${rule.source}",
            )
        }

        val ruleSummary = "${rule.enabled}/${rule.sessionPlanningEnabled}/${rule.dailyEnabled}/${rule.dailyLimitMillis}/${rule.systemTodayUsedMillis}/${rule.systemUsageMeasuredAtElapsedMillis}/${rule.systemUsagePending}/${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis}/${rule.groupEnabled}/${rule.groupId}/${rule.groupDailyEnabled}/${rule.groupDailyLimitMillis}/${rule.groupTodayUsedMillis}/${rule.groupPerLaunchEnabled}/${rule.groupPerLaunchLimitMillis}/${rule.groupScheduleEnabled}/${rule.groupScheduleMode}/${ScheduleCodec.encode(rule.groupScheduleWindows)}/${rule.groupCooldownEnabled}/${rule.groupCooldownMillis}/${rule.groupVersion}/${rule.scheduleEnabled}/${rule.scheduleMode}/${ScheduleCodec.encode(rule.scheduleWindows)}/${rule.cooldownEnabled}/${rule.cooldownMillis}/${rule.version}/${rule.exitWarningEnabled}/${rule.fullScreenExitWarningEnabled}/${rule.exitWarningVibrationEnabled}/${rule.languageMode}/${rule.extensionMillis}/${rule.limitEnforcementMode}/${rule.diagnosticsEnabled}/${rule.source}"
        if (lastRuleSummary != ruleSummary) {
            lastRuleSummary = ruleSummary
            diagnostic(
                activity,
                event = "RULE_READ",
                message = "enabled=${rule.enabled}, sessionPlanning=${rule.sessionPlanningEnabled}, daily=${rule.dailyEnabled}/${rule.dailyLimitMillis / 1000}s, systemToday=${rule.systemTodayUsedMillis / 1000.0}s/pending=${rule.systemUsagePending}, perLaunch=${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis / 1000}s, group=${rule.groupEnabled}/${rule.groupName}/daily=${rule.groupDailyEnabled}/${rule.groupTodayUsedMillis / 1000.0}s/${rule.groupDailyLimitMillis / 1000}s/perLaunch=${rule.groupPerLaunchEnabled}/${rule.groupPerLaunchLimitMillis / 1000}s/schedule=${rule.groupScheduleEnabled}/${rule.groupScheduleMode}/${rule.groupScheduleWindows.size}/cooldown=${rule.groupCooldownEnabled}/${rule.groupCooldownMillis / 1000}s, schedule=${rule.scheduleEnabled}/${rule.scheduleMode}/${rule.scheduleWindows.size}, cooldown=${rule.cooldownEnabled}/${rule.cooldownMillis / 1000}s, warning=${rule.exitWarningEnabled}/${rule.fullScreenExitWarningEnabled}/${rule.exitWarningVibrationEnabled}, language=${rule.languageMode}, extension=${rule.extensionMillis / 1000}s, enforcement=${rule.limitEnforcementMode}, source=${rule.source}",
            )
        }

        if (!rule.enabled && !rule.sessionPlanningEnabled) {
            cancelSessionPlan(dismissDialog = true, resetPrompt = true)
            if (blockingState != null) {
                removeBlockingOverlay(activity, "规则已关闭")
            }
            stopTiming()
            return
        }

        applyRuleVersionChange(rule)

        var launchStatsPersisted = true
        if (!sessionLaunchReported) {
            launchStatsPersisted = recordUsageEvent(
                activity = activity,
                durationMillis = 0L,
                launchIncrement = if (rule.usageStatsEnabled) 1 else 0,
                limitHitIncrement = 0,
            )
            sessionLaunchReported = true
        }

        activeActivity = WeakReference(activity)
        if (
            enforceBlockingConditions(
                activity = activity,
                rule = rule,
                openedDuringBlockedTime = true,
                launchStatsPersisted = launchStatsPersisted,
            )
        ) return

        if (!rule.enabled) {
            stopTimingCallbacks()
            dismissWarning(resetForCurrentLimit = true)
            dismissScheduleWarning()
            groupSegmentIdentity = ""
            groupSegmentBaselineDay = ""
            groupSegmentBaselineUsedMs = 0L
        }
        val startedNow = rule.enabled && foregroundStartedAt == NOT_RUNNING
        if (startedNow) {
            foregroundStartedAt = SystemClock.elapsedRealtime()
            foregroundDayToken = dayToken()
        }
        val remainingMs = if (rule.enabled) {
            if (rule.dailyEnabled) syncSystemDailyBaseline(activity, rule)
            if (startedNow || groupSegmentIdentity != rule.groupIdentity()) {
                updateGroupSegmentBaseline(rule)
            }
            if (rule.hasTimedQuota()) {
                scheduleDeadline(activity, rule)
            } else {
                null
            }
        } else {
            foregroundStartedAt = NOT_RUNNING
            foregroundDayToken = -1
            null
        }
        if (exitScheduled || blockingState != null) return
        val scheduleRemainingMs = if (rule.enabled && !exitScheduled) {
            scheduleScheduleBoundary(activity, rule)
        } else {
            null
        }
        if (rule.enabled && !exitScheduled) scheduleMidnightRollover(rule)
        if (rule.enabled && !exitScheduled) scheduleGroupUsageSync(rule)
        if (startedNow && !exitScheduled) {
            diagnostic(
                activity,
                event = "TIMER_START",
                message = buildString {
                    append("开始前台计时")
                    remainingMs?.let { append("，最早时长阈值剩余=${it / 1000.0}s") }
                    scheduleRemainingMs?.let { append("，时段边界剩余=${it / 1000.0}s") }
                },
            )
        }
        if (!exitScheduled) resumeSessionPlan(activity, rule)
    }

    fun onActivityPaused(activity: Activity) {
        if (activeActivity.get() !== activity) return
        pauseSessionPlan(activity)
        detachBlockingOverlay()
        val rule = readRule(activity, reloadFallback = true)
        val segmentMs = if (foregroundStartedAt == NOT_RUNNING) 0L else activeSegmentMillis()
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        activeActivity.clear()
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        foregroundDayToken = -1
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        mainHandler.removeCallbacks(activityHandoffFinalize)
        mainHandler.postDelayed(activityHandoffFinalize, ACTIVITY_HANDOFF_GRACE_MS)
        if (rule.enabled && segmentMs >= LOGGABLE_SEGMENT_MS) {
            diagnostic(
                activity,
                event = "TIMER_PAUSE",
                message = "本段=${segmentMs / 1000.0}s，${usageSummary(activity, rule)}",
            )
        }
    }

    private fun resumeSessionPlan(activity: Activity, rule: HookRule) {
        if (blockingState != null) {
            suppressSessionPlanForBlock(activity, blockingState?.reason)
            return
        }
        if (!rule.sessionPlanningEnabled) {
            cancelSessionPlan(dismissDialog = true, resetPrompt = true)
            return
        }
        if (sessionPlanRemainingMs != NOT_RUNNING) {
            if (sessionPlanRemainingMillis() <= 0L) {
                expireSessionPlan(activity, rule)
                return
            }
            sessionPlanForegroundStartedAt = SystemClock.elapsedRealtime()
            scheduleSessionPlan(activity, rule)
            return
        }
        if (
            !sessionPlanPromptHandled &&
            rule.systemUsagePending &&
            (rule.dailyEnabled || rule.groupDailyEnabled)
        ) {
            if (!sessionPlanWaitingForUsage) {
                sessionPlanWaitingForUsage = true
                diagnostic(
                    activity,
                    event = "SESSION_PLAN_WAITING_USAGE",
                    message = "等待系统用量快照完成后再判断是否可制定计划",
                )
            }
            return
        }
        sessionPlanWaitingForUsage = false
        if (!sessionPlanPromptHandled) {
            scheduleInitialSessionPlanPrompt(activity)
        }
    }

    private fun pauseSessionPlan(activity: Activity) {
        cancelPendingSessionPlanPrompt()
        if (sessionPlanDialog?.isShowing == true) {
            val interruptedMode = activeSessionPlanDialogMode
            sessionPlanDialog?.dismiss()
            sessionPlanDialog = null
            activeSessionPlanDialogMode = null
            diagnostic(
                activity,
                event = "SESSION_PLAN_PROMPT_INTERRUPTED",
                message = "Activity 交接时关闭计划弹窗；mode=$interruptedMode；不再重复显示",
            )
        }
        if (sessionPlanRemainingMs == NOT_RUNNING || sessionPlanForegroundStartedAt == NOT_RUNNING) {
            return
        }
        sessionPlanRemainingMs = sessionPlanRemainingMillis()
        sessionPlanForegroundStartedAt = NOT_RUNNING
        mainHandler.removeCallbacks(sessionPlanDeadline)
        mainHandler.removeCallbacks(sessionPlanWarningDeadline)
        dismissSessionPlanWarning()
        diagnostic(
            activity,
            event = "SESSION_PLAN_PAUSED",
            message = "进入后台，计划暂停；remaining=${sessionPlanRemainingMs / 1000.0}s",
        )
    }

    private fun showSessionPlanPrompt(
        activity: Activity,
        rule: HookRule,
        mode: SessionPlanDialogMode,
        allowHandledInitialRetry: Boolean = false,
    ) {
        if (exitScheduled || blockingState != null || sessionPlanDialog?.isShowing == true) return
        if (activity !== activeActivity.get() || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (mode == SessionPlanDialogMode.INITIAL) {
            if (sessionPlanPromptHandled && !allowHandledInitialRetry) return
        }
        activeSessionPlanDialogMode = mode
        sessionPlanDialog = SessionPlanDialog.show(
            activity = activity,
            mode = mode,
            english = isEnglish(activity, rule),
            includeDebugChoice = BuildConfig.DEBUG,
            onStart = { durationMillis ->
                sessionPlanDialog = null
                activeSessionPlanDialogMode = null
                requestSessionPlan(activity, durationMillis, mode)
            },
            onWithoutPlan = {
                sessionPlanDialog = null
                activeSessionPlanDialogMode = null
                cancelSessionPlan(dismissDialog = false)
                resumePermanentQuotaEnforcement(activity)
                diagnostic(
                    activity,
                    event = "SESSION_PLAN_SKIPPED",
                    message = if (mode == SessionPlanDialogMode.REPLAN) "用户取消本次计划" else "用户选择本次不计划",
                )
            },
        )
        if (sessionPlanDialog != null) {
            if (mode == SessionPlanDialogMode.INITIAL) {
                sessionPlanPromptHandled = true
                sessionPlanPromptAttempts = 0
            }
            diagnostic(
                activity,
                event = "SESSION_PLAN_PROMPT",
                message = "mode=$mode",
            )
        } else {
            activeSessionPlanDialogMode = null
            if (mode == SessionPlanDialogMode.REPLAN && sessionPlanRemainingMs != NOT_RUNNING) {
                sessionPlanForegroundStartedAt = SystemClock.elapsedRealtime()
                scheduleSessionPlan(activity, rule)
            }
            if (mode == SessionPlanDialogMode.INITIAL) {
                sessionPlanPromptHandled = false
                sessionPlanPromptAttempts++
            }
            diagnostic(
                activity,
                level = "ERROR",
                event = "SESSION_PLAN_PROMPT_FAILED",
                message = "无法显示计划弹窗；mode=$mode, attempt=$sessionPlanPromptAttempts/${SessionPlanPolicy.MAX_PROMPT_ATTEMPTS}",
            )
            if (mode == SessionPlanDialogMode.INITIAL) {
                if (SessionPlanPolicy.shouldRetryPrompt(sessionPlanPromptAttempts)) {
                    diagnostic(
                        activity,
                        event = "SESSION_PLAN_PROMPT_RETRY",
                        message = "将在稳定页面重试；attempt=${sessionPlanPromptAttempts + 1}",
                    )
                    scheduleInitialSessionPlanPrompt(
                        activity,
                        SESSION_PLAN_PROMPT_RETRY_MS,
                    )
                } else {
                    failClosedSessionPlanPrompt(activity, rule)
                }
            }
        }
    }

    private fun requestSessionPlan(
        activity: Activity,
        requestedDurationMillis: Long,
        mode: SessionPlanDialogMode,
    ) {
        val durationMillis = SessionPlanPolicy.selectedDurationMillis(
            requestedDurationMillis = requestedDurationMillis,
            allowDebugShortChoice = BuildConfig.DEBUG,
        ) ?: return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.sessionPlanningEnabled || exitScheduled) {
            cancelSessionPlan(dismissDialog = true)
            return
        }
        if (
            enforceBlockingConditions(
                activity = activity,
                rule = rule,
                openedDuringBlockedTime = true,
                launchStatsPersisted = true,
            )
        ) {
            suppressSessionPlanForBlock(activity, blockingState?.reason)
            return
        }
        val quotaStatus = thresholdStatus(activity, rule)
        val quotaRemainingMillis = quotaStatus.remainingMillis.takeIf { quotaStatus.hasThreshold }
        if (!SessionPlanPolicy.canOfferPlan(quotaRemainingMillis)) {
            diagnostic(
                activity,
                event = "SESSION_PLAN_UNAVAILABLE",
                message = "额度已经耗尽，不提供本次计划；reached=${quotaStatus.reachedLabels}",
            )
            forceExit(activity, rule, quotaStatus)
            return
        }
        if (
            quotaRemainingMillis != null &&
            !SessionPlanPolicy.fitsWithinQuota(durationMillis, quotaRemainingMillis)
        ) {
            val remainingText = formatDuration(activity, rule, quotaRemainingMillis)
            diagnostic(
                activity,
                event = "SESSION_PLAN_REJECTED_OVER_QUOTA",
                message = "rule=${quotaStatus.nextThresholdLabel}, remaining=${quotaRemainingMillis / 1000.0}s, requested=${durationMillis / 1000.0}s",
            )
            Toast.makeText(
                activity,
                hookText(
                    activity,
                    rule,
                    "${quotaStatus.nextThresholdLabel}仅剩 $remainingText，请选择更短的计划",
                    "${quotaStatus.nextThresholdLabel} has $remainingText left. Choose a shorter plan.",
                ),
                Toast.LENGTH_LONG,
            ).show()
            val generation = activityGeneration
            mainHandler.post {
                if (ActivityCallbackPolicy.isCurrent(
                        capturedGeneration = generation,
                        currentGeneration = activityGeneration,
                        hostIsCurrent = activity === activeActivity.get(),
                        hostIsUsable = !activity.isFinishing && !activity.isDestroyed,
                        exitScheduled = exitScheduled,
                    )
                ) {
                    showSessionPlanPrompt(
                        activity,
                        readRule(activity, reloadFallback = true),
                        mode,
                        allowHandledInitialRetry = true,
                    )
                }
            }
            return
        }
        startSessionPlan(
            activity = activity,
            durationMillis = durationMillis,
            replanned = mode == SessionPlanDialogMode.REPLAN,
            rule = rule,
        )
    }

    private fun startSessionPlan(
        activity: Activity,
        durationMillis: Long,
        replanned: Boolean,
        rule: HookRule,
    ): Boolean {
        sessionPlanRemainingMs = durationMillis
        sessionPlanForegroundStartedAt = SystemClock.elapsedRealtime()
        sessionPlanWarningShown = false
        sessionPlanWarningVibrated = false
        if (!rule.sessionPlanningEnabled || exitScheduled) {
            cancelSessionPlan(dismissDialog = true)
            return false
        }
        resumePermanentQuotaEnforcement(activity)
        scheduleSessionPlan(activity, rule)
        diagnostic(
            activity,
            event = if (replanned) "SESSION_PLAN_REPLANNED" else "SESSION_PLAN_STARTED",
            message = "duration=${durationMillis / 1000.0}s",
        )
        return true
    }

    private fun beginSessionReplan(activity: Activity, rule: HookRule) {
        if (sessionPlanRemainingMs == NOT_RUNNING || exitScheduled) return
        if (sessionPlanForegroundStartedAt != NOT_RUNNING) {
            sessionPlanRemainingMs = sessionPlanRemainingMillis()
            sessionPlanForegroundStartedAt = NOT_RUNNING
        }
        mainHandler.removeCallbacks(sessionPlanDeadline)
        mainHandler.removeCallbacks(sessionPlanWarningDeadline)
        dismissSessionPlanWarning()
        showSessionPlanPrompt(activity, rule, SessionPlanDialogMode.REPLAN)
    }

    private fun cancelSessionPlan(dismissDialog: Boolean, resetPrompt: Boolean = false) {
        sessionPlanRemainingMs = NOT_RUNNING
        sessionPlanForegroundStartedAt = NOT_RUNNING
        sessionPlanWarningShown = false
        sessionPlanWarningVibrated = false
        sessionPlanWaitingForUsage = false
        cancelPendingSessionPlanPrompt()
        mainHandler.removeCallbacks(sessionPlanDeadline)
        mainHandler.removeCallbacks(sessionPlanWarningDeadline)
        dismissSessionPlanWarning()
        if (dismissDialog) {
            sessionPlanDialog?.dismiss()
            sessionPlanDialog = null
            activeSessionPlanDialogMode = null
        }
        if (resetPrompt) sessionPlanPromptHandled = false
        if (resetPrompt) sessionPlanPromptAttempts = 0
    }

    private fun scheduleInitialSessionPlanPrompt(
        activity: Activity,
        delayMillis: Long = SESSION_PLAN_PROMPT_STABLE_MS,
    ) {
        if (sessionPlanPromptHandled || sessionPlanPromptRunnable != null) return
        val generation = activityGeneration
        val prompt = Runnable {
            sessionPlanPromptRunnable = null
            if (!ActivityCallbackPolicy.isCurrent(
                    capturedGeneration = generation,
                    currentGeneration = activityGeneration,
                    hostIsCurrent = activity === activeActivity.get(),
                    hostIsUsable = !activity.isFinishing && !activity.isDestroyed,
                    exitScheduled = exitScheduled,
                )
            ) {
                return@Runnable
            }
            val latestRule = readRule(activity, reloadFallback = true)
            if (!latestRule.sessionPlanningEnabled || latestRule.systemUsagePending) return@Runnable
            if (
                enforceBlockingConditions(
                    activity = activity,
                    rule = latestRule,
                    openedDuringBlockedTime = true,
                    launchStatsPersisted = true,
                )
            ) {
                suppressSessionPlanForBlock(activity, blockingState?.reason)
                return@Runnable
            }
            showSessionPlanPrompt(activity, latestRule, SessionPlanDialogMode.INITIAL)
        }
        sessionPlanPromptRunnable = prompt
        mainHandler.postDelayed(prompt, delayMillis)
    }

    private fun cancelPendingSessionPlanPrompt() {
        sessionPlanPromptRunnable?.let(mainHandler::removeCallbacks)
        sessionPlanPromptRunnable = null
    }

    private fun failClosedSessionPlanPrompt(activity: Activity, rule: HookRule) {
        if (exitScheduled) return
        exitScheduled = true
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        cancelSessionPlan(dismissDialog = true)
        stopTimingCallbacks()
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        diagnostic(
            activity,
            level = "ERROR",
            event = "SESSION_PLAN_PROMPT_GAVE_UP",
            message = "计划弹窗连续失败，关闭目标应用以防止规则被绕过；attempts=$sessionPlanPromptAttempts",
        )
        finishTarget(
            activity,
            hookText(
                activity,
                rule,
                "无法显示本次计划，请在时停中检查或关闭该功能",
                "Unable to show the session plan. Check or disable it in Time Stop.",
            ),
            statsPersisted = true,
        )
    }

    private fun resumePermanentQuotaEnforcement(activity: Activity) {
        val rule = readRule(activity, reloadFallback = true)
        if (
            !exitScheduled &&
            rule.enabled &&
            foregroundStartedAt != NOT_RUNNING &&
            rule.hasTimedQuota()
        ) {
            scheduleDeadline(activity, rule)
        }
    }

    private fun sessionPlanRemainingMillis(): Long {
        if (sessionPlanRemainingMs == NOT_RUNNING) return NOT_RUNNING
        return SessionPlanPolicy.remainingMillis(
            committedRemainingMillis = sessionPlanRemainingMs,
            foregroundStartedAtElapsedMillis = sessionPlanForegroundStartedAt
                .takeUnless { it == NOT_RUNNING },
            nowElapsedMillis = SystemClock.elapsedRealtime(),
        )
    }

    private fun scheduleSessionPlan(activity: Activity, rule: HookRule) {
        mainHandler.removeCallbacks(sessionPlanDeadline)
        mainHandler.removeCallbacks(sessionPlanWarningDeadline)
        if (
            exitScheduled ||
            sessionPlanRemainingMs == NOT_RUNNING ||
            sessionPlanForegroundStartedAt == NOT_RUNNING
        ) return
        val remainingMs = sessionPlanRemainingMillis()
        if (remainingMs <= 0L) {
            mainHandler.post(sessionPlanDeadline)
            return
        }
        mainHandler.postDelayed(sessionPlanDeadline, remainingMs.coerceAtLeast(1L))
        if (
            rule.exitWarningEnabled &&
            SessionPlanPolicy.isEarliest(remainingMs, earliestPermanentRemainingMillis(activity, rule))
        ) {
            mainHandler.postDelayed(
                sessionPlanWarningDeadline,
                UsageMath.warningDelayMillis(remainingMs, SessionPlanPolicy.WARNING_LEAD_MILLIS),
            )
        }
    }

    private fun checkSessionPlanDeadline() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
        if (sessionPlanRemainingMs == NOT_RUNNING || sessionPlanForegroundStartedAt == NOT_RUNNING) return
        val remainingMs = sessionPlanRemainingMillis()
        if (remainingMs > 0L) {
            scheduleSessionPlan(activity, readRule(activity, reloadFallback = true))
            return
        }
        val rule = readRule(activity, reloadFallback = true)
        if (rule.enabled && rule.hasSchedule()) {
            val decision = evaluateSchedule(rule)
            if (!decision.allowed) {
                forceScheduleExit(activity, rule, decision, openedDuringBlockedTime = false)
                return
            }
        }
        if (rule.enabled && rule.hasTimedQuota()) {
            val status = thresholdStatus(activity, rule)
            if (status.reached) {
                forceExit(activity, rule, status)
                return
            }
        }
        expireSessionPlan(activity, rule)
    }

    private fun expireSessionPlan(activity: Activity, rule: HookRule) {
        if (exitScheduled) return
        exitScheduled = true
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        cancelSessionPlan(dismissDialog = true)
        stopTimingCallbacks()
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        diagnostic(
            activity,
            level = "WARN",
            event = "SESSION_PLAN_EXPIRED",
            message = "本次计划到期，退出应用；不计限制触发且不启动冷却；pid=${Process.myPid()}",
        )
        finishTarget(
            activity,
            hookText(activity, rule, "本次计划时间已结束", "This session plan has ended"),
            statsPersisted = true,
        )
    }

    private fun earliestPermanentRemainingMillis(activity: Activity, rule: HookRule): Long? {
        if (!rule.enabled) return null
        val candidates = mutableListOf<Long>()
        if (rule.hasTimedQuota()) {
            candidates += thresholdStatus(activity, rule).remainingMillis
        }
        if (rule.hasSchedule()) {
            val decision = evaluateSchedule(rule)
            candidates += if (decision.allowed) {
                decision.millisUntilTransition(ZonedDateTime.now()) ?: Long.MAX_VALUE
            } else {
                0L
            }
        }
        return candidates.minOrNull()
    }

    private fun showSessionPlanWarning() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
        if (isBannerShowing(WarningBannerKind.SCHEDULE)) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.sessionPlanningEnabled || !rule.exitWarningEnabled) return
        val remainingMs = sessionPlanRemainingMillis()
        if (remainingMs == NOT_RUNNING) return
        if (remainingMs <= 0L) {
            checkSessionPlanDeadline()
            return
        }
        if (!SessionPlanPolicy.isEarliest(remainingMs, earliestPermanentRemainingMillis(activity, rule))) {
            scheduleSessionPlan(activity, rule)
            return
        }
        if (remainingMs > SessionPlanPolicy.WARNING_LEAD_MILLIS) {
            scheduleSessionPlan(activity, rule)
            return
        }
        if (sessionPlanWarningShown || isBannerShowing(WarningBannerKind.SESSION_PLAN)) return
        dismissWarning(resetForCurrentLimit = false)
        sessionPlanWarningShown = true
        runCatching {
            warningBanner = TopWarningBanner.attach(
                activity = activity,
                kind = WarningBannerKind.SESSION_PLAN,
                title = hookText(
                    activity,
                    rule,
                    "本次计划时间即将结束",
                    "This session plan is ending soon",
                ),
                message = sessionPlanWarningMessage(activity, rule, remainingMs),
                remainingMillis = remainingMs,
                maxProgressMillis = SessionPlanPolicy.WARNING_LEAD_MILLIS,
                fullScreen = rule.fullScreenExitWarningEnabled,
                actionLabel = hookText(activity, rule, "重新计划", "Replan"),
                onAction = {
                    if (!activity.isFinishing && !activity.isDestroyed && !exitScheduled) {
                        beginSessionReplan(activity, rule)
                    }
                },
            )
            if (!sessionPlanWarningVibrated) {
                sessionPlanWarningVibrated = true
                vibrateExitWarning(activity, rule)
            }
            startSessionPlanWarningCountdown(activity, rule)
        }.onFailure {
            sessionPlanWarningShown = false
            dismissSessionPlanWarning()
            diagnostic(activity, level = "ERROR", event = "SESSION_PLAN_WARNING_FAILED", message = it.toString())
        }
    }

    private fun startSessionPlanWarningCountdown(activity: Activity, rule: HookRule) {
        val countdown = object : Runnable {
            override fun run() {
                val banner = warningBanner
                    ?.takeIf { it.kind == WarningBannerKind.SESSION_PLAN && it.isAttached }
                    ?: return
                if (exitScheduled) return
                val remainingMs = sessionPlanRemainingMillis()
                if (remainingMs <= 0L) {
                    checkSessionPlanDeadline()
                    return
                }
                banner.update(
                    title = hookText(
                        activity,
                        rule,
                        "本次计划时间即将结束",
                        "This session plan is ending soon",
                    ),
                    message = sessionPlanWarningMessage(activity, rule, remainingMs),
                    remainingMillis = remainingMs,
                )
                mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = countdown
        mainHandler.post(countdown)
    }

    private fun sessionPlanWarningMessage(context: Context, rule: HookRule, remainingMs: Long): String {
        val seconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(0L)
        return hookText(
            context,
            rule,
            "$seconds 秒后退出 · 可重新制定本次计划",
            "Exit in $seconds sec · you can replan this session",
        )
    }

    private fun dismissSessionPlanWarning() {
        if (warningBanner?.kind == WarningBannerKind.SESSION_PLAN) {
            warningBanner?.remove()
            warningBanner = null
            warningCountdown?.let(mainHandler::removeCallbacks)
            warningCountdown = null
        }
        sessionPlanWarningShown = false
    }

    private fun scheduleDeadline(activity: Activity, rule: HookRule): Long {
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        val status = thresholdStatus(activity, rule)
        val remainingMs = status.remainingMillis
        if (status.reached) {
            forceExit(activity, rule, status)
        } else {
            val recheckMaxMillis = if (rule.systemUsagePending) {
                SYSTEM_USAGE_PENDING_RECHECK_MS
            } else {
                RULE_RECHECK_MAX_MS
            }
            mainHandler.postDelayed(deadline, minOf(remainingMs, recheckMaxMillis))
            if (
                rule.exitWarningEnabled &&
                (
                    warningShownForExtensionMs != grantedExtensionMs ||
                        !isBannerShowing(WarningBannerKind.TIME_LIMIT)
                    )
            ) {
                mainHandler.postDelayed(
                    warningDeadline,
                    UsageMath.warningDelayMillis(remainingMs, WARNING_LEAD_MS),
                )
            }
        }
        return remainingMs
    }

    private fun checkDeadline() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled) {
            diagnostic(activity, event = "TIMER_CANCEL", message = "规则已停用")
            stopTiming()
            return
        }
        val status = thresholdStatus(activity, rule)
        if (status.reached) {
            forceExit(activity, rule, status)
        } else {
            scheduleDeadline(activity, rule)
            if (
                !exitScheduled &&
                rule.sessionPlanningEnabled &&
                !sessionPlanPromptHandled &&
                !rule.systemUsagePending
            ) {
                resumeSessionPlan(activity, rule)
            }
        }
    }

    private fun scheduleMidnightRollover(rule: HookRule) {
        mainHandler.removeCallbacks(midnightDeadline)
        if (
            exitScheduled ||
            (!rule.dailyEnabled && !rule.groupDailyEnabled) ||
            foregroundStartedAt == NOT_RUNNING
        ) return
        val now = ZonedDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val delayMs = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
        mainHandler.postDelayed(midnightDeadline, delayMs)
    }

    private fun checkMidnightRollover() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || foregroundStartedAt == NOT_RUNNING) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled || (!rule.dailyEnabled && !rule.groupDailyEnabled)) {
            mainHandler.removeCallbacks(midnightDeadline)
            return
        }
        val currentDay = dayToken()
        if (foregroundDayToken != currentDay) {
            val fullSegmentMillis = activeSegmentMillis()
            val todaySegmentMillis = activeSegmentMillisForToday()
            if (rule.groupDailyEnabled) {
                val previousDaySegmentMillis = (fullSegmentMillis - todaySegmentMillis)
                    .coerceAtLeast(0L)
                if (previousDaySegmentMillis > 0L) {
                    recordUsageEvent(
                        activity = activity,
                        durationMillis = previousDaySegmentMillis,
                        launchIncrement = 0,
                        limitHitIncrement = 0,
                        eventDayToken = LocalDate.now().minusDays(1L).toString(),
                    )
                }
                if (todaySegmentMillis > 0L) {
                    recordUsageEvent(
                        activity = activity,
                        durationMillis = todaySegmentMillis,
                        launchIncrement = 0,
                        limitHitIncrement = 0,
                    )
                }
            }
            if (rule.perLaunchEnabled || rule.groupPerLaunchEnabled) {
                perLaunchCommittedMs += fullSegmentMillis
            }
            foregroundStartedAt = SystemClock.elapsedRealtime()
            foregroundDayToken = currentDay
            val refreshedRule = readRule(activity, reloadFallback = true)
            if (refreshedRule.dailyEnabled) {
                writeDailyState(
                    activity,
                    refreshedRule,
                    UsageMath.authoritativeDailyUsedMillis(
                        todaySegmentMillis,
                        refreshedRule.systemTodayUsedMillis,
                    ),
                )
            }
            updateGroupSegmentBaseline(refreshedRule)
            diagnostic(
                activity,
                event = "DAILY_RESET",
                message = "跨午夜后每日累计已重置；今日当前段=${todaySegmentMillis / 1000.0}s",
            )
            scheduleDeadline(activity, refreshedRule)
        }
        scheduleMidnightRollover(lastLoadedRule ?: rule)
    }

    private fun scheduleGroupUsageSync(rule: HookRule) {
        mainHandler.removeCallbacks(groupUsageSync)
        if (!exitScheduled && rule.groupDailyEnabled && foregroundStartedAt != NOT_RUNNING) {
            mainHandler.postDelayed(groupUsageSync, GROUP_USAGE_SYNC_INTERVAL_MS)
        }
    }

    private fun syncGroupUsage() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || foregroundStartedAt == NOT_RUNNING) return
        val currentRule = lastLoadedRule ?: readRule(activity, reloadFallback = true)
        if (!currentRule.enabled || !currentRule.groupDailyEnabled) return
        commitActiveSegment(activity, currentRule)
        foregroundStartedAt = SystemClock.elapsedRealtime()
        foregroundDayToken = dayToken()
        val refreshedRule = readRule(activity, reloadFallback = true)
        if (!refreshedRule.enabled) {
            stopTiming()
            return
        }
        if (!refreshedRule.groupDailyEnabled) {
            if (refreshedRule.hasTimedQuota()) {
                scheduleDeadline(activity, refreshedRule)
            }
            scheduleMidnightRollover(refreshedRule)
            return
        }
        updateGroupSegmentBaseline(refreshedRule)
        scheduleDeadline(activity, refreshedRule)
        if (!exitScheduled) scheduleGroupUsageSync(refreshedRule)
    }

    private fun scheduleScheduleBoundary(activity: Activity, rule: HookRule): Long? {
        if (exitScheduled) return null
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        if (!rule.hasSchedule()) return null
        val decision = evaluateSchedule(rule)
        if (!decision.allowed) {
            forceScheduleExit(activity, rule, decision, openedDuringBlockedTime = false)
            return 0L
        }
        val remainingMs = decision.millisUntilTransition(ZonedDateTime.now()) ?: return null
        mainHandler.postDelayed(scheduleDeadline, minOf(remainingMs, SCHEDULE_RECHECK_MAX_MS))
        if (rule.exitWarningEnabled && remainingMs <= SCHEDULE_RECHECK_MAX_MS) {
            mainHandler.postDelayed(
                scheduleWarningDeadline,
                UsageMath.warningDelayMillis(remainingMs, WARNING_LEAD_MS),
            )
        }
        return remainingMs
    }

    private fun checkScheduleBoundary() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled) {
            stopTiming()
            return
        }
        if (!rule.hasSchedule()) {
            mainHandler.removeCallbacks(scheduleWarningDeadline)
            dismissScheduleWarning()
            return
        }
        val decision = evaluateSchedule(rule)
        if (!decision.allowed) {
            forceScheduleExit(activity, rule, decision, openedDuringBlockedTime = false)
        } else {
            scheduleScheduleBoundary(activity, rule)
        }
    }

    private fun forceScheduleExit(
        activity: Activity,
        rule: HookRule,
        decision: ScheduleDecision,
        openedDuringBlockedTime: Boolean,
    ) {
        if (exitScheduled) return
        if (
            rule.limitEnforcementMode.usesBreakPage() &&
            !blockingOverlayFallback
        ) {
            if (blockForSchedule(activity, rule, decision, openedDuringBlockedTime)) return
            blockingOverlayFallback = true
        }
        exitScheduled = true
        cancelSessionPlan(dismissDialog = true)
        val scheduleHit = reportScheduleLimitHit(activity, rule, decision)
        val statsPersisted = scheduleHit.statsPersisted
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        val nextAllowed = formatNextTransition(activity, rule, decision)
        diagnostic(
            activity,
            level = "WARN",
            event = if (openedDuringBlockedTime) "SCHEDULE_DENIED" else "SCHEDULE_BOUNDARY_REACHED",
            message = "当前时段不可使用；app=${rule.scheduleEnabled}/${rule.scheduleMode}/${ScheduleCodec.encode(rule.scheduleWindows)}, group=${rule.groupScheduleEnabled}/${rule.groupScheduleMode}/${ScheduleCodec.encode(rule.groupScheduleWindows)}, nextAllowed=$nextAllowed, cooldownStarted=false, hitRecorded=${scheduleHit.recorded}, source=${rule.source}, pid=${Process.myPid()}",
        )
        val message = if (nextAllowed == null) {
            hookText(activity, rule, "当前处于不可用时段", "This time is unavailable")
        } else {
            hookText(
                activity,
                rule,
                "当前处于不可用时段，下次可用：$nextAllowed",
                "This time is unavailable. Next available: $nextAllowed",
            )
        }
        finishTarget(activity, message, statsPersisted)
    }

    private fun forceExit(activity: Activity, rule: HookRule, status: ThresholdStatus) {
        if (exitScheduled) return
        val incidentClaim = claimQuotaIncident(activity, rule, status)
        if (
            rule.limitEnforcementMode.usesBreakPage() &&
            !blockingOverlayFallback
        ) {
            if (blockForQuota(activity, rule, status, incidentClaim)) return
            blockingOverlayFallback = true
        }
        exitScheduled = true
        cancelSessionPlan(dismissDialog = true)
        dismissWarning(resetForCurrentLimit = false)
        val segmentMs = activeSegmentMillis()
        val statsPersisted = recordUsageEvent(
            activity = activity,
            durationMillis = if (rule.groupEnabled) segmentMs else 0L,
            launchIncrement = 0,
            limitHitIncrement = if (rule.usageStatsEnabled && incidentClaim.isNewIncident) {
                1
            } else {
                0
            },
        )
        if (rule.dailyEnabled) {
            val finalDailyUsed = authoritativeDailyTotalMillis(
                activity,
                rule,
                activeSegmentMillisForToday(),
            )
                .coerceAtMost(effectiveLimitMillis(rule.dailyLimitMillis))
            writeDailyState(activity, rule, finalDailyUsed)
        }
        if (rule.perLaunchEnabled || rule.groupPerLaunchEnabled) {
            perLaunchCommittedMs += segmentMs
        }
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        dismissScheduleWarning()

        diagnostic(
            activity,
            level = "WARN",
            event = "LIMIT_REACHED",
            message = "达到${status.reachedLabels}阈值（本次已延时 ${grantedExtensionMs / 1000}s），执行限制退出；incident=${incidentClaim.incidentId}, new=${incidentClaim.isNewIncident}, cooldownStarted=${incidentClaim.cooldownStarted}, pid=${Process.myPid()}",
        )
        finishTarget(
            activity,
            hookText(
                activity,
                rule,
                "已达到${status.reachedLabels}限制",
                "${status.reachedLabels} limit reached",
            ),
            statsPersisted,
        )
    }

    private fun forceCooldownExit(
        activity: Activity,
        rule: HookRule,
        remainingMillis: Long,
        statsPersisted: Boolean,
    ) {
        if (exitScheduled) return
        if (
            rule.limitEnforcementMode.usesBreakPage() &&
            !blockingOverlayFallback
        ) {
            if (blockForCooldown(activity, rule, remainingMillis)) return
            blockingOverlayFallback = true
        }
        exitScheduled = true
        cancelSessionPlan(dismissDialog = true)
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
        activeActivity.clear()
        stopTimingCallbacks()
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        val remainingText = formatCooldownRemaining(activity, rule, remainingMillis)
        diagnostic(
            activity,
            level = "WARN",
            event = "COOLDOWN_BLOCKED",
            message = "冷却期间拒绝打开；remaining=${remainingMillis / 1000.0}s, configured=${rule.configuredCooldownMillis() / 1000}s",
        )
        finishTarget(
            activity,
            hookText(
                activity,
                rule,
                "冷却中，$remainingText 后可再次打开",
                "Cooldown active. Reopen in $remainingText",
            ),
            statsPersisted,
        )
    }

    private fun blockForSchedule(
        activity: Activity,
        rule: HookRule,
        decision: ScheduleDecision,
        openedDuringBlockedTime: Boolean,
    ): Boolean {
        val nextAllowed = formatNextTransition(activity, rule, decision)
        val message = if (nextAllowed == null) {
            hookText(activity, rule, "当前处于不可用时段", "This time is unavailable")
        } else {
            hookText(
                activity,
                rule,
                "下次可用：$nextAllowed",
                "Next available: $nextAllowed",
            )
        }
        val token = "schedule:${rule.scheduleTokenVersion()}:${decision.nextTransition}"
        val newlyBlocked = blockingState?.token != token
        if (
            !showBlockingOverlay(
                activity,
                BlockingState(
                    LimitBlockReason.SCHEDULE,
                    token,
                    hookText(activity, rule, "当前时段不可使用", "Unavailable at this time"),
                    message,
                    ruleVersion = rule.version,
                    groupVersion = rule.groupVersion,
                ),
                rule,
            )
        ) return false
        cancelSessionPlan(dismissDialog = true)
        freezeForegroundForOverlay(activity, rule)
        val hit = if (newlyBlocked) {
            reportScheduleLimitHit(activity, rule, decision)
        } else {
            ScheduleHitResult(recorded = false, statsPersisted = true)
        }
        if (newlyBlocked) {
            diagnostic(
                activity,
                level = "WARN",
                event = if (openedDuringBlockedTime) {
                    "SCHEDULE_DENIED"
                } else {
                    "SCHEDULE_BOUNDARY_REACHED"
                },
                message = "独立休息页已显示；nextAllowed=$nextAllowed, cooldownStarted=false, hitRecorded=${hit.recorded}",
            )
        }
        return true
    }

    private fun blockForQuota(
        activity: Activity,
        rule: HookRule,
        status: ThresholdStatus,
        incidentClaim: QuotaIncidentClaim,
    ): Boolean {
        val token = "quota:${rule.version}:${rule.groupVersion}:${status.reachedLabels}"
        val quotaState = RestPagePolicy.quotaState(
            claimedCooldownEndsAtMillis = incidentClaim.cooldownEndsAtMillis,
            nowMillis = System.currentTimeMillis(),
        )
        val initialReason = quotaState.reason
        val initialTitle = if (initialReason == LimitBlockReason.COOLDOWN) {
            hookText(activity, rule, "休息一下", "Take a break")
        } else {
            hookText(activity, rule, "使用额度已耗尽", "Usage limit reached")
        }
        val initialMessage = if (initialReason == LimitBlockReason.COOLDOWN) {
            val remainingText = formatCooldownRemaining(
                activity,
                rule,
                (quotaState.cooldownEndsAtMillis - System.currentTimeMillis())
                    .coerceAtLeast(0L),
            )
            hookText(
                activity,
                rule,
                "$remainingText 后自动继续",
                "Continue automatically in $remainingText",
            )
        } else {
            hookText(
                activity,
                rule,
                "已达到${status.reachedLabels}限制，请等待额度恢复",
                "${status.reachedLabels} limit reached. Wait for it to reset.",
            )
        }
        if (
            !showBlockingOverlay(
                activity,
                BlockingState(
                    initialReason,
                    token,
                    initialTitle,
                    initialMessage,
                    ruleVersion = rule.version,
                    groupVersion = rule.groupVersion,
                    cooldownEndsAtMillis = quotaState.cooldownEndsAtMillis,
                    reachedKinds = status.reachedKinds,
                ),
                rule,
            )
        ) return false
        cancelSessionPlan(dismissDialog = true)
        val segmentMs = activeSegmentMillis()
        freezeForegroundForOverlay(activity, rule)
        if (incidentClaim.isNewIncident) {
            recordUsageEvent(
                activity,
                durationMillis = 0L,
                launchIncrement = 0,
                limitHitIncrement = if (rule.usageStatsEnabled) 1 else 0,
            )
        }
        if (incidentClaim.isNewIncident) {
            diagnostic(
                activity,
                level = "WARN",
                event = "LIMIT_REACHED",
                message = "达到${status.reachedLabels}阈值，执行限制页；segment=${segmentMs / 1000.0}s, incident=${incidentClaim.incidentId}, cooldownStarted=${incidentClaim.cooldownStarted}, mode=${rule.limitEnforcementMode}",
            )
        } else {
            diagnostic(
                activity,
                event = "QUOTA_INCIDENT_DUPLICATE",
                message = "同一额度事件已处理，不重复计数或启动冷却；incident=${incidentClaim.incidentId}",
            )
        }
        return true
    }

    private fun blockForCooldown(
        activity: Activity,
        rule: HookRule,
        remainingMillis: Long,
    ): Boolean {
        val remainingText = formatCooldownRemaining(activity, rule, remainingMillis)
        val token = "cooldown:${rule.cooldownToken()}"
        val newlyBlocked = blockingState?.token != token
        val reachedKinds = buildSet {
            addAll(blockingState?.reachedKinds.orEmpty())
            addAll(cooldownReachedKinds(activity, rule))
        }
        val cooldownEndsAtMillis = safeAdd(System.currentTimeMillis(), remainingMillis)
        if (
            !showBlockingOverlay(
                activity,
                BlockingState(
                    LimitBlockReason.COOLDOWN,
                    token,
                    hookText(activity, rule, "休息一下", "Take a break"),
                    hookText(
                        activity,
                        rule,
                        "$remainingText 后自动继续",
                        "Continue automatically in $remainingText",
                    ),
                    ruleVersion = rule.version,
                    groupVersion = rule.groupVersion,
                    cooldownEndsAtMillis = cooldownEndsAtMillis,
                    reachedKinds = reachedKinds,
                ),
                rule,
            )
        ) return false
        cancelSessionPlan(dismissDialog = true)
        freezeForegroundForOverlay(activity, rule)
        if (newlyBlocked) {
            diagnostic(
                activity,
                level = "WARN",
                event = "COOLDOWN_BLOCKED",
                message = "冷却期间显示独立休息页；remaining=${remainingMillis / 1000.0}s",
            )
        }
        return true
    }

    private fun freezeForegroundForOverlay(activity: Activity, rule: HookRule) {
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
        stopTimingCallbacks()
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
    }

    private fun showBlockingOverlay(
        activity: Activity,
        state: BlockingState,
        rule: HookRule = checkNotNull(lastLoadedRule),
    ): Boolean {
        val previous = blockingState
        return showExternalBreakPage(activity, state, rule, previous)
    }

    private fun showExternalBreakPage(
        activity: Activity,
        state: BlockingState,
        rule: HookRule,
        previous: BlockingState?,
    ): Boolean {
        val breakSessionToken = createBreakSessionToken(activity)
        if (breakSessionToken == null) {
            diagnostic(
                activity,
                level = "ERROR",
                event = "EXTERNAL_BREAK_PAGE_TOKEN_FAILED",
                message = "独立休息页授权令牌签发失败，将回退到安全退出",
            )
            return false
        }
        val shouldPauseMedia = previous?.token != state.token ||
            previous.reason != state.reason
        if (shouldPauseMedia) {
            val pauseResult = runCatching {
                mediaPauseController.pauseCommonMedia()
            }.onFailure { error ->
                diagnostic(
                    activity,
                    level = "WARN",
                    event = "MEDIA_PAUSE_FAILED",
                    message = "${error.javaClass.simpleName}:${error.message}",
                )
            }.getOrNull()
            if (pauseResult != null) {
                diagnostic(
                    activity,
                    event = "MEDIA_PAUSE_ATTEMPT",
                    message = "tracked=${pauseResult.trackedPlayers}, paused=${pauseResult.pausedPlayers}, webViews=${pauseResult.webViewsSignaled}",
                )
            }
        }
        val launched = runCatching {
            val intent = Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, LimitBlockActivity::class.java.name)
                putExtra(LimitBlockActivity.EXTRA_TARGET_PACKAGE, packageName)
                putExtra(
                    LimitBlockActivity.EXTRA_BREAK_SESSION_TOKEN,
                    breakSessionToken,
                )
                putExtra(LimitBlockActivity.EXTRA_TITLE, state.title)
                putExtra(LimitBlockActivity.EXTRA_MESSAGE, state.message)
                putExtra(LimitBlockActivity.EXTRA_RULE_VERSION, state.ruleVersion)
                putExtra(LimitBlockActivity.EXTRA_GROUP_VERSION, state.groupVersion)
                putExtra(
                    LimitBlockActivity.EXTRA_COOLDOWN_ENDS_AT,
                    state.cooldownEndsAtMillis,
                )
                putExtra(
                    LimitBlockActivity.EXTRA_REACHED_KINDS,
                    state.reachedKinds.joinToString(",") { it.name },
                )
                putExtra(LimitBlockActivity.EXTRA_DAY_TOKEN, LocalDate.now().toString())
                putExtra(LimitBlockActivity.EXTRA_ENGLISH, isEnglish(activity, rule))
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            activity.startActivity(intent)
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
            true
        }.onFailure { error ->
            diagnostic(
                activity,
                level = "ERROR",
                event = "EXTERNAL_BREAK_PAGE_FAILED",
                message = "独立休息页启动失败，将回退到安全退出；${error.javaClass.simpleName}:${error.message}",
            )
        }.getOrDefault(false)
        if (!launched) return false
        blockingState = state
        diagnostic(
            activity,
            event = when {
                previous == null -> "EXTERNAL_BREAK_PAGE_SHOWN"
                previous.reason != state.reason -> "OVERLAY_REASON_CHANGED"
                else -> "EXTERNAL_BREAK_PAGE_UPDATED"
            },
            message = "reason=${state.reason}, token=${state.token}",
        )
        return true
    }

    private fun createBreakSessionToken(context: Context): String? = runCatching {
        context.contentResolver.call(
            RuleContract.CONTENT_URI,
            RuleContract.METHOD_CREATE_BREAK_SESSION,
            packageName,
            null,
        )
    }.getOrNull()
        ?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) }
        ?.getString(RuleContract.KEY_BREAK_SESSION_TOKEN)
        ?.takeIf(String::isNotBlank)

    private fun detachBlockingOverlay() = Unit

    private fun removeBlockingOverlay(activity: Activity, reason: String) {
        if (blockingState == null) return
        blockingState = null
        blockingOverlayFallback = false
        diagnostic(activity, event = "EXTERNAL_BREAK_PAGE_REMOVED", message = reason)
    }

    private fun enforceBlockingConditions(
        activity: Activity,
        rule: HookRule,
        openedDuringBlockedTime: Boolean,
        launchStatsPersisted: Boolean,
    ): Boolean {
        if (!rule.enabled) {
            if (blockingState != null) {
                removeBlockingOverlay(activity, "规则已关闭")
            }
            return false
        }
        completeRestCycleIfNeeded(activity, rule)
        val scheduleDecision = rule.hasSchedule().takeIf { it }?.let { evaluateSchedule(rule) }
        val cooldownRemaining = cooldownRemainingMillis(activity, rule)
        val thresholdStatus = rule.hasTimedQuota().takeIf { it }?.let {
            thresholdStatus(activity, rule)
        }
        return when (
            LimitEnforcementPolicy.evaluate(
                LimitGateSnapshot(
                    scheduleBlocked = scheduleDecision?.allowed == false,
                    cooldownRemainingMillis = cooldownRemaining,
                    quotaReached = thresholdStatus?.reached == true,
                ),
            ).blockingReason
        ) {
            LimitBlockReason.SCHEDULE -> {
                forceScheduleExit(
                    activity,
                    rule,
                    checkNotNull(scheduleDecision),
                    openedDuringBlockedTime,
                )
                true
            }
            LimitBlockReason.COOLDOWN -> {
                forceCooldownExit(activity, rule, cooldownRemaining, launchStatsPersisted)
                true
            }
            LimitBlockReason.QUOTA -> {
                forceExit(activity, rule, checkNotNull(thresholdStatus))
                true
            }
            null -> {
                if (blockingState != null) {
                    removeBlockingOverlay(activity, "限制条件已经解除")
                }
                false
            }
        }
    }

    private fun suppressSessionPlanForBlock(
        context: Context,
        reason: LimitBlockReason?,
    ) {
        cancelPendingSessionPlanPrompt()
        sessionPlanDialog?.dismiss()
        sessionPlanDialog = null
        activeSessionPlanDialogMode = null
        diagnostic(
            context,
            event = "SESSION_PLAN_SUPPRESSED_BLOCKED",
            message = "限制状态优先，本次计划未显示或已关闭；reason=${reason ?: "pending_exit"}",
        )
    }

    private fun completeRestCycleIfNeeded(activity: Activity, rule: HookRule): Boolean {
        val state = blockingState ?: return false
        val nowMillis = System.currentTimeMillis()
        if (
            !RestPagePolicy.shouldStartNewPerLaunchCycle(
                mode = rule.limitEnforcementMode,
                previousReason = state.reason,
                cooldownEndsAtMillis = state.cooldownEndsAtMillis,
                nowMillis = nowMillis,
                reachedKinds = state.reachedKinds,
            )
        ) return false
        perLaunchCommittedMs = 0L
        perLaunchCycleGeneration++
        grantedExtensionMs = 0L
        warningShownForExtensionMs = Long.MIN_VALUE
        warningVibratedForExtensionMs = Long.MIN_VALUE
        incidentOccurredAtMillis.keys.removeAll { incidentId ->
            incidentId.startsWith("app-launch|") ||
                incidentId.startsWith("group-launch|")
        }
        blockingState = state.copy(
            reachedKinds = state.reachedKinds.filterNotTo(linkedSetOf()) {
                it == QuotaKind.APP_PER_LAUNCH || it == QuotaKind.GROUP_PER_LAUNCH
            },
            cooldownEndsAtMillis = 0L,
        )
        diagnostic(
            activity,
            event = "REST_CYCLE_RESUMED",
            message = "冷静期结束，单次额度开始新一轮；cycle=$perLaunchCycleGeneration",
        )
        return true
    }

    private fun applyRuleVersionChange(rule: HookRule) {
        if (loadedRuleVersion == rule.version && loadedGroupVersion == rule.groupVersion) return
        loadedRuleVersion = rule.version
        loadedGroupVersion = rule.groupVersion
        perLaunchCommittedMs = 0L
        grantedExtensionMs = 0L
        warningShownForExtensionMs = Long.MIN_VALUE
        warningVibratedForExtensionMs = Long.MIN_VALUE
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
    }

    private fun finishTarget(activity: Activity, message: String, statsPersisted: Boolean) {
        runCatching {
            // The task can disappear before the system Toast does. Keep this message short and
            // state-based; future-tense countdown copy belongs only in the pre-exit banner.
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }.onFailure {
            diagnostic(activity, level = "WARN", event = "EXIT_NOTICE_FAILED", message = it.toString())
        }
        runCatching {
            activity.finishAndRemoveTask()
        }.onFailure {
            diagnostic(activity, level = "ERROR", event = "FINISH_TASK_FAILED", message = it.toString())
        }
        runCatching {
            activity.finishAffinity()
        }.onFailure {
            diagnostic(activity, level = "ERROR", event = "FINISH_AFFINITY_FAILED", message = it.toString())
        }
        runCatching {
            activity.moveTaskToBack(true)
        }.onFailure {
            diagnostic(activity, level = "ERROR", event = "MOVE_TASK_FAILED", message = it.toString())
        }
        if (!isSafeToTerminateProcess(activity)) {
            diagnostic(
                activity,
                level = "WARN",
                event = "PROCESS_KILL_SKIPPED",
                message = "为避免影响系统稳定性，仅关闭界面；uid=${Process.myUid()}, process=$processName",
            )
            mainHandler.postDelayed({ exitScheduled = false }, EXIT_RECOVERY_DELAY_MS)
            return
        }
        if (!statsPersisted) {
            // The normal retry may already have exhausted its backoff budget. Force one final
            // attempt while this process is still alive so the limit-hit counter is not lost.
            mainHandler.removeCallbacks(statsRetry)
            mainHandler.postDelayed(statsRetry, FINAL_STATS_RETRY_DELAY_MS)
        }
        mainHandler.postDelayed(
            {
                runCatching { Process.killProcess(Process.myPid()) }
                mainHandler.postDelayed({ exitScheduled = false }, EXIT_RECOVERY_DELAY_MS)
            },
            if (statsPersisted) EXIT_DELAY_MS else EXIT_DELAY_AFTER_STATS_FAILURE_MS,
        )
    }

    private fun isSafeToTerminateProcess(activity: Activity): Boolean =
        ProcessTerminationPolicy.mayTerminate(
            targetPackage = packageName,
            activityPackage = activity.packageName,
            uid = Process.myUid(),
            firstApplicationUid = Process.FIRST_APPLICATION_UID,
            isSystemApp = activity.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
        )

    private fun cooldownRemainingMillis(context: Context, rule: HookRule): Long {
        val nowMillis = System.currentTimeMillis()
        val localRemaining = SharedCooldownPolicy.remainingMillis(
            localCooldownRecord(context, rule),
            nowMillis,
        )
        val sharedRemaining = if (rule.groupEnabled && rule.groupCooldownEnabled) {
            (rule.groupCooldownEndsAtMillis - nowMillis).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(localRemaining, sharedRemaining)
    }

    private fun claimQuotaIncident(
        context: Context,
        rule: HookRule,
        status: ThresholdStatus,
    ): QuotaIncidentClaim {
        val incidentId = QuotaIncidentPolicy.incidentId(
            packageName = packageName,
            ruleVersion = rule.version,
            groupId = rule.groupId,
            groupVersion = rule.groupVersion,
            dayToken = LocalDate.now().toString(),
            processSessionId = "$processSessionId:$perLaunchCycleGeneration",
            reachedKinds = status.reachedKinds,
        ) ?: return QuotaIncidentClaim(
            incidentId = "",
            isNewIncident = false,
            cooldownStarted = false,
            cooldownStartedAtMillis = 0L,
            cooldownEndsAtMillis = 0L,
        )
        val occurredAt = incidentOccurredAtMillis.getOrPut(incidentId) {
            System.currentTimeMillis()
        }
        if (status.groupOnlyReached && rule.groupEnabled && rule.groupId.isNotBlank()) {
            val providerClaim = claimGroupQuotaIncident(
                context = context,
                rule = rule,
                incidentId = incidentId,
                occurredAtMillis = occurredAt,
            )
            if (providerClaim != null) return providerClaim
        }
        return claimLocalQuotaIncident(
            context = context,
            rule = rule,
            incidentId = incidentId,
            occurredAtMillis = occurredAt,
            durationMillis = if (status.groupOnlyReached) {
                rule.groupCooldownMillis.takeIf { rule.groupCooldownEnabled } ?: 0L
            } else {
                rule.cooldownMillis.takeIf { rule.cooldownEnabled } ?: 0L
            },
        )
    }

    private fun claimGroupQuotaIncident(
        context: Context,
        rule: HookRule,
        incidentId: String,
        occurredAtMillis: Long,
    ): QuotaIncidentClaim? {
        val extras = Bundle().apply {
            putString(RuleContract.KEY_GROUP_ID, rule.groupId)
            putString(RuleContract.KEY_INCIDENT_ID, incidentId)
            putLong(RuleContract.KEY_INCIDENT_OCCURRED_AT_MS, occurredAtMillis)
        }
        val result = runCatching {
            context.contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_CLAIM_GROUP_COOLDOWN,
                packageName,
                extras,
            )
        }.getOrNull()?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) } ?: return null
        val event = result.getString(RuleContract.KEY_EVENT).orEmpty()
        val isNew = result.getBoolean(RuleContract.KEY_INCIDENT_NEW, false)
        val cooldownStarted = result.getBoolean(
            RuleContract.KEY_GROUP_COOLDOWN_STARTED,
            false,
        )
        val cooldownStartedAtMillis = result.getLong(
            RuleContract.KEY_GROUP_COOLDOWN_STARTED_AT_MS,
            0L,
        )
        val cooldownEndsAtMillis = result.getLong(
            RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS,
            0L,
        )
        diagnostic(
            context,
            event = event.ifBlank {
                if (isNew) "GROUP_COOLDOWN_STARTED" else "GROUP_COOLDOWN_REUSED"
            },
            message = "group=${rule.groupId}, incident=$incidentId, new=$isNew, started=$cooldownStarted, end=${result.getLong(RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS, 0L)}",
        )
        return QuotaIncidentClaim(
            incidentId = incidentId,
            isNewIncident = isNew,
            cooldownStarted = cooldownStarted,
            cooldownStartedAtMillis = cooldownStartedAtMillis,
            cooldownEndsAtMillis = cooldownEndsAtMillis,
        )
    }

    private fun claimLocalQuotaIncident(
        context: Context,
        rule: HookRule,
        incidentId: String,
        occurredAtMillis: Long,
        durationMillis: Long,
    ): QuotaIncidentClaim {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val handled = prefs.getString(KEY_HANDLED_QUOTA_INCIDENTS, null)
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val nowMillis = System.currentTimeMillis()
        val claim = SharedCooldownPolicy.claim(
            existingRecord = localCooldownRecord(context, rule),
            handledIncidentIds = handled,
            incidentId = incidentId,
            sourcePackage = packageName,
            occurredAtMillis = occurredAtMillis,
            durationMillis = durationMillis,
            nowMillis = nowMillis,
        )
        val editor = prefs.edit()
            .putString(
                KEY_HANDLED_QUOTA_INCIDENTS,
                claim.handledIncidentIds.joinToString("\n"),
            )
        if (claim.record.endsAtMillis > nowMillis) {
            editor
                .putLong(KEY_COOLDOWN_STARTED_AT, claim.record.startedAtMillis)
                .putLong(KEY_COOLDOWN_ENDS_AT, claim.record.endsAtMillis)
                .putString(KEY_COOLDOWN_INCIDENT_ID, claim.record.incidentId)
                .putString(KEY_COOLDOWN_RULE_IDENTITY, rule.localCooldownIdentity())
        } else {
            editor
                .remove(KEY_COOLDOWN_STARTED_AT)
                .remove(KEY_COOLDOWN_ENDS_AT)
                .remove(KEY_COOLDOWN_INCIDENT_ID)
                .remove(KEY_COOLDOWN_RULE_IDENTITY)
        }
        val persisted = editor.remove(KEY_COOLDOWN_RULE_VERSION).commit()
        if (!persisted) {
            diagnostic(
                context,
                level = "ERROR",
                event = "COOLDOWN_PERSIST_FAILED",
                message = "本地额度事件同步保存失败；incident=$incidentId",
            )
        }
        if (claim.cooldownStarted) {
            diagnostic(
                context,
                event = if (rule.groupEnabled) {
                    "GROUP_COOLDOWN_STARTED_LOCAL_FALLBACK"
                } else {
                    "COOLDOWN_STARTED"
                },
                message = "incident=$incidentId, duration=${durationMillis / 1000}s",
            )
        }
        return QuotaIncidentClaim(
            incidentId = incidentId,
            isNewIncident = claim.isNewIncident,
            cooldownStarted = claim.cooldownStarted,
            cooldownStartedAtMillis = claim.record.startedAtMillis,
            cooldownEndsAtMillis = claim.record.endsAtMillis,
        )
    }

    private fun localCooldownRecord(context: Context, rule: HookRule): SharedCooldownRecord {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (
            prefs.getString(KEY_COOLDOWN_RULE_IDENTITY, null) !=
            rule.localCooldownIdentity()
        ) return SharedCooldownRecord()
        val startedAt = prefs.getLong(KEY_COOLDOWN_STARTED_AT, 0L)
        val storedEnd = prefs.getLong(KEY_COOLDOWN_ENDS_AT, 0L)
        val migratedEnd = if (storedEnd > 0L) {
            storedEnd
        } else {
            val duration = if (rule.groupEnabled) {
                rule.groupCooldownMillis.takeIf { rule.groupCooldownEnabled } ?: 0L
            } else {
                rule.cooldownMillis.takeIf { rule.cooldownEnabled } ?: 0L
            }
            if (startedAt > 0L) startedAt + duration else 0L
        }
        return SharedCooldownRecord(
            startedAtMillis = startedAt,
            endsAtMillis = migratedEnd,
            incidentId = prefs.getString(KEY_COOLDOWN_INCIDENT_ID, null).orEmpty(),
            sourcePackage = packageName,
        )
    }

    private fun HookRule.localCooldownIdentity(): String =
        if (groupEnabled) {
            "group-fallback:$groupId:$groupVersion:$groupCooldownMillis"
        } else {
            "app:$version:$cooldownMillis"
        }

    private fun HookRule.configuredCooldownMillis(): Long =
        if (groupEnabled) {
            groupCooldownMillis.takeIf { groupCooldownEnabled } ?: 0L
        } else {
            cooldownMillis.takeIf { cooldownEnabled } ?: 0L
        }

    private fun HookRule.cooldownToken(): String =
        "$groupId:$groupCooldownEndsAtMillis:${localCooldownIdentity()}"

    private fun LimitEnforcementMode.usesBreakPage(): Boolean =
        this == LimitEnforcementMode.EXTERNAL_BREAK_PAGE

    private fun cooldownReachedKinds(context: Context, rule: HookRule): Set<QuotaKind> =
        buildSet {
            quotaKindFromIncident(rule.groupCooldownIncidentId)?.let(::add)
            quotaKindFromIncident(localCooldownRecord(context, rule).incidentId)?.let(::add)
        }

    private fun quotaKindFromIncident(incidentId: String): QuotaKind? {
        val parts = incidentId.split('|')
        return when (parts.firstOrNull()) {
            "group-daily" -> QuotaKind.GROUP_DAILY
            "group-launch" -> if (
                parts.getOrNull(3) == packageName &&
                isCurrentPerLaunchSession(parts.getOrNull(4))
            ) {
                QuotaKind.GROUP_PER_LAUNCH
            } else {
                null
            }
            "app-daily" -> if (parts.getOrNull(1) == packageName) {
                QuotaKind.APP_DAILY
            } else {
                null
            }
            "app-launch" -> if (
                parts.getOrNull(1) == packageName &&
                isCurrentPerLaunchSession(parts.getOrNull(3))
            ) {
                QuotaKind.APP_PER_LAUNCH
            } else {
                null
            }
            else -> null
        }
    }

    private fun isCurrentPerLaunchSession(candidate: String?): Boolean =
        candidate == "$processSessionId:$perLaunchCycleGeneration" ||
            (perLaunchCycleGeneration == 0L && candidate == processSessionId)

    private fun formatCooldownRemaining(
        context: Context,
        rule: HookRule,
        remainingMillis: Long,
    ): String {
        val totalSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (isEnglish(context, rule)) {
            when {
                minutes == 0L -> "$seconds sec"
                seconds == 0L -> "$minutes min"
                else -> "$minutes min $seconds sec"
            }
        } else {
            when {
                minutes == 0L -> "$seconds 秒"
                seconds == 0L -> "$minutes 分钟"
                else -> "$minutes 分 $seconds 秒"
            }
        }
    }

    private fun stopTimingCallbacks() {
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
    }

    private fun commitActiveSegment(activity: Activity, rule: HookRule) {
        val segmentMs = activeSegmentMillis()
        if (segmentMs <= 0L) {
            foregroundStartedAt = NOT_RUNNING
            foregroundDayToken = -1
            return
        }
        if (rule.dailyEnabled) {
            writeDailyState(
                activity,
                rule,
                authoritativeDailyTotalMillis(activity, rule, activeSegmentMillisForToday()),
            )
        }
        if (rule.perLaunchEnabled || rule.groupPerLaunchEnabled) {
            perLaunchCommittedMs += segmentMs
        }
        if (rule.groupEnabled) {
            recordUsageEvent(
                activity = activity,
                durationMillis = segmentMs,
                launchIncrement = 0,
                limitHitIncrement = 0,
            )
        }
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
    }

    private fun reportLimitHit(activity: Activity, rule: HookRule): Boolean =
        recordUsageEvent(
            activity = activity,
            durationMillis = 0L,
            launchIncrement = 0,
            limitHitIncrement = if (rule.usageStatsEnabled) 1 else 0,
        )

    private fun reportScheduleLimitHit(
        activity: Activity,
        rule: HookRule,
        decision: ScheduleDecision,
    ): ScheduleHitResult {
        val blockToken = ScheduleBlockPolicy.token(
            ruleVersion = rule.scheduleTokenVersion(),
            mode = rule.scheduleModeForToken(),
            nextTransitionEpochMillis = decision.nextTransition?.toInstant()?.toEpochMilli(),
        )
        val prefs = activity.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (!ScheduleBlockPolicy.shouldRecord(prefs.getString(KEY_SCHEDULE_BLOCK_TOKEN, null), blockToken)) {
            return ScheduleHitResult(recorded = false, statsPersisted = true)
        }
        val markerPersisted = prefs.edit()
            .putString(KEY_SCHEDULE_BLOCK_TOKEN, blockToken)
            .commit()
        val statsPersisted = reportLimitHit(activity, rule)
        if (!markerPersisted) {
            diagnostic(
                activity,
                level = "WARN",
                event = "SCHEDULE_BLOCK_MARKER_FAILED",
                message = "时段阻止标记写入失败，后续重复打开可能重复计数；token=$blockToken",
            )
        }
        return ScheduleHitResult(recorded = true, statsPersisted = statsPersisted)
    }

    private fun recordUsageEvent(
        activity: Activity,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
        eventDayToken: String = LocalDate.now().toString(),
    ): Boolean {
        val context = activity.applicationContext
        statsContext = context
        loadStatsOutbox(context)
        val pending = pendingStatsByDay.getOrPut(eventDayToken) { PendingUsageBatch() }
        pending.durationMillis = safeAdd(
            pending.durationMillis,
            durationMillis.coerceAtLeast(0L),
        ).coerceAtMost(MAX_STATS_DURATION_PER_DAY_MS)
        pending.launches = safeAdd(pending.launches, launchIncrement.coerceAtLeast(0))
        pending.limitHits = safeAdd(pending.limitHits, limitHitIncrement.coerceAtLeast(0))
        persistStatsOutbox(context)
        return flushUsageEvents(context)
    }

    private fun loadStatsOutbox(context: Context) {
        if (statsOutboxLoaded) return
        val prefs = context.getSharedPreferences(STATS_OUTBOX_PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(OUTBOX_DAYS, emptySet()).orEmpty()
            .asSequence()
            .filter { runCatching { LocalDate.parse(it) }.isSuccess }
            .sorted()
            .forEach { day ->
                pendingStatsByDay[day] = PendingUsageBatch(
                    durationMillis = prefs.getLong(
                        "$OUTBOX_DURATION_MS.$day",
                        0L,
                    ).coerceIn(0L, MAX_STATS_DURATION_PER_DAY_MS),
                    launches = prefs.getInt("$OUTBOX_LAUNCHES.$day", 0).coerceAtLeast(0),
                    limitHits = prefs.getInt("$OUTBOX_LIMIT_HITS.$day", 0).coerceAtLeast(0),
                )
            }

        // v0.9.1 之前只有一个无日期分桶。无法恢复原始日期时按迁移当天处理，
        // 后续记录均使用带日期的新格式，不再发生跨零点混记。
        if (pendingStatsByDay.isEmpty() && prefs.getBoolean(OUTBOX_PENDING, false)) {
            pendingStatsByDay[LocalDate.now().toString()] = PendingUsageBatch(
                durationMillis = prefs.getLong(OUTBOX_DURATION_MS, 0L)
                    .coerceIn(0L, MAX_STATS_DURATION_PER_DAY_MS),
                launches = prefs.getInt(OUTBOX_LAUNCHES, 0).coerceAtLeast(0),
                limitHits = prefs.getInt(OUTBOX_LIMIT_HITS, 0).coerceAtLeast(0),
            )
        }
        statsOutboxLoaded = true
    }

    private fun persistStatsOutbox(context: Context) {
        val editor = context.getSharedPreferences(STATS_OUTBOX_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putStringSet(OUTBOX_DAYS, pendingStatsByDay.keys.toSet())
        pendingStatsByDay.forEach { (day, batch) ->
            editor
                .putLong("$OUTBOX_DURATION_MS.$day", batch.durationMillis)
                .putInt("$OUTBOX_LAUNCHES.$day", batch.launches)
                .putInt("$OUTBOX_LIMIT_HITS.$day", batch.limitHits)
        }
        editor.commit()
    }

    private fun flushUsageEvents(context: Context): Boolean {
        while (pendingStatsByDay.isNotEmpty()) {
            val (day, batch) = pendingStatsByDay.entries.minBy { it.key }
            val extras = Bundle().apply {
                putString(RuleContract.KEY_DAY_TOKEN, day)
                putLong(RuleContract.KEY_DURATION_MS, batch.durationMillis)
                putInt(RuleContract.KEY_LAUNCH_INCREMENT, batch.launches)
                putInt(RuleContract.KEY_LIMIT_HIT_INCREMENT, batch.limitHits)
                putInt(RuleContract.KEY_HOOK_VERSION_CODE, BuildConfig.VERSION_CODE)
            }
            val result = runCatching {
                context.contentResolver.call(
                    RuleContract.CONTENT_URI,
                    RuleContract.METHOD_RECORD_USAGE,
                    packageName,
                    extras,
                )
            }
            val persisted = result.getOrNull()?.getBoolean(RuleContract.KEY_OK, false) == true
            if (persisted) {
                pendingStatsByDay.remove(day)
                persistStatsOutbox(context)
                statsRetryCount = 0
                statsFailureLogged = false
                mainHandler.removeCallbacks(statsRetry)
                if (!statsSuccessLogged) {
                    statsSuccessLogged = true
                    XposedBridge.log("AppTimeLimiter: STATS_REPORT_OK package=$packageName")
                    diagnostic(
                        context,
                        event = "STATS_REPORT_OK",
                        message = "使用统计已成功写入；管理应用无需后台常驻",
                    )
                }
                continue
            }

            if (!statsFailureLogged) {
                statsFailureLogged = true
                XposedBridge.log(
                    "AppTimeLimiter: STATS_REPORT_FAILED package=$packageName day=$day error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "provider_rejected"}",
                )
                result.exceptionOrNull()?.let(XposedBridge::log)
                diagnostic(
                    context,
                    level = "WARN",
                    event = "STATS_REPORT_FAILED",
                    message = "使用统计写入失败，将自动重试；日期=$day；原因=${result.exceptionOrNull()?.javaClass?.simpleName ?: "provider_rejected"}",
                )
            }
            mainHandler.removeCallbacks(statsRetry)
            if (statsRetryCount < MAX_STATS_RETRIES) {
                val delayMs = STATS_RETRY_DELAYS_MS[statsRetryCount]
                statsRetryCount++
                mainHandler.postDelayed(statsRetry, delayMs)
            }
            return false
        }
        mainHandler.removeCallbacks(statsRetry)
        return true
    }

    private fun safeAdd(current: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment

    private fun safeAdd(current: Int, increment: Int): Int =
        if (increment > Int.MAX_VALUE - current) Int.MAX_VALUE else current + increment

    private fun stopTiming() {
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
        activeActivity.clear()
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        groupSegmentIdentity = ""
        groupSegmentBaselineDay = ""
        groupSegmentBaselineUsedMs = 0L
        dismissWarning(resetForCurrentLimit = true)
        dismissScheduleWarning()
    }

    private fun showExitWarning() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
        if (isBannerShowing(WarningBannerKind.SCHEDULE)) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled || !rule.exitWarningEnabled) return

        val status = thresholdStatus(activity, rule)
        val remainingMs = status.remainingMillis
        if (status.reached) {
            forceExit(activity, rule, status)
            return
        }
        val planRemainingMs = sessionPlanRemainingMillis()
        if (
            planRemainingMs != NOT_RUNNING &&
            SessionPlanPolicy.isEarliest(planRemainingMs, remainingMs)
        ) {
            scheduleSessionPlan(activity, rule)
            return
        }
        if (remainingMs > WARNING_LEAD_MS) {
            scheduleDeadline(activity, rule)
            return
        }
        if (isBannerShowing(WarningBannerKind.TIME_LIMIT)) return
        val firstPresentation = warningShownForExtensionMs != grantedExtensionMs
        warningShownForExtensionMs = grantedExtensionMs

        runCatching {
            warningBanner = TopWarningBanner.attach(
                activity = activity,
                kind = WarningBannerKind.TIME_LIMIT,
                title = hookText(
                    activity,
                    rule,
                    "${status.nextThresholdLabel}即将到期",
                    "${status.nextThresholdLabel} ending soon",
                ),
                message = exitWarningMessage(activity, rule, remainingMs, rule.extensionMillis),
                remainingMillis = remainingMs,
                maxProgressMillis = WARNING_LEAD_MS,
                fullScreen = rule.fullScreenExitWarningEnabled,
                actionLabel = hookText(
                    activity,
                    rule,
                    if (rule.fullScreenExitWarningEnabled) {
                        "延时 ${compactMinutes(rule.extensionMillis)}分"
                    } else {
                        "延时 ${formatDuration(activity, rule, rule.extensionMillis)}"
                    },
                    if (rule.fullScreenExitWarningEnabled) {
                        "Extend ${compactMinutes(rule.extensionMillis)}m"
                    } else {
                        "Extend ${formatDuration(activity, rule, rule.extensionMillis)}"
                    },
                ),
                onAction = {
                    if (!activity.isFinishing && !activity.isDestroyed && !exitScheduled) {
                        dismissWarning(resetForCurrentLimit = false)
                        grantExtension(activity, rule.extensionMillis)
                    }
                },
            )
            if (warningVibratedForExtensionMs != grantedExtensionMs) {
                warningVibratedForExtensionMs = grantedExtensionMs
                vibrateExitWarning(activity, rule)
            }
            if (firstPresentation) {
                diagnostic(
                    activity,
                    event = "WARNING_SHOWN",
                    message = "顶部退出提醒已显示；可延时=${rule.extensionMillis / 1000}s",
                )
            }
            startWarningCountdown(activity, rule)
        }.onFailure {
            warningShownForExtensionMs = Long.MIN_VALUE
            dismissWarning(resetForCurrentLimit = false)
            diagnostic(activity, level = "ERROR", event = "WARNING_FAILED", message = it.toString())
        }
    }

    private fun showScheduleWarning() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled || !rule.hasSchedule() || !rule.exitWarningEnabled) return
        val decision = evaluateSchedule(rule)
        if (!decision.allowed) {
            forceScheduleExit(activity, rule, decision, openedDuringBlockedTime = false)
            return
        }
        val remainingMs = decision.millisUntilTransition(ZonedDateTime.now()) ?: return
        if (remainingMs > WARNING_LEAD_MS) {
            scheduleScheduleBoundary(activity, rule)
            return
        }
        if (isBannerShowing(WarningBannerKind.SCHEDULE)) return
        dismissSessionPlanWarning()
        dismissWarning(resetForCurrentLimit = false)
        runCatching {
            warningBanner = TopWarningBanner.attach(
                activity = activity,
                kind = WarningBannerKind.SCHEDULE,
                title = hookText(
                    activity,
                    rule,
                    "即将进入不可用时段",
                    "Unavailable time approaching",
                ),
                message = scheduleWarningMessage(activity, rule, remainingMs),
                remainingMillis = remainingMs,
                maxProgressMillis = WARNING_LEAD_MS,
                fullScreen = rule.fullScreenExitWarningEnabled,
            )
            vibrateExitWarning(activity, rule)
            diagnostic(
                activity,
                event = "SCHEDULE_WARNING_SHOWN",
                message = "顶部时段提醒已显示",
            )
            startScheduleWarningCountdown(activity, rule)
        }.onFailure {
            dismissScheduleWarning()
            diagnostic(activity, level = "ERROR", event = "SCHEDULE_WARNING_FAILED", message = it.toString())
        }
    }

    private fun startScheduleWarningCountdown(activity: Activity, rule: HookRule) {
        val countdown = object : Runnable {
            override fun run() {
                val banner = warningBanner
                    ?.takeIf { it.kind == WarningBannerKind.SCHEDULE && it.isAttached }
                    ?: return
                if (exitScheduled) return
                val decision = evaluateSchedule(rule)
                if (!decision.allowed) {
                    checkScheduleBoundary()
                    return
                }
                val remainingMs = decision.millisUntilTransition(ZonedDateTime.now()) ?: return
                banner.update(
                    title = hookText(
                        activity,
                        rule,
                        "即将进入不可用时段",
                        "Unavailable time approaching",
                    ),
                    message = scheduleWarningMessage(activity, rule, remainingMs),
                    remainingMillis = remainingMs,
                )
                if (remainingMs > 0L) mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = countdown
        mainHandler.post(countdown)
    }

    private fun scheduleWarningMessage(
        context: Context,
        rule: HookRule,
        remainingMs: Long,
    ): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return hookText(
            context,
            rule,
            "$seconds 秒后退出 · 时段限制不可延时",
            "Exit in $seconds sec · schedule limits cannot be extended",
        )
    }

    private fun startWarningCountdown(activity: Activity, rule: HookRule) {
        val countdown = object : Runnable {
            override fun run() {
                val banner = warningBanner
                    ?.takeIf { it.kind == WarningBannerKind.TIME_LIMIT && it.isAttached }
                    ?: return
                if (exitScheduled) return
                val status = thresholdStatus(activity, rule)
                val remainingMs = status.remainingMillis
                if (status.reached) {
                    forceExit(activity, rule, status)
                    return
                }
                val planRemainingMs = sessionPlanRemainingMillis()
                if (
                    planRemainingMs != NOT_RUNNING &&
                    SessionPlanPolicy.isEarliest(planRemainingMs, remainingMs)
                ) {
                    dismissWarning(resetForCurrentLimit = false)
                    showSessionPlanWarning()
                    return
                }
                banner.update(
                    title = hookText(
                        activity,
                        rule,
                        "${status.nextThresholdLabel}即将到期",
                        "${status.nextThresholdLabel} ending soon",
                    ),
                    message = exitWarningMessage(
                        activity,
                        rule,
                        remainingMs,
                        rule.extensionMillis,
                    ),
                    remainingMillis = remainingMs,
                )
                if (remainingMs > 0L) mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = countdown
        mainHandler.post(countdown)
    }

    private fun grantExtension(activity: Activity, extensionMillis: Long) {
        val granted = extensionMillis.coerceAtLeast(RuleRepository.MIN_EXTENSION_SECONDS * 1000L)
        grantedExtensionMs = UsageMath.addExtensionMillis(
            grantedExtensionMs,
            granted,
            MAX_TOTAL_EXTENSION_MS,
        )
        warningShownForExtensionMs = Long.MIN_VALUE
        diagnostic(
            activity,
            event = "EXTENSION_GRANTED",
            message = "本次追加=${granted / 1000}s，累计延时=${grantedExtensionMs / 1000}s",
        )
        val latestRule = readRule(activity, reloadFallback = true)
        scheduleDeadline(activity, latestRule)
        scheduleSessionPlan(activity, latestRule)
    }

    private fun compactMinutes(durationMillis: Long): Long =
        ((durationMillis.coerceAtLeast(0L) + 59_999L) / 60_000L).coerceAtLeast(1L)

    private fun dismissWarning(resetForCurrentLimit: Boolean) {
        dismissBanner(WarningBannerKind.TIME_LIMIT)
        if (resetForCurrentLimit) warningShownForExtensionMs = Long.MIN_VALUE
    }

    private fun vibrateExitWarning(context: Context, rule: HookRule) {
        if (!rule.exitWarningVibrationEnabled) return
        val appContext = context.applicationContext
        runCatching {
            Thread(
                { performExitWarningVibration(appContext) },
                "time-stop-warning-vibration",
            ).apply { isDaemon = true }.start()
        }.onFailure {
            diagnostic(
                context,
                level = "WARN",
                event = "WARNING_VIBRATION_THREAD_FAILED",
                message = it.toString(),
            )
        }
    }

    private fun performExitWarningVibration(context: Context) {
        val providerResult = runCatching {
            context.contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_VIBRATE_WARNING,
                packageName,
                null,
            )
        }.getOrNull()
        if (providerResult != null) {
            if (providerResult.getBoolean(RuleContract.KEY_OK, false)) {
                diagnostic(
                    context,
                    event = "WARNING_VIBRATION_SENT",
                    message = "模块进程已执行退出提醒震动",
                )
            } else {
                diagnostic(
                    context,
                    level = "WARN",
                    event = "WARNING_VIBRATION_REJECTED",
                    message = "模块进程拒绝或无法执行退出提醒震动",
                )
            }
            return
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    EXIT_WARNING_VIBRATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
            diagnostic(
                context,
                event = "WARNING_VIBRATION_FALLBACK",
                message = "Provider 不可用，已由目标应用进程执行震动",
            )
        }.onFailure {
            diagnostic(context, level = "WARN", event = "WARNING_VIBRATION_FAILED", message = it.toString())
        }
    }

    private fun dismissScheduleWarning() {
        dismissBanner(WarningBannerKind.SCHEDULE)
    }

    private fun dismissBanner(kind: WarningBannerKind) {
        val banner = warningBanner ?: return
        if (banner.kind != kind) return
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = null
        runCatching { banner.remove() }
        warningBanner = null
    }

    private fun isBannerShowing(kind: WarningBannerKind): Boolean {
        val banner = warningBanner ?: return false
        if (!banner.isAttached) {
            warningCountdown?.let(mainHandler::removeCallbacks)
            warningCountdown = null
            warningBanner = null
            return false
        }
        return banner.kind == kind
    }

    /**
     * App and group schedules form an intersection: the target is usable only when every enabled
     * schedule allows it. Skip intermediate boundaries that do not change the combined result so
     * "next available" remains stable when two blocked windows overlap.
     */
    private fun evaluateSchedule(rule: HookRule): ScheduleDecision {
        val constraints = buildList {
            if (rule.scheduleEnabled && rule.scheduleWindows.any(ScheduleWindow::isValid)) {
                add(ScheduleConstraint(rule.scheduleMode, rule.scheduleWindows))
            }
            if (
                rule.groupScheduleEnabled &&
                rule.groupScheduleWindows.any(ScheduleWindow::isValid)
            ) {
                add(ScheduleConstraint(rule.groupScheduleMode, rule.groupScheduleWindows))
            }
        }
        return ScheduleEvaluator.evaluateAll(constraints, ZonedDateTime.now())
    }

    private fun HookRule.hasTimedQuota(): Boolean =
        GroupRulePolicy.hasTimedQuota(
            appDailyEnabled = dailyEnabled,
            appPerLaunchEnabled = perLaunchEnabled,
            groupDailyEnabled = groupDailyEnabled,
            groupPerLaunchEnabled = groupPerLaunchEnabled,
        )

    private fun HookRule.hasSchedule(): Boolean =
        (scheduleEnabled && scheduleWindows.any(ScheduleWindow::isValid)) ||
            (groupScheduleEnabled && groupScheduleWindows.any(ScheduleWindow::isValid))

    private fun HookRule.scheduleModeForToken(): ScheduleMode =
        when {
            scheduleEnabled -> scheduleMode
            groupScheduleEnabled -> groupScheduleMode
            else -> ScheduleMode.BLOCK_DURING
        }

    private fun HookRule.scheduleTokenVersion(): Long =
        31L * version + groupVersion

    private fun formatNextTransition(
        context: Context,
        rule: HookRule,
        decision: ScheduleDecision,
    ): String? = decision.nextTransition?.format(
        if (isEnglish(context, rule)) {
            DateTimeFormatter.ofPattern("MMM d, E HH:mm", Locale.ENGLISH)
        } else {
            DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA)
        },
    )

    private fun effectiveLimitMillis(baseLimitMillis: Long): Long =
        (baseLimitMillis + grantedExtensionMs).coerceAtMost(Long.MAX_VALUE / 2L)

    private fun exitWarningMessage(
        context: Context,
        rule: HookRule,
        remainingMs: Long,
        extensionMillis: Long,
    ): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return hookText(
            context,
            rule,
            "$seconds 秒后退出 · 延时将同步延长已开启阈值 ${formatDuration(context, rule, extensionMillis)}",
            "Exit in $seconds sec · extension adds ${formatDuration(context, rule, extensionMillis)} to active limits",
        )
    }

    private fun formatDuration(context: Context, rule: HookRule, durationMs: Long): String {
        val seconds = durationMs / 1000L
        return if (isEnglish(context, rule)) {
            if (seconds < 60L) "$seconds sec" else "${seconds / 60L} min"
        } else {
            if (seconds < 60L) "$seconds 秒" else "${seconds / 60L} 分钟"
        }
    }

    private fun hookText(
        context: Context,
        rule: HookRule,
        chinese: String,
        english: String,
    ): String = if (isEnglish(context, rule)) english else chinese

    private fun isEnglish(context: Context, rule: HookRule): Boolean =
        AppLocaleController.resolvedLanguage(context, rule.languageMode) == SupportedLanguage.ENGLISH

    private fun localDailyUsedMillis(context: Context, rule: HookRule): Long {
        val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val today = dayToken()
        val savedDay = statePrefs.getInt(KEY_DAY, -1)
        val savedVersion = statePrefs.getLong(KEY_VERSION, Long.MIN_VALUE)
        if (savedDay != today || savedVersion != rule.version) {
            writeDailyState(context, rule, 0L)
            return 0L
        }
        return statePrefs.getLong(KEY_USED_MS, 0L).coerceAtLeast(0L)
    }

    private fun syncSystemDailyBaseline(context: Context, rule: HookRule) {
        if (rule.systemTodayUsedMillis < 0L) return
        val localUsedMillis = localDailyUsedMillis(context, rule)
        val activeIncludedInSnapshot = if (
            foregroundStartedAt != NOT_RUNNING &&
            rule.systemUsageMeasuredAtElapsedMillis >= foregroundStartedAt
        ) {
            UsageMath.activeIncludedAtMeasurementMillis(
                foregroundStartedAtElapsedMillis = foregroundStartedAt,
                measuredAtElapsedMillis = rule.systemUsageMeasuredAtElapsedMillis,
                activeTodayMillis = activeSegmentMillisForToday(),
            )
        } else {
            0L
        }
        val committedSystemEquivalent = UsageMath.committedSystemUsageMillis(
            rule.systemTodayUsedMillis,
            activeIncludedInSnapshot,
        )
        if (committedSystemEquivalent > localUsedMillis) {
            writeDailyState(context, rule, committedSystemEquivalent)
        }
    }

    private fun authoritativeDailyTotalMillis(
        context: Context,
        rule: HookRule,
        activeTodayMillis: Long,
    ): Long {
        val nowElapsed = SystemClock.elapsedRealtime()
        val activeAfterSystemMeasurement = if (
            rule.systemTodayUsedMillis >= 0L && foregroundStartedAt != NOT_RUNNING
        ) {
            UsageMath.activeAfterMeasurementMillis(
                nowElapsedMillis = nowElapsed,
                foregroundStartedAtElapsedMillis = foregroundStartedAt,
                measuredAtElapsedMillis = rule.systemUsageMeasuredAtElapsedMillis,
            )
        } else {
            0L
        }
        return UsageMath.authoritativeDailyUsedMillis(
            localDailyUsedMillis(context, rule) + activeTodayMillis.coerceAtLeast(0L),
            UsageMath.projectedSystemUsageMillis(
                rule.systemTodayUsedMillis,
                activeAfterSystemMeasurement,
            ),
        )
    }

    private fun activeSegmentMillisForToday(): Long {
        val activeMillis = activeSegmentMillis()
        if (activeMillis == 0L) return 0L
        val now = ZonedDateTime.now()
        val millisSinceStartOfDay = Duration.between(
            now.toLocalDate().atStartOfDay(now.zone),
            now,
        ).toMillis().coerceAtLeast(0L)
        return minOf(activeMillis, millisSinceStartOfDay)
    }

    private fun thresholdStatus(
        context: Context,
        rule: HookRule,
    ): ThresholdStatus {
        val currentGroupIdentity = rule.groupIdentity()
        if (loadedGroupIdentity != currentGroupIdentity) {
            loadedGroupIdentity = currentGroupIdentity
            grantedExtensionMs = 0L
            warningShownForExtensionMs = Long.MIN_VALUE
            warningVibratedForExtensionMs = Long.MIN_VALUE
            updateGroupSegmentBaseline(rule)
        }
        val activeMs = activeSegmentMillis()
        val thresholds = buildList {
            if (rule.dailyEnabled) {
                add(
                    ThresholdRemaining(
                        label = hookText(context, rule, "每日累计", "Daily cumulative"),
                        kind = QuotaKind.APP_DAILY,
                        isGroup = false,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.dailyLimitMillis),
                            authoritativeDailyTotalMillis(
                                context,
                                rule,
                                activeSegmentMillisForToday(),
                            ),
                            0L,
                        ),
                    ),
                )
            }
            if (rule.perLaunchEnabled) {
                add(
                    ThresholdRemaining(
                        label = hookText(context, rule, "单次打开", "Per launch"),
                        kind = QuotaKind.APP_PER_LAUNCH,
                        isGroup = false,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.perLaunchLimitMillis),
                            perLaunchCommittedMs,
                            activeMs,
                        ),
                    ),
                )
            }
            if (rule.groupDailyEnabled) {
                add(
                    ThresholdRemaining(
                        label = hookText(
                            context,
                            rule,
                            "${rule.groupName.ifBlank { "应用分组" }}共享额度",
                            "${rule.groupName.ifBlank { "App group" }} shared allowance",
                        ),
                        kind = QuotaKind.GROUP_DAILY,
                        isGroup = true,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.groupDailyLimitMillis),
                            authoritativeGroupTodayUsedMillis(rule),
                            0L,
                        ),
                    ),
                )
            }
            if (rule.groupPerLaunchEnabled) {
                add(
                    ThresholdRemaining(
                        label = hookText(
                            context,
                            rule,
                            "${rule.groupName.ifBlank { "应用分组" }}单次打开",
                            "${rule.groupName.ifBlank { "App group" }} per launch",
                        ),
                        kind = QuotaKind.GROUP_PER_LAUNCH,
                        isGroup = true,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.groupPerLaunchLimitMillis),
                            perLaunchCommittedMs,
                            activeMs,
                        ),
                    ),
                )
            }
        }
        if (thresholds.isEmpty()) {
            val disabled = hookText(context, rule, "未启用", "Disabled")
            return ThresholdStatus(
                remainingMillis = Long.MAX_VALUE / 2L,
                reached = false,
                reachedLabels = disabled,
                nextThresholdLabel = disabled,
                groupOnlyReached = false,
                reachedKinds = emptySet(),
                hasThreshold = false,
            )
        }
        val earliestRemaining = UsageMath.earliestRemainingMillis(
            thresholds.map { it.remainingMillis },
        ) ?: Long.MAX_VALUE / 2L
        val earliest = thresholds.first { it.remainingMillis == earliestRemaining }
        val reached = thresholds.filter { it.remainingMillis == 0L }
        val reachedLabels = reached.joinToString("、") { it.label }
        return ThresholdStatus(
            remainingMillis = earliest.remainingMillis,
            reached = earliest.remainingMillis == 0L,
            reachedLabels = reachedLabels,
            nextThresholdLabel = earliest.label,
            groupOnlyReached = reached.isNotEmpty() && reached.all(ThresholdRemaining::isGroup),
            reachedKinds = reached.mapTo(linkedSetOf(), ThresholdRemaining::kind),
            hasThreshold = true,
        )
    }

    private fun authoritativeGroupTodayUsedMillis(rule: HookRule): Long {
        if (!rule.groupDailyEnabled) return 0L
        val today = LocalDate.now().toString()
        val providerBase = rule.groupTodayUsedMillis
            .takeIf { rule.groupDayToken == today }
            ?.coerceAtLeast(0L)
            ?: 0L
        val nowElapsed = SystemClock.elapsedRealtime()
        val providerAdvanceStart = maxOf(
            rule.groupMeasuredAtElapsedMillis,
            foregroundStartedAt.takeIf { it != NOT_RUNNING } ?: nowElapsed,
        )
        val providerEstimate = safeAdd(
            providerBase,
            (nowElapsed - providerAdvanceStart).coerceAtLeast(0L),
        )
        val segmentEstimate = if (
            groupSegmentIdentity == rule.groupIdentity() &&
            groupSegmentBaselineDay == today
        ) {
            safeAdd(groupSegmentBaselineUsedMs, activeSegmentMillisForToday())
        } else {
            providerEstimate
        }
        return maxOf(providerEstimate, segmentEstimate)
    }

    private fun updateGroupSegmentBaseline(rule: HookRule) {
        groupSegmentIdentity = rule.groupIdentity()
        groupSegmentBaselineDay = rule.groupDayToken
        groupSegmentBaselineUsedMs = rule.groupTodayUsedMillis.coerceAtLeast(0L)
    }

    private fun HookRule.groupIdentity(): String = "$groupId/$groupVersion"

    private fun usageSummary(context: Context, rule: HookRule): String = buildList {
        if (rule.dailyEnabled) {
            add(
                "每日累计=${authoritativeDailyTotalMillis(context, rule, activeSegmentMillisForToday()) / 1000.0}s",
            )
        }
        if (rule.perLaunchEnabled || rule.groupPerLaunchEnabled) {
            add("单次累计=${perLaunchCommittedMs / 1000.0}s")
        }
        if (rule.groupDailyEnabled) {
            add("${rule.groupName}共享=${authoritativeGroupTodayUsedMillis(rule) / 1000.0}s")
        }
    }.joinToString("，")

    private fun writeDailyState(context: Context, rule: HookRule, usedMillis: Long) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DAY, dayToken())
            .putLong(KEY_VERSION, rule.version)
            .putLong(KEY_USED_MS, usedMillis.coerceAtLeast(0L))
            .commit()
    }

    private fun readRule(context: Context, reloadFallback: Boolean): HookRule {
        runCatching {
            context.contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_GET_RULE,
                packageName,
                null,
            )
        }.getOrNull()?.takeIf { it.getBoolean(RuleContract.KEY_OK, false) }?.let { result ->
            val rule = HookRule(
                enabled = result.getBoolean(RuleContract.KEY_ENABLED, false),
                sessionPlanningEnabled = result.getBoolean(
                    RuleContract.KEY_SESSION_PLANNING_ENABLED,
                    false,
                ),
                dailyEnabled = result.getBoolean(RuleContract.KEY_DAILY_ENABLED, false),
                dailyLimitMillis = result.getLong(
                    RuleContract.KEY_DAILY_LIMIT_SECONDS,
                    RuleRepository.DEFAULT_LIMIT_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
                systemTodayUsedMillis = result.getLong(
                    RuleContract.KEY_SYSTEM_TODAY_USED_MS,
                    -1L,
                ),
                systemUsageMeasuredAtElapsedMillis = result.getLong(
                    RuleContract.KEY_SYSTEM_USAGE_MEASURED_AT_ELAPSED_MS,
                    SystemClock.elapsedRealtime(),
                ),
                systemUsagePending = result.getBoolean(
                    RuleContract.KEY_SYSTEM_USAGE_PENDING,
                    false,
                ),
                groupEnabled = result.getBoolean(RuleContract.KEY_GROUP_ENABLED, false),
                groupId = result.getString(RuleContract.KEY_GROUP_ID).orEmpty(),
                groupName = result.getString(RuleContract.KEY_GROUP_NAME).orEmpty(),
                groupDailyEnabled = result.getBoolean(
                    RuleContract.KEY_GROUP_DAILY_ENABLED,
                    // Provider versions before group multi-rule support only exposed a daily quota.
                    result.getBoolean(RuleContract.KEY_GROUP_ENABLED, false),
                ),
                groupDailyLimitMillis = result.getLong(
                    RuleContract.KEY_GROUP_DAILY_LIMIT_SECONDS,
                    RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
                groupTodayUsedMillis = result.getLong(
                    RuleContract.KEY_GROUP_TODAY_USED_MS,
                    -1L,
                ),
                groupVersion = result.getLong(RuleContract.KEY_GROUP_VERSION, 0L),
                groupDayToken = result.getString(RuleContract.KEY_GROUP_DAY_TOKEN).orEmpty(),
                groupMeasuredAtElapsedMillis = result.getLong(
                    RuleContract.KEY_GROUP_MEASURED_AT_ELAPSED_MS,
                    SystemClock.elapsedRealtime(),
                ),
                groupPerLaunchEnabled = result.getBoolean(
                    RuleContract.KEY_GROUP_PER_LAUNCH_ENABLED,
                    false,
                ),
                groupPerLaunchLimitMillis = result.getLong(
                    RuleContract.KEY_GROUP_PER_LAUNCH_LIMIT_SECONDS,
                    RuleRepository.DEFAULT_LIMIT_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
                groupScheduleEnabled = result.getBoolean(
                    RuleContract.KEY_GROUP_SCHEDULE_ENABLED,
                    false,
                ),
                groupScheduleMode = result.getString(RuleContract.KEY_GROUP_SCHEDULE_MODE)
                    ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                    ?: ScheduleMode.BLOCK_DURING,
                groupScheduleWindows = ScheduleCodec.decode(
                    result.getString(RuleContract.KEY_GROUP_SCHEDULE_WINDOWS),
                ),
                groupCooldownEnabled = result.getBoolean(
                    RuleContract.KEY_GROUP_COOLDOWN_ENABLED,
                    false,
                ),
                groupCooldownMillis = result.getLong(
                    RuleContract.KEY_GROUP_COOLDOWN_SECONDS,
                    RuleRepository.DEFAULT_COOLDOWN_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_COOLDOWN_SECONDS,
                    RuleRepository.MAX_COOLDOWN_SECONDS,
                ) * 1000L,
                groupCooldownStartedAtMillis = result.getLong(
                    RuleContract.KEY_GROUP_COOLDOWN_STARTED_AT_MS,
                    0L,
                ),
                groupCooldownEndsAtMillis = result.getLong(
                    RuleContract.KEY_GROUP_COOLDOWN_ENDS_AT_MS,
                    0L,
                ),
                groupCooldownIncidentId = result.getString(
                    RuleContract.KEY_GROUP_COOLDOWN_INCIDENT_ID,
                ).orEmpty(),
                groupCooldownSourcePackage = result.getString(
                    RuleContract.KEY_GROUP_COOLDOWN_SOURCE_PACKAGE,
                ).orEmpty(),
                perLaunchEnabled = result.getBoolean(RuleContract.KEY_PER_LAUNCH_ENABLED, false),
                perLaunchLimitMillis = result.getLong(
                    RuleContract.KEY_PER_LAUNCH_LIMIT_SECONDS,
                    RuleRepository.DEFAULT_LIMIT_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
                scheduleEnabled = result.getBoolean(RuleContract.KEY_SCHEDULE_ENABLED, false),
                scheduleMode = result.getString(RuleContract.KEY_SCHEDULE_MODE)
                    ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                    ?: ScheduleMode.BLOCK_DURING,
                scheduleWindows = ScheduleCodec.decode(
                    result.getString(RuleContract.KEY_SCHEDULE_WINDOWS),
                ),
                cooldownEnabled = result.getBoolean(RuleContract.KEY_COOLDOWN_ENABLED, false),
                cooldownMillis = result.getLong(
                    RuleContract.KEY_COOLDOWN_SECONDS,
                    RuleRepository.DEFAULT_COOLDOWN_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_COOLDOWN_SECONDS,
                    RuleRepository.MAX_COOLDOWN_SECONDS,
                ) * 1000L,
                version = result.getLong(RuleContract.KEY_VERSION, 0L),
                exitWarningEnabled = result.getBoolean(RuleContract.KEY_EXIT_WARNING_ENABLED, true),
                fullScreenExitWarningEnabled = result.getBoolean(
                    RuleContract.KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
                    false,
                ),
                exitWarningVibrationEnabled = result.getBoolean(
                    RuleContract.KEY_EXIT_WARNING_VIBRATION_ENABLED,
                    false,
                ),
                languageMode = result.getString(RuleContract.KEY_LANGUAGE_MODE)
                    ?.let { runCatching { AppLanguageMode.valueOf(it) }.getOrNull() }
                    ?: AppLanguageMode.SYSTEM,
                extensionMillis = result.getLong(
                    RuleContract.KEY_EXTENSION_SECONDS,
                    RuleRepository.DEFAULT_EXTENSION_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_EXTENSION_SECONDS,
                    RuleRepository.MAX_EXTENSION_SECONDS,
                ) * 1000L,
                limitEnforcementMode = LimitEnforcementPolicy.parseMode(
                    result.getString(RuleContract.KEY_LIMIT_ENFORCEMENT_MODE),
                ),
                diagnosticsEnabled = result.getBoolean(RuleContract.KEY_DIAGNOSTICS_ENABLED, true),
                usageStatsEnabled = result.getBoolean(RuleContract.KEY_USAGE_STATS_ENABLED, true),
                source = "provider",
            )
            diagnosticsEnabled = rule.diagnosticsEnabled
            cacheRule(context, rule)
            lastLoadedRule = rule
            return rule
        }

        if (!providerFailureLogged) {
            providerFailureLogged = true
            XposedBridge.log("AppTimeLimiter: PROVIDER_UNAVAILABLE package=$packageName, fallback=XSharedPreferences/local_cache")
        }
        if (reloadFallback) preferences.reload()
        val rawSharedRule = readXSharedRule()
        val cachedRule = readCachedRule(context)
        val sharedRule = if (
            rawSharedRule?.groupEnabled == true &&
            cachedRule?.groupEnabled == true &&
            rawSharedRule.groupIdentity() == cachedRule.groupIdentity()
        ) {
            rawSharedRule.copy(
                groupTodayUsedMillis = cachedRule.groupTodayUsedMillis,
                groupDayToken = cachedRule.groupDayToken,
                groupMeasuredAtElapsedMillis = cachedRule.groupMeasuredAtElapsedMillis,
                groupCooldownStartedAtMillis = cachedRule.groupCooldownStartedAtMillis,
                groupCooldownEndsAtMillis = cachedRule.groupCooldownEndsAtMillis,
                groupCooldownIncidentId = cachedRule.groupCooldownIncidentId,
                groupCooldownSourcePackage = cachedRule.groupCooldownSourcePackage,
            )
        } else {
            rawSharedRule
        }
        val rule = when {
            sharedRule != null && (cachedRule == null || sharedRule.version >= cachedRule.version) -> {
                cacheRule(context, sharedRule)
                sharedRule
            }
            cachedRule != null -> cachedRule
            else -> unavailableRule()
        }
        diagnosticsEnabled = rule.diagnosticsEnabled
        lastLoadedRule = rule
        return rule
    }

    private fun readXSharedRule(): HookRule? {
        val prefix = "rule.$packageName."
        val storedVersion = preferences.getLong("${prefix}version", Long.MIN_VALUE)
        val membershipVersion = preferences.getLong(
            "${RuleRepository.KEY_PACKAGE_GROUP_VERSION_PREFIX}$packageName",
            Long.MIN_VALUE,
        )
        val groupId = preferences.getString(
            "${RuleRepository.KEY_PACKAGE_GROUP_PREFIX}$packageName",
            "",
        ).orEmpty()
        val groupPrefix = "group.$groupId."
        val groupMembers = if (groupId.isBlank()) {
            emptySet()
        } else {
            preferences.getStringSet("${groupPrefix}packages", emptySet()).orEmpty()
        }
        val groupAssigned = groupId.isNotBlank() && packageName in groupMembers
        val groupEnabled = groupAssigned &&
            preferences.getBoolean("${groupPrefix}enabled", false)
        val groupDailyEnabled = groupEnabled &&
            preferences.getBoolean("${groupPrefix}daily_enabled", true)
        if (storedVersion == Long.MIN_VALUE && membershipVersion == Long.MIN_VALUE && !groupAssigned) {
            return null
        }
        val version = storedVersion.takeIf { it != Long.MIN_VALUE } ?: 0L
        val legacyEnabled = preferences.getBoolean("${prefix}enabled", false)
        val legacyLimitSeconds = preferences.getLong(
            "${prefix}limit_seconds",
            RuleRepository.DEFAULT_LIMIT_SECONDS,
        )
        val legacyDaily = preferences.getString("${prefix}mode", "DAILY") == "DAILY"
        val dailyLimitSeconds = preferences.getLong("${prefix}daily_limit_seconds", -1L)
        val perLaunchLimitSeconds = preferences.getLong("${prefix}per_launch_limit_seconds", -1L)
        val hasDualThresholdRule = dailyLimitSeconds >= 0L || perLaunchLimitSeconds >= 0L
        val rawDailyEnabled = if (hasDualThresholdRule) {
            preferences.getBoolean("${prefix}daily_enabled", false)
        } else {
            legacyEnabled && legacyDaily
        }
        val rawPerLaunchEnabled = if (hasDualThresholdRule) {
            preferences.getBoolean("${prefix}per_launch_enabled", false)
        } else {
            legacyEnabled && !legacyDaily
        }
        val rawScheduleEnabled = preferences.getBoolean("${prefix}schedule_enabled", false)
        val dailyEnabled = !groupAssigned && rawDailyEnabled
        val perLaunchEnabled = !groupAssigned && rawPerLaunchEnabled
        val scheduleEnabled = !groupAssigned && rawScheduleEnabled
        val groupPerLaunchEnabled = groupEnabled &&
            preferences.getBoolean("${groupPrefix}per_launch_enabled", false)
        return HookRule(
            enabled = if (groupAssigned) {
                groupEnabled
            } else {
                legacyEnabled && (dailyEnabled || perLaunchEnabled || scheduleEnabled)
            },
            sessionPlanningEnabled = !groupAssigned && preferences.getBoolean(
                "${prefix}session_planning_enabled",
                false,
            ),
            dailyEnabled = dailyEnabled,
            dailyLimitMillis = (if (dailyLimitSeconds >= 0L) dailyLimitSeconds else legacyLimitSeconds)
                .coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
            systemTodayUsedMillis = -1L,
            systemUsageMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
            systemUsagePending = false,
            groupEnabled = groupEnabled,
            groupId = groupId,
            groupName = preferences.getString("${groupPrefix}name", "").orEmpty(),
            groupDailyEnabled = groupDailyEnabled,
            groupDailyLimitMillis = preferences.getLong(
                "${groupPrefix}daily_limit_seconds",
                RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS,
                RuleRepository.MAX_LIMIT_SECONDS,
            ) * 1000L,
            groupTodayUsedMillis = -1L,
            groupVersion = if (groupEnabled) {
                preferences.getLong("${groupPrefix}version", membershipVersion.coerceAtLeast(0L))
            } else {
                membershipVersion.coerceAtLeast(0L)
            },
            groupDayToken = LocalDate.now().toString(),
            groupMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
            groupPerLaunchEnabled = groupPerLaunchEnabled,
            groupPerLaunchLimitMillis = preferences.getLong(
                "${groupPrefix}per_launch_limit_seconds",
                RuleRepository.DEFAULT_LIMIT_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS,
                RuleRepository.MAX_LIMIT_SECONDS,
            ) * 1000L,
            groupScheduleEnabled = groupEnabled &&
                preferences.getBoolean("${groupPrefix}schedule_enabled", false),
            groupScheduleMode = preferences.getString(
                "${groupPrefix}schedule_mode",
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            groupScheduleWindows = ScheduleCodec.decode(
                preferences.getString("${groupPrefix}schedule_windows", null),
            ),
            groupCooldownEnabled = groupEnabled &&
                (groupDailyEnabled || groupPerLaunchEnabled) &&
                preferences.getBoolean("${groupPrefix}cooldown_enabled", false),
            groupCooldownMillis = preferences.getLong(
                "${groupPrefix}cooldown_seconds",
                RuleRepository.DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_COOLDOWN_SECONDS,
                RuleRepository.MAX_COOLDOWN_SECONDS,
            ) * 1000L,
            groupCooldownStartedAtMillis = preferences.getLong(
                "${groupPrefix}runtime_cooldown_started_at",
                0L,
            ),
            groupCooldownEndsAtMillis = preferences.getLong(
                "${groupPrefix}runtime_cooldown_ends_at",
                0L,
            ),
            groupCooldownIncidentId = preferences.getString(
                "${groupPrefix}runtime_cooldown_incident",
                "",
            ).orEmpty(),
            groupCooldownSourcePackage = preferences.getString(
                "${groupPrefix}runtime_cooldown_source_package",
                "",
            ).orEmpty(),
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitMillis = (if (perLaunchLimitSeconds >= 0L) {
                perLaunchLimitSeconds
            } else {
                legacyLimitSeconds
            }).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS,
                RuleRepository.MAX_LIMIT_SECONDS,
            ) * 1000L,
            scheduleEnabled = scheduleEnabled,
            scheduleMode = preferences.getString(
                "${prefix}schedule_mode",
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(
                preferences.getString("${prefix}schedule_windows", null),
            ),
            cooldownEnabled = !groupAssigned &&
                (dailyEnabled || perLaunchEnabled) &&
                preferences.getBoolean("${prefix}cooldown_enabled", false),
            cooldownMillis = preferences.getLong(
                "${prefix}cooldown_seconds",
                RuleRepository.DEFAULT_COOLDOWN_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_COOLDOWN_SECONDS,
                RuleRepository.MAX_COOLDOWN_SECONDS,
            ) * 1000L,
            version = version,
            exitWarningEnabled = preferences.getBoolean(RuleRepository.KEY_EXIT_WARNING_ENABLED, true),
            fullScreenExitWarningEnabled = preferences.getBoolean(
                RuleRepository.KEY_FULL_SCREEN_EXIT_WARNING_ENABLED,
                false,
            ),
            exitWarningVibrationEnabled = preferences.getBoolean(
                RuleRepository.KEY_EXIT_WARNING_VIBRATION_ENABLED,
                false,
            ),
            languageMode = preferences.getString(
                RuleRepository.KEY_LANGUAGE_MODE,
                AppLanguageMode.SYSTEM.name,
            )?.let { runCatching { AppLanguageMode.valueOf(it) }.getOrNull() }
                ?: AppLanguageMode.SYSTEM,
            extensionMillis = preferences.getLong(
                RuleRepository.KEY_EXTENSION_SECONDS,
                RuleRepository.DEFAULT_EXTENSION_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_EXTENSION_SECONDS,
                RuleRepository.MAX_EXTENSION_SECONDS,
            ) * 1000L,
            limitEnforcementMode = LimitEnforcementPolicy.parseMode(
                preferences.getString(RuleRepository.KEY_LIMIT_ENFORCEMENT_MODE, null),
            ),
            diagnosticsEnabled = preferences.getBoolean(RuleRepository.KEY_DIAGNOSTICS_ENABLED, true),
            usageStatsEnabled = preferences.getBoolean(RuleRepository.KEY_USAGE_STATS_ENABLED, true),
            source = "xsharedpreferences",
        )
    }

    private fun cacheRule(context: Context, rule: HookRule) {
        val signature = listOf(
            rule.enabled,
            rule.sessionPlanningEnabled,
            rule.dailyEnabled,
            rule.dailyLimitMillis,
            rule.groupEnabled,
            rule.groupId,
            rule.groupName,
            rule.groupDailyEnabled,
            rule.groupDailyLimitMillis,
            rule.groupTodayUsedMillis,
            rule.groupVersion,
            rule.groupDayToken,
            rule.groupMeasuredAtElapsedMillis,
            rule.groupPerLaunchEnabled,
            rule.groupPerLaunchLimitMillis,
            rule.groupScheduleEnabled,
            rule.groupScheduleMode.name,
            ScheduleCodec.encode(rule.groupScheduleWindows),
            rule.groupCooldownEnabled,
            rule.groupCooldownMillis,
            rule.groupCooldownStartedAtMillis,
            rule.groupCooldownEndsAtMillis,
            rule.groupCooldownIncidentId,
            rule.groupCooldownSourcePackage,
            rule.perLaunchEnabled,
            rule.perLaunchLimitMillis,
            rule.scheduleEnabled,
            rule.scheduleMode.name,
            ScheduleCodec.encode(rule.scheduleWindows),
            rule.cooldownEnabled,
            rule.cooldownMillis,
            rule.version,
            rule.exitWarningEnabled,
            rule.fullScreenExitWarningEnabled,
            rule.exitWarningVibrationEnabled,
            rule.languageMode.name,
            rule.extensionMillis,
            rule.limitEnforcementMode.name,
            rule.diagnosticsEnabled,
            rule.usageStatsEnabled,
        ).joinToString("|")
        val prefs = context.getSharedPreferences(RULE_CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(CACHE_SIGNATURE, null) == signature) return
        prefs.edit()
            .putBoolean(CACHE_PRESENT, true)
            .putBoolean(CACHE_ENABLED, rule.enabled)
            .putBoolean(CACHE_SESSION_PLANNING_ENABLED, rule.sessionPlanningEnabled)
            .putBoolean(CACHE_DAILY_ENABLED, rule.dailyEnabled)
            .putLong(CACHE_DAILY_LIMIT_MS, rule.dailyLimitMillis)
            .putBoolean(CACHE_GROUP_ENABLED, rule.groupEnabled)
            .putString(CACHE_GROUP_ID, rule.groupId)
            .putString(CACHE_GROUP_NAME, rule.groupName)
            .putBoolean(CACHE_GROUP_DAILY_ENABLED, rule.groupDailyEnabled)
            .putLong(CACHE_GROUP_DAILY_LIMIT_MS, rule.groupDailyLimitMillis)
            .putLong(CACHE_GROUP_TODAY_USED_MS, rule.groupTodayUsedMillis)
            .putLong(CACHE_GROUP_VERSION, rule.groupVersion)
            .putString(CACHE_GROUP_DAY_TOKEN, rule.groupDayToken)
            .putLong(CACHE_GROUP_MEASURED_AT_ELAPSED_MS, rule.groupMeasuredAtElapsedMillis)
            .putBoolean(CACHE_GROUP_PER_LAUNCH_ENABLED, rule.groupPerLaunchEnabled)
            .putLong(CACHE_GROUP_PER_LAUNCH_LIMIT_MS, rule.groupPerLaunchLimitMillis)
            .putBoolean(CACHE_GROUP_SCHEDULE_ENABLED, rule.groupScheduleEnabled)
            .putString(CACHE_GROUP_SCHEDULE_MODE, rule.groupScheduleMode.name)
            .putString(
                CACHE_GROUP_SCHEDULE_WINDOWS,
                ScheduleCodec.encode(rule.groupScheduleWindows),
            )
            .putBoolean(CACHE_GROUP_COOLDOWN_ENABLED, rule.groupCooldownEnabled)
            .putLong(CACHE_GROUP_COOLDOWN_MS, rule.groupCooldownMillis)
            .putLong(
                CACHE_GROUP_COOLDOWN_STARTED_AT_MS,
                rule.groupCooldownStartedAtMillis,
            )
            .putLong(CACHE_GROUP_COOLDOWN_ENDS_AT_MS, rule.groupCooldownEndsAtMillis)
            .putString(CACHE_GROUP_COOLDOWN_INCIDENT_ID, rule.groupCooldownIncidentId)
            .putString(
                CACHE_GROUP_COOLDOWN_SOURCE_PACKAGE,
                rule.groupCooldownSourcePackage,
            )
            .putBoolean(CACHE_PER_LAUNCH_ENABLED, rule.perLaunchEnabled)
            .putLong(CACHE_PER_LAUNCH_LIMIT_MS, rule.perLaunchLimitMillis)
            .putBoolean(CACHE_SCHEDULE_ENABLED, rule.scheduleEnabled)
            .putString(CACHE_SCHEDULE_MODE, rule.scheduleMode.name)
            .putString(CACHE_SCHEDULE_WINDOWS, ScheduleCodec.encode(rule.scheduleWindows))
            .putBoolean(CACHE_COOLDOWN_ENABLED, rule.cooldownEnabled)
            .putLong(CACHE_COOLDOWN_MS, rule.cooldownMillis)
            .putLong(CACHE_RULE_VERSION, rule.version)
            .putBoolean(CACHE_EXIT_WARNING_ENABLED, rule.exitWarningEnabled)
            .putBoolean(
                CACHE_FULL_SCREEN_EXIT_WARNING_ENABLED,
                rule.fullScreenExitWarningEnabled,
            )
            .putBoolean(
                CACHE_EXIT_WARNING_VIBRATION_ENABLED,
                rule.exitWarningVibrationEnabled,
            )
            .putString(CACHE_LANGUAGE_MODE, rule.languageMode.name)
            .putLong(CACHE_EXTENSION_MS, rule.extensionMillis)
            .putString(CACHE_LIMIT_ENFORCEMENT_MODE, rule.limitEnforcementMode.name)
            .putBoolean(CACHE_DIAGNOSTICS_ENABLED, rule.diagnosticsEnabled)
            .putBoolean(CACHE_USAGE_STATS_ENABLED, rule.usageStatsEnabled)
            .putString(CACHE_SIGNATURE, signature)
            .commit()
    }

    private fun readCachedRule(context: Context): HookRule? {
        val prefs = context.getSharedPreferences(RULE_CACHE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CACHE_PRESENT, false)) return null
        val groupEnabled = prefs.getBoolean(CACHE_GROUP_ENABLED, false)
        val groupAssigned = prefs.getString(CACHE_GROUP_ID, "").orEmpty().isNotBlank()
        val dailyEnabled = !groupAssigned && prefs.getBoolean(CACHE_DAILY_ENABLED, false)
        val perLaunchEnabled = !groupAssigned &&
            prefs.getBoolean(CACHE_PER_LAUNCH_ENABLED, false)
        val groupDailyEnabled = prefs.getBoolean(
            CACHE_GROUP_DAILY_ENABLED,
            groupEnabled,
        )
        val groupPerLaunchEnabled = prefs.getBoolean(
            CACHE_GROUP_PER_LAUNCH_ENABLED,
            false,
        )
        return HookRule(
            enabled = prefs.getBoolean(CACHE_ENABLED, false),
            sessionPlanningEnabled = !groupAssigned &&
                prefs.getBoolean(CACHE_SESSION_PLANNING_ENABLED, false),
            dailyEnabled = dailyEnabled,
            dailyLimitMillis = prefs.getLong(
                CACHE_DAILY_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            systemTodayUsedMillis = -1L,
            systemUsageMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
            systemUsagePending = false,
            groupEnabled = groupEnabled,
            groupId = prefs.getString(CACHE_GROUP_ID, "").orEmpty(),
            groupName = prefs.getString(CACHE_GROUP_NAME, "").orEmpty(),
            groupDailyEnabled = groupDailyEnabled,
            groupDailyLimitMillis = prefs.getLong(
                CACHE_GROUP_DAILY_LIMIT_MS,
                RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            groupTodayUsedMillis = prefs.getLong(CACHE_GROUP_TODAY_USED_MS, -1L),
            groupVersion = prefs.getLong(CACHE_GROUP_VERSION, 0L),
            groupDayToken = prefs.getString(CACHE_GROUP_DAY_TOKEN, "").orEmpty(),
            groupMeasuredAtElapsedMillis = prefs.getLong(
                CACHE_GROUP_MEASURED_AT_ELAPSED_MS,
                SystemClock.elapsedRealtime(),
            ),
            groupPerLaunchEnabled = groupPerLaunchEnabled,
            groupPerLaunchLimitMillis = prefs.getLong(
                CACHE_GROUP_PER_LAUNCH_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            groupScheduleEnabled = prefs.getBoolean(CACHE_GROUP_SCHEDULE_ENABLED, false),
            groupScheduleMode = prefs.getString(
                CACHE_GROUP_SCHEDULE_MODE,
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            groupScheduleWindows = ScheduleCodec.decode(
                prefs.getString(CACHE_GROUP_SCHEDULE_WINDOWS, null),
            ),
            groupCooldownEnabled = (groupDailyEnabled || groupPerLaunchEnabled) &&
                prefs.getBoolean(CACHE_GROUP_COOLDOWN_ENABLED, false),
            groupCooldownMillis = prefs.getLong(
                CACHE_GROUP_COOLDOWN_MS,
                RuleRepository.DEFAULT_COOLDOWN_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_COOLDOWN_SECONDS * 1000L,
                RuleRepository.MAX_COOLDOWN_SECONDS * 1000L,
            ),
            groupCooldownStartedAtMillis = prefs.getLong(
                CACHE_GROUP_COOLDOWN_STARTED_AT_MS,
                0L,
            ),
            groupCooldownEndsAtMillis = prefs.getLong(
                CACHE_GROUP_COOLDOWN_ENDS_AT_MS,
                0L,
            ),
            groupCooldownIncidentId = prefs.getString(
                CACHE_GROUP_COOLDOWN_INCIDENT_ID,
                "",
            ).orEmpty(),
            groupCooldownSourcePackage = prefs.getString(
                CACHE_GROUP_COOLDOWN_SOURCE_PACKAGE,
                "",
            ).orEmpty(),
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitMillis = prefs.getLong(
                CACHE_PER_LAUNCH_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            scheduleEnabled = !groupAssigned && prefs.getBoolean(CACHE_SCHEDULE_ENABLED, false),
            scheduleMode = prefs.getString(CACHE_SCHEDULE_MODE, ScheduleMode.BLOCK_DURING.name)
                ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(prefs.getString(CACHE_SCHEDULE_WINDOWS, null)),
            cooldownEnabled = !groupAssigned &&
                (dailyEnabled || perLaunchEnabled) &&
                prefs.getBoolean(CACHE_COOLDOWN_ENABLED, false),
            cooldownMillis = prefs.getLong(
                CACHE_COOLDOWN_MS,
                RuleRepository.DEFAULT_COOLDOWN_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_COOLDOWN_SECONDS * 1000L,
                RuleRepository.MAX_COOLDOWN_SECONDS * 1000L,
            ),
            version = prefs.getLong(CACHE_RULE_VERSION, 0L),
            exitWarningEnabled = prefs.getBoolean(CACHE_EXIT_WARNING_ENABLED, true),
            fullScreenExitWarningEnabled = prefs.getBoolean(
                CACHE_FULL_SCREEN_EXIT_WARNING_ENABLED,
                false,
            ),
            exitWarningVibrationEnabled = prefs.getBoolean(
                CACHE_EXIT_WARNING_VIBRATION_ENABLED,
                false,
            ),
            languageMode = prefs.getString(CACHE_LANGUAGE_MODE, AppLanguageMode.SYSTEM.name)
                ?.let { runCatching { AppLanguageMode.valueOf(it) }.getOrNull() }
                ?: AppLanguageMode.SYSTEM,
            extensionMillis = prefs.getLong(
                CACHE_EXTENSION_MS,
                RuleRepository.DEFAULT_EXTENSION_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_EXTENSION_SECONDS * 1000L,
                RuleRepository.MAX_EXTENSION_SECONDS * 1000L,
            ),
            limitEnforcementMode = LimitEnforcementPolicy.parseMode(
                prefs.getString(CACHE_LIMIT_ENFORCEMENT_MODE, null),
            ),
            diagnosticsEnabled = prefs.getBoolean(CACHE_DIAGNOSTICS_ENABLED, true),
            usageStatsEnabled = prefs.getBoolean(CACHE_USAGE_STATS_ENABLED, true),
            source = "local_cache",
        )
    }

    private fun unavailableRule() = HookRule(
        enabled = false,
        sessionPlanningEnabled = false,
        dailyEnabled = false,
        dailyLimitMillis = RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
        systemTodayUsedMillis = -1L,
        systemUsageMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
        systemUsagePending = false,
        groupEnabled = false,
        groupId = "",
        groupName = "",
        groupDailyEnabled = false,
        groupDailyLimitMillis = RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS * 1000L,
        groupTodayUsedMillis = -1L,
        groupVersion = 0L,
        groupDayToken = "",
        groupMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
        groupPerLaunchEnabled = false,
        groupPerLaunchLimitMillis = RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
        groupScheduleEnabled = false,
        groupScheduleMode = ScheduleMode.BLOCK_DURING,
        groupScheduleWindows = emptyList(),
        groupCooldownEnabled = false,
        groupCooldownMillis = RuleRepository.DEFAULT_COOLDOWN_SECONDS * 1000L,
        groupCooldownStartedAtMillis = 0L,
        groupCooldownEndsAtMillis = 0L,
        groupCooldownIncidentId = "",
        groupCooldownSourcePackage = "",
        perLaunchEnabled = false,
        perLaunchLimitMillis = RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
        scheduleEnabled = false,
        scheduleMode = ScheduleMode.BLOCK_DURING,
        scheduleWindows = emptyList(),
        cooldownEnabled = false,
        cooldownMillis = RuleRepository.DEFAULT_COOLDOWN_SECONDS * 1000L,
        version = Long.MIN_VALUE,
        exitWarningEnabled = true,
        fullScreenExitWarningEnabled = false,
        exitWarningVibrationEnabled = false,
        languageMode = AppLanguageMode.SYSTEM,
        extensionMillis = RuleRepository.DEFAULT_EXTENSION_SECONDS * 1000L,
        limitEnforcementMode = LimitEnforcementMode.FORCE_EXIT,
        diagnosticsEnabled = true,
        usageStatsEnabled = true,
        source = "unavailable",
    )

    private fun diagnostic(
        context: Context,
        level: String = "INFO",
        event: String,
        message: String,
    ) {
        if (!diagnosticsEnabled) return
        XposedBridge.log("AppTimeLimiter: $event package=$packageName process=$processName $message")
        val extras = Bundle().apply {
            putString(RuleContract.KEY_LEVEL, level)
            putString(RuleContract.KEY_EVENT, event)
            putString(RuleContract.KEY_MESSAGE, message)
        }
        runCatching {
            context.contentResolver.call(
                RuleContract.CONTENT_URI,
                RuleContract.METHOD_APPEND_LOG,
                packageName,
                extras,
            )
        }
    }

    private fun activeSegmentMillis(): Long =
        if (foregroundStartedAt == NOT_RUNNING) 0L
        else (SystemClock.elapsedRealtime() - foregroundStartedAt).coerceAtLeast(0L)

    private fun dayToken(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private data class HookRule(
        val enabled: Boolean,
        val sessionPlanningEnabled: Boolean,
        val dailyEnabled: Boolean,
        val dailyLimitMillis: Long,
        val systemTodayUsedMillis: Long,
        val systemUsageMeasuredAtElapsedMillis: Long,
        val systemUsagePending: Boolean,
        val groupEnabled: Boolean,
        val groupId: String,
        val groupName: String,
        val groupDailyEnabled: Boolean,
        val groupDailyLimitMillis: Long,
        val groupTodayUsedMillis: Long,
        val groupVersion: Long,
        val groupDayToken: String,
        val groupMeasuredAtElapsedMillis: Long,
        val groupPerLaunchEnabled: Boolean,
        val groupPerLaunchLimitMillis: Long,
        val groupScheduleEnabled: Boolean,
        val groupScheduleMode: ScheduleMode,
        val groupScheduleWindows: List<ScheduleWindow>,
        val groupCooldownEnabled: Boolean,
        val groupCooldownMillis: Long,
        val groupCooldownStartedAtMillis: Long,
        val groupCooldownEndsAtMillis: Long,
        val groupCooldownIncidentId: String,
        val groupCooldownSourcePackage: String,
        val perLaunchEnabled: Boolean,
        val perLaunchLimitMillis: Long,
        val scheduleEnabled: Boolean,
        val scheduleMode: ScheduleMode,
        val scheduleWindows: List<ScheduleWindow>,
        val cooldownEnabled: Boolean,
        val cooldownMillis: Long,
        val version: Long,
        val exitWarningEnabled: Boolean,
        val fullScreenExitWarningEnabled: Boolean,
        val exitWarningVibrationEnabled: Boolean,
        val languageMode: AppLanguageMode,
        val extensionMillis: Long,
        val limitEnforcementMode: LimitEnforcementMode,
        val diagnosticsEnabled: Boolean,
        val usageStatsEnabled: Boolean,
        val source: String,
    )

    private data class ThresholdRemaining(
        val label: String,
        val kind: QuotaKind,
        val isGroup: Boolean,
        val remainingMillis: Long,
    )

    private data class ThresholdStatus(
        val remainingMillis: Long,
        val reached: Boolean,
        val reachedLabels: String,
        val nextThresholdLabel: String,
        val groupOnlyReached: Boolean,
        val reachedKinds: Set<QuotaKind>,
        val hasThreshold: Boolean,
    )

    private data class PendingUsageBatch(
        var durationMillis: Long = 0L,
        var launches: Int = 0,
        var limitHits: Int = 0,
    )

    private data class ScheduleHitResult(
        val recorded: Boolean,
        val statsPersisted: Boolean,
    )

    private data class BlockingState(
        val reason: LimitBlockReason,
        val token: String,
        val title: String,
        val message: String,
        val ruleVersion: Long = Long.MIN_VALUE,
        val groupVersion: Long = Long.MIN_VALUE,
        val cooldownEndsAtMillis: Long = 0L,
        val reachedKinds: Set<QuotaKind> = emptySet(),
    )

    private data class QuotaIncidentClaim(
        val incidentId: String,
        val isNewIncident: Boolean,
        val cooldownStarted: Boolean,
        val cooldownStartedAtMillis: Long,
        val cooldownEndsAtMillis: Long,
    )

    private companion object {
        const val NOT_RUNNING = -1L
        const val ACTIVITY_HANDOFF_GRACE_MS = 350L
        const val SESSION_PLAN_PROMPT_STABLE_MS = 500L
        const val SESSION_PLAN_PROMPT_RETRY_MS = 1_000L
        const val STATE_PREFS = "__app_time_limiter_state__"
        const val RULE_CACHE_PREFS = "__app_time_limiter_rule_cache__"
        const val STATS_OUTBOX_PREFS = "__app_time_limiter_stats_outbox__"
        const val KEY_DAY = "day"
        const val KEY_VERSION = "rule_version"
        const val KEY_USED_MS = "used_ms"
        const val KEY_COOLDOWN_STARTED_AT = "cooldown_started_at"
        const val KEY_COOLDOWN_ENDS_AT = "cooldown_ends_at"
        const val KEY_COOLDOWN_INCIDENT_ID = "cooldown_incident_id"
        const val KEY_HANDLED_QUOTA_INCIDENTS = "handled_quota_incidents"
        const val KEY_COOLDOWN_RULE_VERSION = "cooldown_rule_version"
        const val KEY_COOLDOWN_RULE_IDENTITY = "cooldown_rule_identity"
        const val KEY_SCHEDULE_BLOCK_TOKEN = "schedule_block_token"
        const val CACHE_PRESENT = "present"
        const val CACHE_SIGNATURE = "signature"
        const val CACHE_ENABLED = "enabled"
        const val CACHE_SESSION_PLANNING_ENABLED = "session_planning_enabled"
        const val CACHE_DAILY_ENABLED = "daily_enabled"
        const val CACHE_DAILY_LIMIT_MS = "daily_limit_ms"
        const val CACHE_GROUP_ENABLED = "group_enabled"
        const val CACHE_GROUP_ID = "group_id"
        const val CACHE_GROUP_NAME = "group_name"
        const val CACHE_GROUP_DAILY_ENABLED = "group_daily_enabled"
        const val CACHE_GROUP_DAILY_LIMIT_MS = "group_daily_limit_ms"
        const val CACHE_GROUP_TODAY_USED_MS = "group_today_used_ms"
        const val CACHE_GROUP_VERSION = "group_version"
        const val CACHE_GROUP_DAY_TOKEN = "group_day_token"
        const val CACHE_GROUP_MEASURED_AT_ELAPSED_MS = "group_measured_at_elapsed_ms"
        const val CACHE_GROUP_PER_LAUNCH_ENABLED = "group_per_launch_enabled"
        const val CACHE_GROUP_PER_LAUNCH_LIMIT_MS = "group_per_launch_limit_ms"
        const val CACHE_GROUP_SCHEDULE_ENABLED = "group_schedule_enabled"
        const val CACHE_GROUP_SCHEDULE_MODE = "group_schedule_mode"
        const val CACHE_GROUP_SCHEDULE_WINDOWS = "group_schedule_windows"
        const val CACHE_GROUP_COOLDOWN_ENABLED = "group_cooldown_enabled"
        const val CACHE_GROUP_COOLDOWN_MS = "group_cooldown_ms"
        const val CACHE_GROUP_COOLDOWN_STARTED_AT_MS = "group_cooldown_started_at_ms"
        const val CACHE_GROUP_COOLDOWN_ENDS_AT_MS = "group_cooldown_ends_at_ms"
        const val CACHE_GROUP_COOLDOWN_INCIDENT_ID = "group_cooldown_incident_id"
        const val CACHE_GROUP_COOLDOWN_SOURCE_PACKAGE = "group_cooldown_source_package"
        const val CACHE_PER_LAUNCH_ENABLED = "per_launch_enabled"
        const val CACHE_PER_LAUNCH_LIMIT_MS = "per_launch_limit_ms"
        const val CACHE_SCHEDULE_ENABLED = "schedule_enabled"
        const val CACHE_SCHEDULE_MODE = "schedule_mode"
        const val CACHE_SCHEDULE_WINDOWS = "schedule_windows"
        const val CACHE_COOLDOWN_ENABLED = "cooldown_enabled"
        const val CACHE_COOLDOWN_MS = "cooldown_ms"
        const val CACHE_RULE_VERSION = "rule_version"
        const val CACHE_EXIT_WARNING_ENABLED = "exit_warning_enabled"
        const val CACHE_FULL_SCREEN_EXIT_WARNING_ENABLED = "full_screen_exit_warning_enabled"
        const val CACHE_EXIT_WARNING_VIBRATION_ENABLED = "exit_warning_vibration_enabled"
        const val CACHE_LANGUAGE_MODE = "language_mode"
        const val CACHE_EXTENSION_MS = "extension_ms"
        const val CACHE_LIMIT_ENFORCEMENT_MODE = "limit_enforcement_mode"
        const val CACHE_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        const val CACHE_USAGE_STATS_ENABLED = "usage_stats_enabled"
        const val OUTBOX_PENDING = "pending"
        const val OUTBOX_DAYS = "days"
        const val OUTBOX_DURATION_MS = "duration_ms"
        const val OUTBOX_LAUNCHES = "launches"
        const val OUTBOX_LIMIT_HITS = "limit_hits"
        const val LOGGABLE_SEGMENT_MS = 1_000L
        const val EXIT_DELAY_MS = 350L
        const val EXIT_DELAY_AFTER_STATS_FAILURE_MS = 1_500L
        const val FINAL_STATS_RETRY_DELAY_MS = 250L
        const val EXIT_RECOVERY_DELAY_MS = 2_000L
        const val WARNING_LEAD_MS = 5_000L
        const val EXIT_WARNING_VIBRATION_MS = 1_200L
        const val COUNTDOWN_REFRESH_MS = 1_000L
        const val SCHEDULE_RECHECK_MAX_MS = 60_000L
        const val RULE_RECHECK_MAX_MS = 60_000L
        const val SYSTEM_USAGE_PENDING_RECHECK_MS = 1_000L
        const val GROUP_USAGE_SYNC_INTERVAL_MS = 15_000L
        const val MAX_STATS_RETRIES = 3
        const val MAX_STATS_DURATION_PER_DAY_MS = 24L * 60L * 60L * 1000L
        val STATS_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L)
        const val MAX_TOTAL_EXTENSION_MS = 24L * 60L * 60L * 1000L
    }
}
