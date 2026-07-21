package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.CooldownPolicy
import com.liuml.apptimelimiter.core.UsageMath
import com.liuml.apptimelimiter.core.ProcessTerminationPolicy
import com.liuml.apptimelimiter.core.ScheduleDecision
import com.liuml.apptimelimiter.core.ScheduleEvaluator
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.data.ScheduleMode
import com.liuml.apptimelimiter.data.ScheduleWindow
import com.liuml.apptimelimiter.ipc.RuleContract
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
        val limiter = RuntimeLimiter(lpparam.packageName, lpparam.processName, preferences)
        try {
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
            XposedBridge.log(
                "AppTimeLimiter: HOOK_INSTALLED package=${lpparam.packageName} process=${lpparam.processName} version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) entry=Instrumentation",
            )
        } catch (error: Throwable) {
            XposedBridge.log(
                "AppTimeLimiter: HOOK_FAILED package=${lpparam.packageName} process=${lpparam.processName}",
            )
            XposedBridge.log(error)
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
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeActivity = WeakReference<Activity>(null)
    private var foregroundStartedAt = NOT_RUNNING
    private var foregroundDayToken = -1
    private var perLaunchCommittedMs = 0L
    private var loadedRuleVersion = Long.MIN_VALUE
    private var exitScheduled = false
    private var hookReadyLogged = false
    private var lastRuleSummary = ""
    private var providerFailureLogged = false
    private var diagnosticsEnabled = true
    private var grantedExtensionMs = 0L
    private var warningShownForExtensionMs = Long.MIN_VALUE
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

    private val deadline = Runnable { checkDeadline() }
    private val warningDeadline = Runnable { showExitWarning() }
    private val scheduleDeadline = Runnable { checkScheduleBoundary() }
    private val scheduleWarningDeadline = Runnable { showScheduleWarning() }
    private val midnightDeadline = Runnable { checkMidnightRollover() }
    private val groupUsageSync = Runnable { syncGroupUsage() }
    private val statsRetry = Runnable {
        statsContext?.let(::flushUsageEvents)
    }

    fun onActivityResumed(activity: Activity) {
        if (exitScheduled) return
        val rule = readRule(activity, reloadFallback = true)
        if (!hookReadyLogged) {
            hookReadyLogged = true
            diagnostic(
                activity,
                event = "HOOK_READY",
                message = "Instrumentation Hook 已运行；version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})；process=$processName；规则来源=${rule.source}",
            )
        }

        val ruleSummary = "${rule.enabled}/${rule.dailyEnabled}/${rule.dailyLimitMillis}/${rule.systemTodayUsedMillis}/${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis}/${rule.groupEnabled}/${rule.groupId}/${rule.groupDailyLimitMillis}/${rule.groupTodayUsedMillis}/${rule.groupVersion}/${rule.scheduleEnabled}/${rule.scheduleMode}/${ScheduleCodec.encode(rule.scheduleWindows)}/${rule.cooldownEnabled}/${rule.cooldownMillis}/${rule.version}/${rule.exitWarningEnabled}/${rule.fullScreenExitWarningEnabled}/${rule.extensionMillis}/${rule.diagnosticsEnabled}/${rule.source}"
        if (lastRuleSummary != ruleSummary) {
            lastRuleSummary = ruleSummary
            diagnostic(
                activity,
                event = "RULE_READ",
                message = "enabled=${rule.enabled}, daily=${rule.dailyEnabled}/${rule.dailyLimitMillis / 1000}s, systemToday=${rule.systemTodayUsedMillis / 1000.0}s, perLaunch=${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis / 1000}s, group=${rule.groupEnabled}/${rule.groupName}/${rule.groupTodayUsedMillis / 1000.0}s/${rule.groupDailyLimitMillis / 1000}s, schedule=${rule.scheduleEnabled}/${rule.scheduleMode}/${rule.scheduleWindows.size}, cooldown=${rule.cooldownEnabled}/${rule.cooldownMillis / 1000}s, warning=${rule.exitWarningEnabled}/${rule.fullScreenExitWarningEnabled}, extension=${rule.extensionMillis / 1000}s, source=${rule.source}",
            )
        }

        if (!rule.enabled) {
            stopTiming()
            return
        }

        if (loadedRuleVersion != rule.version) {
            loadedRuleVersion = rule.version
            perLaunchCommittedMs = 0L
            grantedExtensionMs = 0L
            warningShownForExtensionMs = Long.MIN_VALUE
            foregroundStartedAt = NOT_RUNNING
        }

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

        val cooldownRemainingMillis = cooldownRemainingMillis(activity, rule)
        if (cooldownRemainingMillis > 0L) {
            forceCooldownExit(activity, rule, cooldownRemainingMillis, launchStatsPersisted)
            return
        }

        if (rule.scheduleEnabled) {
            val scheduleDecision = evaluateSchedule(rule)
            if (!scheduleDecision.allowed) {
                forceScheduleExit(activity, rule, scheduleDecision, openedDuringBlockedTime = true)
                return
            }
        }

        if (rule.dailyEnabled) syncSystemDailyBaseline(activity, rule)

        activeActivity = WeakReference(activity)
        val startedNow = foregroundStartedAt == NOT_RUNNING
        if (startedNow) {
            foregroundStartedAt = SystemClock.elapsedRealtime()
            foregroundDayToken = dayToken()
        }
        if (startedNow || groupSegmentIdentity != rule.groupIdentity()) {
            updateGroupSegmentBaseline(rule)
        }
        val remainingMs = if (rule.dailyEnabled || rule.perLaunchEnabled || rule.groupEnabled) {
            scheduleDeadline(activity, rule)
        } else {
            null
        }
        val scheduleRemainingMs = if (!exitScheduled) scheduleScheduleBoundary(activity, rule) else null
        if (!exitScheduled) scheduleMidnightRollover(rule)
        if (!exitScheduled) scheduleGroupUsageSync(rule)
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
    }

    fun onActivityPaused(activity: Activity) {
        if (activeActivity.get() !== activity || foregroundStartedAt == NOT_RUNNING) return
        val rule = readRule(activity, reloadFallback = true)
        val segmentMs = activeSegmentMillis()
        commitActiveSegment(activity, rule)
        activeActivity.clear()
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        foregroundDayToken = -1
        dismissWarning(resetForCurrentLimit = true)
        dismissScheduleWarning()
        if (rule.enabled && segmentMs >= LOGGABLE_SEGMENT_MS) {
            diagnostic(
                activity,
                event = "TIMER_PAUSE",
                message = "本段=${segmentMs / 1000.0}s，${usageSummary(activity, rule)}",
            )
        }
    }

    private fun scheduleDeadline(activity: Activity, rule: HookRule): Long {
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        val status = thresholdStatus(activity, rule)
        val remainingMs = status.remainingMillis
        if (status.reached) {
            forceExit(activity, rule, status)
        } else {
            mainHandler.postDelayed(deadline, minOf(remainingMs, RULE_RECHECK_MAX_MS))
            if (rule.exitWarningEnabled && warningShownForExtensionMs != grantedExtensionMs) {
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
        }
    }

    private fun scheduleMidnightRollover(rule: HookRule) {
        mainHandler.removeCallbacks(midnightDeadline)
        if (exitScheduled || (!rule.dailyEnabled && !rule.groupEnabled) || foregroundStartedAt == NOT_RUNNING) return
        val now = ZonedDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val delayMs = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L)
        mainHandler.postDelayed(midnightDeadline, delayMs)
    }

    private fun checkMidnightRollover() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || foregroundStartedAt == NOT_RUNNING) return
        val rule = readRule(activity, reloadFallback = true)
        if (!rule.enabled || (!rule.dailyEnabled && !rule.groupEnabled)) {
            mainHandler.removeCallbacks(midnightDeadline)
            return
        }
        val currentDay = dayToken()
        if (foregroundDayToken != currentDay) {
            val fullSegmentMillis = activeSegmentMillis()
            val todaySegmentMillis = activeSegmentMillisForToday()
            if (rule.groupEnabled) {
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
            if (rule.perLaunchEnabled) perLaunchCommittedMs += fullSegmentMillis
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
        if (!exitScheduled && rule.groupEnabled && foregroundStartedAt != NOT_RUNNING) {
            mainHandler.postDelayed(groupUsageSync, GROUP_USAGE_SYNC_INTERVAL_MS)
        }
    }

    private fun syncGroupUsage() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || foregroundStartedAt == NOT_RUNNING) return
        val currentRule = lastLoadedRule ?: readRule(activity, reloadFallback = true)
        if (!currentRule.enabled || !currentRule.groupEnabled) return
        commitActiveSegment(activity, currentRule)
        foregroundStartedAt = SystemClock.elapsedRealtime()
        foregroundDayToken = dayToken()
        val refreshedRule = readRule(activity, reloadFallback = true)
        if (!refreshedRule.enabled) {
            stopTiming()
            return
        }
        if (!refreshedRule.groupEnabled) {
            if (refreshedRule.dailyEnabled || refreshedRule.perLaunchEnabled) {
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
        if (!rule.scheduleEnabled) return null
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
        if (!rule.scheduleEnabled) {
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
        exitScheduled = true
        startCooldown(activity, rule)
        val statsPersisted = reportLimitHit(activity, rule)
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        mainHandler.removeCallbacks(midnightDeadline)
        mainHandler.removeCallbacks(groupUsageSync)
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        val nextAllowed = formatNextTransition(decision)
        diagnostic(
            activity,
            level = "WARN",
            event = if (openedDuringBlockedTime) "SCHEDULE_DENIED" else "SCHEDULE_BOUNDARY_REACHED",
            message = "当前时段不可使用；mode=${rule.scheduleMode}, nextAllowed=$nextAllowed, pid=${Process.myPid()}",
        )
        val message = if (nextAllowed == null) {
            "当前处于不可用时段"
        } else {
            "当前处于不可用时段，下次可用：$nextAllowed"
        }
        finishTarget(activity, message, statsPersisted)
    }

    private fun forceExit(activity: Activity, rule: HookRule, status: ThresholdStatus) {
        if (exitScheduled) return
        exitScheduled = true
        startCooldown(activity, rule)
        dismissWarning(resetForCurrentLimit = false)
        val segmentMs = activeSegmentMillis()
        val statsPersisted = recordUsageEvent(
            activity = activity,
            durationMillis = if (rule.groupEnabled) segmentMs else 0L,
            launchIncrement = 0,
            limitHitIncrement = if (rule.usageStatsEnabled) 1 else 0,
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
        if (rule.perLaunchEnabled) perLaunchCommittedMs += segmentMs
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
            message = "达到${status.reachedLabels}阈值（本次已延时 ${grantedExtensionMs / 1000}s），执行限制退出 pid=${Process.myPid()}",
        )
        finishTarget(
            activity,
            if (status.groupOnlyReached) {
                "${rule.groupName.ifBlank { "分组" }}共享额度已用尽"
            } else {
                "已达到使用时长限制"
            },
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
        exitScheduled = true
        foregroundStartedAt = NOT_RUNNING
        foregroundDayToken = -1
        activeActivity.clear()
        stopTimingCallbacks()
        dismissWarning(resetForCurrentLimit = false)
        dismissScheduleWarning()
        val remainingText = formatCooldownRemaining(remainingMillis)
        diagnostic(
            activity,
            level = "WARN",
            event = "COOLDOWN_BLOCKED",
            message = "冷却期间拒绝打开；remaining=${remainingMillis / 1000.0}s, configured=${rule.cooldownMillis / 1000}s",
        )
        finishTarget(activity, "冷却中，$remainingText 后可再次打开", statsPersisted)
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

    private fun startCooldown(context: Context, rule: HookRule) {
        if (!rule.cooldownEnabled || rule.cooldownMillis <= 0L) return
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_COOLDOWN_STARTED_AT, System.currentTimeMillis())
            .putLong(KEY_COOLDOWN_RULE_VERSION, rule.version)
            .commit()
        diagnostic(
            context,
            event = "COOLDOWN_STARTED",
            message = "强制退出后开始冷却；duration=${rule.cooldownMillis / 1000}s",
        )
    }

    private fun cooldownRemainingMillis(context: Context, rule: HookRule): Long {
        if (!rule.cooldownEnabled || rule.cooldownMillis <= 0L) return 0L
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_COOLDOWN_RULE_VERSION, Long.MIN_VALUE) != rule.version) return 0L
        return CooldownPolicy.remainingMillis(
            startedAtMillis = prefs.getLong(KEY_COOLDOWN_STARTED_AT, 0L),
            durationMillis = rule.cooldownMillis,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun formatCooldownRemaining(remainingMillis: Long): String {
        val totalSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return when {
            minutes == 0L -> "$seconds 秒"
            seconds == 0L -> "$minutes 分钟"
            else -> "$minutes 分 $seconds 秒"
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
        if (rule.perLaunchEnabled) perLaunchCommittedMs += segmentMs
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
        if (remainingMs > WARNING_LEAD_MS) {
            scheduleDeadline(activity, rule)
            return
        }
        if (
            warningShownForExtensionMs == grantedExtensionMs ||
            isBannerShowing(WarningBannerKind.TIME_LIMIT)
        ) return
        warningShownForExtensionMs = grantedExtensionMs

        runCatching {
            warningBanner = TopWarningBanner.attach(
                activity = activity,
                kind = WarningBannerKind.TIME_LIMIT,
                title = "${status.nextThresholdLabel}即将到期",
                message = exitWarningMessage(remainingMs, rule.extensionMillis),
                remainingMillis = remainingMs,
                maxProgressMillis = WARNING_LEAD_MS,
                fullScreen = rule.fullScreenExitWarningEnabled,
                actionLabel = "延时 ${formatDuration(rule.extensionMillis)}",
                onAction = {
                    if (!activity.isFinishing && !activity.isDestroyed && !exitScheduled) {
                        dismissWarning(resetForCurrentLimit = false)
                        grantExtension(activity, rule.extensionMillis)
                    }
                },
            )
            diagnostic(
                activity,
                event = "WARNING_SHOWN",
                message = "顶部退出提醒已显示；可延时=${rule.extensionMillis / 1000}s",
            )
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
        if (!rule.enabled || !rule.scheduleEnabled || !rule.exitWarningEnabled) return
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
        dismissWarning(resetForCurrentLimit = false)
        runCatching {
            warningBanner = TopWarningBanner.attach(
                activity = activity,
                kind = WarningBannerKind.SCHEDULE,
                title = "即将进入不可用时段",
                message = scheduleWarningMessage(remainingMs),
                remainingMillis = remainingMs,
                maxProgressMillis = WARNING_LEAD_MS,
                fullScreen = rule.fullScreenExitWarningEnabled,
            )
            diagnostic(
                activity,
                event = "SCHEDULE_WARNING_SHOWN",
                message = "顶部时段提醒已显示",
            )
            startScheduleWarningCountdown(rule)
        }.onFailure {
            dismissScheduleWarning()
            diagnostic(activity, level = "ERROR", event = "SCHEDULE_WARNING_FAILED", message = it.toString())
        }
    }

    private fun startScheduleWarningCountdown(rule: HookRule) {
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
                    title = "即将进入不可用时段",
                    message = scheduleWarningMessage(remainingMs),
                    remainingMillis = remainingMs,
                )
                if (remainingMs > 0L) mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = countdown
        mainHandler.post(countdown)
    }

    private fun scheduleWarningMessage(remainingMs: Long): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return "$seconds 秒后退出 · 时段限制不可延时"
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
                banner.update(
                    title = "${status.nextThresholdLabel}即将到期",
                    message = exitWarningMessage(remainingMs, rule.extensionMillis),
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
    }

    private fun dismissWarning(resetForCurrentLimit: Boolean) {
        dismissBanner(WarningBannerKind.TIME_LIMIT)
        if (resetForCurrentLimit) warningShownForExtensionMs = Long.MIN_VALUE
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

    private fun evaluateSchedule(rule: HookRule): ScheduleDecision = ScheduleEvaluator.evaluate(
        mode = rule.scheduleMode,
        windows = rule.scheduleWindows,
        now = ZonedDateTime.now(),
    )

    private fun formatNextTransition(decision: ScheduleDecision): String? = decision.nextTransition
        ?.format(DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA))

    private fun effectiveLimitMillis(baseLimitMillis: Long): Long =
        (baseLimitMillis + grantedExtensionMs).coerceAtMost(Long.MAX_VALUE / 2L)

    private fun exitWarningMessage(
        remainingMs: Long,
        extensionMillis: Long,
    ): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return "$seconds 秒后退出 · 延时将同步延长已开启阈值 ${formatDuration(extensionMillis)}"
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000L
        return if (seconds < 60L) "$seconds 秒" else "${seconds / 60L} 分钟"
    }

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
        if (rule.systemTodayUsedMillis > localUsedMillis) {
            writeDailyState(context, rule, rule.systemTodayUsedMillis)
        }
    }

    private fun authoritativeDailyTotalMillis(
        context: Context,
        rule: HookRule,
        activeTodayMillis: Long,
    ): Long = UsageMath.authoritativeDailyUsedMillis(
        localDailyUsedMillis(context, rule) + activeTodayMillis.coerceAtLeast(0L),
        rule.systemTodayUsedMillis,
    )

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

    private fun thresholdStatus(context: Context, rule: HookRule): ThresholdStatus {
        val currentGroupIdentity = rule.groupIdentity()
        if (loadedGroupIdentity != currentGroupIdentity) {
            loadedGroupIdentity = currentGroupIdentity
            grantedExtensionMs = 0L
            warningShownForExtensionMs = Long.MIN_VALUE
            updateGroupSegmentBaseline(rule)
        }
        val activeMs = activeSegmentMillis()
        val thresholds = buildList {
            if (rule.dailyEnabled) {
                add(
                    ThresholdRemaining(
                        label = "每日累计",
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
                        label = "单次打开",
                        isGroup = false,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.perLaunchLimitMillis),
                            perLaunchCommittedMs,
                            activeMs,
                        ),
                    ),
                )
            }
            if (rule.groupEnabled) {
                add(
                    ThresholdRemaining(
                        label = "${rule.groupName.ifBlank { "应用分组" }}共享额度",
                        isGroup = true,
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.groupDailyLimitMillis),
                            authoritativeGroupTodayUsedMillis(rule),
                            0L,
                        ),
                    ),
                )
            }
        }
        if (thresholds.isEmpty()) {
            return ThresholdStatus(Long.MAX_VALUE / 2L, false, "未启用", "未启用", false)
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
        )
    }

    private fun authoritativeGroupTodayUsedMillis(rule: HookRule): Long {
        if (!rule.groupEnabled) return 0L
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
        if (rule.perLaunchEnabled) add("单次累计=${perLaunchCommittedMs / 1000.0}s")
        if (rule.groupEnabled) {
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
                groupEnabled = result.getBoolean(RuleContract.KEY_GROUP_ENABLED, false),
                groupId = result.getString(RuleContract.KEY_GROUP_ID).orEmpty(),
                groupName = result.getString(RuleContract.KEY_GROUP_NAME).orEmpty(),
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
                extensionMillis = result.getLong(
                    RuleContract.KEY_EXTENSION_SECONDS,
                    RuleRepository.DEFAULT_EXTENSION_SECONDS,
                ).coerceIn(
                    RuleRepository.MIN_EXTENSION_SECONDS,
                    RuleRepository.MAX_EXTENSION_SECONDS,
                ) * 1000L,
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
        val groupEnabled = groupId.isNotBlank() &&
            packageName in groupMembers &&
            preferences.getBoolean("${groupPrefix}enabled", false)
        if (storedVersion == Long.MIN_VALUE && membershipVersion == Long.MIN_VALUE && !groupEnabled) {
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
        val dailyEnabled = if (hasDualThresholdRule) {
            preferences.getBoolean("${prefix}daily_enabled", false)
        } else {
            legacyEnabled && legacyDaily
        }
        val perLaunchEnabled = if (hasDualThresholdRule) {
            preferences.getBoolean("${prefix}per_launch_enabled", false)
        } else {
            legacyEnabled && !legacyDaily
        }
        val scheduleEnabled = preferences.getBoolean("${prefix}schedule_enabled", false)
        return HookRule(
            enabled = (legacyEnabled && (dailyEnabled || perLaunchEnabled || scheduleEnabled)) ||
                groupEnabled,
            dailyEnabled = dailyEnabled,
            dailyLimitMillis = (if (dailyLimitSeconds >= 0L) dailyLimitSeconds else legacyLimitSeconds)
                .coerceIn(
                    RuleRepository.MIN_LIMIT_SECONDS,
                    RuleRepository.MAX_LIMIT_SECONDS,
                ) * 1000L,
            systemTodayUsedMillis = -1L,
            groupEnabled = groupEnabled,
            groupId = groupId,
            groupName = preferences.getString("${groupPrefix}name", "").orEmpty(),
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
            cooldownEnabled = preferences.getBoolean("${prefix}cooldown_enabled", false),
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
            extensionMillis = preferences.getLong(
                RuleRepository.KEY_EXTENSION_SECONDS,
                RuleRepository.DEFAULT_EXTENSION_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_EXTENSION_SECONDS,
                RuleRepository.MAX_EXTENSION_SECONDS,
            ) * 1000L,
            diagnosticsEnabled = preferences.getBoolean(RuleRepository.KEY_DIAGNOSTICS_ENABLED, true),
            usageStatsEnabled = preferences.getBoolean(RuleRepository.KEY_USAGE_STATS_ENABLED, true),
            source = "xsharedpreferences",
        )
    }

    private fun cacheRule(context: Context, rule: HookRule) {
        val signature = listOf(
            rule.enabled,
            rule.dailyEnabled,
            rule.dailyLimitMillis,
            rule.groupEnabled,
            rule.groupId,
            rule.groupName,
            rule.groupDailyLimitMillis,
            rule.groupTodayUsedMillis,
            rule.groupVersion,
            rule.groupDayToken,
            rule.groupMeasuredAtElapsedMillis,
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
            rule.extensionMillis,
            rule.diagnosticsEnabled,
            rule.usageStatsEnabled,
        ).joinToString("|")
        val prefs = context.getSharedPreferences(RULE_CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(CACHE_SIGNATURE, null) == signature) return
        prefs.edit()
            .putBoolean(CACHE_PRESENT, true)
            .putBoolean(CACHE_ENABLED, rule.enabled)
            .putBoolean(CACHE_DAILY_ENABLED, rule.dailyEnabled)
            .putLong(CACHE_DAILY_LIMIT_MS, rule.dailyLimitMillis)
            .putBoolean(CACHE_GROUP_ENABLED, rule.groupEnabled)
            .putString(CACHE_GROUP_ID, rule.groupId)
            .putString(CACHE_GROUP_NAME, rule.groupName)
            .putLong(CACHE_GROUP_DAILY_LIMIT_MS, rule.groupDailyLimitMillis)
            .putLong(CACHE_GROUP_TODAY_USED_MS, rule.groupTodayUsedMillis)
            .putLong(CACHE_GROUP_VERSION, rule.groupVersion)
            .putString(CACHE_GROUP_DAY_TOKEN, rule.groupDayToken)
            .putLong(CACHE_GROUP_MEASURED_AT_ELAPSED_MS, rule.groupMeasuredAtElapsedMillis)
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
            .putLong(CACHE_EXTENSION_MS, rule.extensionMillis)
            .putBoolean(CACHE_DIAGNOSTICS_ENABLED, rule.diagnosticsEnabled)
            .putBoolean(CACHE_USAGE_STATS_ENABLED, rule.usageStatsEnabled)
            .putString(CACHE_SIGNATURE, signature)
            .commit()
    }

    private fun readCachedRule(context: Context): HookRule? {
        val prefs = context.getSharedPreferences(RULE_CACHE_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(CACHE_PRESENT, false)) return null
        return HookRule(
            enabled = prefs.getBoolean(CACHE_ENABLED, false),
            dailyEnabled = prefs.getBoolean(CACHE_DAILY_ENABLED, false),
            dailyLimitMillis = prefs.getLong(
                CACHE_DAILY_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            systemTodayUsedMillis = -1L,
            groupEnabled = prefs.getBoolean(CACHE_GROUP_ENABLED, false),
            groupId = prefs.getString(CACHE_GROUP_ID, "").orEmpty(),
            groupName = prefs.getString(CACHE_GROUP_NAME, "").orEmpty(),
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
            perLaunchEnabled = prefs.getBoolean(CACHE_PER_LAUNCH_ENABLED, false),
            perLaunchLimitMillis = prefs.getLong(
                CACHE_PER_LAUNCH_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_LIMIT_SECONDS * 1000L,
                RuleRepository.MAX_LIMIT_SECONDS * 1000L,
            ),
            scheduleEnabled = prefs.getBoolean(CACHE_SCHEDULE_ENABLED, false),
            scheduleMode = prefs.getString(CACHE_SCHEDULE_MODE, ScheduleMode.BLOCK_DURING.name)
                ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(prefs.getString(CACHE_SCHEDULE_WINDOWS, null)),
            cooldownEnabled = prefs.getBoolean(CACHE_COOLDOWN_ENABLED, false),
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
            extensionMillis = prefs.getLong(
                CACHE_EXTENSION_MS,
                RuleRepository.DEFAULT_EXTENSION_SECONDS * 1000L,
            ).coerceIn(
                RuleRepository.MIN_EXTENSION_SECONDS * 1000L,
                RuleRepository.MAX_EXTENSION_SECONDS * 1000L,
            ),
            diagnosticsEnabled = prefs.getBoolean(CACHE_DIAGNOSTICS_ENABLED, true),
            usageStatsEnabled = prefs.getBoolean(CACHE_USAGE_STATS_ENABLED, true),
            source = "local_cache",
        )
    }

    private fun unavailableRule() = HookRule(
        enabled = false,
        dailyEnabled = false,
        dailyLimitMillis = RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
        systemTodayUsedMillis = -1L,
        groupEnabled = false,
        groupId = "",
        groupName = "",
        groupDailyLimitMillis = RuleRepository.DEFAULT_GROUP_LIMIT_SECONDS * 1000L,
        groupTodayUsedMillis = -1L,
        groupVersion = 0L,
        groupDayToken = "",
        groupMeasuredAtElapsedMillis = SystemClock.elapsedRealtime(),
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
        extensionMillis = RuleRepository.DEFAULT_EXTENSION_SECONDS * 1000L,
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
        val dailyEnabled: Boolean,
        val dailyLimitMillis: Long,
        val systemTodayUsedMillis: Long,
        val groupEnabled: Boolean,
        val groupId: String,
        val groupName: String,
        val groupDailyLimitMillis: Long,
        val groupTodayUsedMillis: Long,
        val groupVersion: Long,
        val groupDayToken: String,
        val groupMeasuredAtElapsedMillis: Long,
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
        val extensionMillis: Long,
        val diagnosticsEnabled: Boolean,
        val usageStatsEnabled: Boolean,
        val source: String,
    )

    private data class ThresholdRemaining(
        val label: String,
        val isGroup: Boolean,
        val remainingMillis: Long,
    )

    private data class ThresholdStatus(
        val remainingMillis: Long,
        val reached: Boolean,
        val reachedLabels: String,
        val nextThresholdLabel: String,
        val groupOnlyReached: Boolean,
    )

    private data class PendingUsageBatch(
        var durationMillis: Long = 0L,
        var launches: Int = 0,
        var limitHits: Int = 0,
    )

    private companion object {
        const val NOT_RUNNING = -1L
        const val STATE_PREFS = "__app_time_limiter_state__"
        const val RULE_CACHE_PREFS = "__app_time_limiter_rule_cache__"
        const val STATS_OUTBOX_PREFS = "__app_time_limiter_stats_outbox__"
        const val KEY_DAY = "day"
        const val KEY_VERSION = "rule_version"
        const val KEY_USED_MS = "used_ms"
        const val KEY_COOLDOWN_STARTED_AT = "cooldown_started_at"
        const val KEY_COOLDOWN_RULE_VERSION = "cooldown_rule_version"
        const val CACHE_PRESENT = "present"
        const val CACHE_SIGNATURE = "signature"
        const val CACHE_ENABLED = "enabled"
        const val CACHE_DAILY_ENABLED = "daily_enabled"
        const val CACHE_DAILY_LIMIT_MS = "daily_limit_ms"
        const val CACHE_GROUP_ENABLED = "group_enabled"
        const val CACHE_GROUP_ID = "group_id"
        const val CACHE_GROUP_NAME = "group_name"
        const val CACHE_GROUP_DAILY_LIMIT_MS = "group_daily_limit_ms"
        const val CACHE_GROUP_TODAY_USED_MS = "group_today_used_ms"
        const val CACHE_GROUP_VERSION = "group_version"
        const val CACHE_GROUP_DAY_TOKEN = "group_day_token"
        const val CACHE_GROUP_MEASURED_AT_ELAPSED_MS = "group_measured_at_elapsed_ms"
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
        const val CACHE_EXTENSION_MS = "extension_ms"
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
        const val COUNTDOWN_REFRESH_MS = 1_000L
        const val SCHEDULE_RECHECK_MAX_MS = 60_000L
        const val RULE_RECHECK_MAX_MS = 60_000L
        const val GROUP_USAGE_SYNC_INTERVAL_MS = 15_000L
        const val MAX_STATS_RETRIES = 3
        const val MAX_STATS_DURATION_PER_DAY_MS = 24L * 60L * 60L * 1000L
        val STATS_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L)
        const val MAX_TOTAL_EXTENSION_MS = 24L * 60L * 60L * 1000L
    }
}
