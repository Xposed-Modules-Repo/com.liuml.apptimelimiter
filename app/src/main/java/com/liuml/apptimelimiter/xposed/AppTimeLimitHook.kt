package com.liuml.apptimelimiter.xposed

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.liuml.apptimelimiter.core.UsageMath
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
                "AppTimeLimiter: HOOK_INSTALLED package=${lpparam.packageName} process=${lpparam.processName} entry=Instrumentation",
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
    private var perLaunchCommittedMs = 0L
    private var loadedRuleVersion = Long.MIN_VALUE
    private var exitScheduled = false
    private var hookReadyLogged = false
    private var lastRuleSummary = ""
    private var providerFailureLogged = false
    private var diagnosticsEnabled = true
    private var grantedExtensionMs = 0L
    private var warningShownForExtensionMs = Long.MIN_VALUE
    private var warningDialog: AlertDialog? = null
    private var warningCountdown: Runnable? = null
    private var scheduleWarningDialog: AlertDialog? = null
    private var scheduleWarningCountdown: Runnable? = null
    private var sessionLaunchReported = false
    private var statsContext: Context? = null
    private var pendingStatsDurationMs = 0L
    private var pendingStatsLaunches = 0
    private var pendingStatsLimitHits = 0
    private var pendingStatsHeartbeat = false
    private var statsRetryCount = 0
    private var statsSuccessLogged = false
    private var statsFailureLogged = false
    private var statsOutboxLoaded = false

    private val deadline = Runnable { checkDeadline() }
    private val warningDeadline = Runnable { showExitWarning() }
    private val scheduleDeadline = Runnable { checkScheduleBoundary() }
    private val scheduleWarningDeadline = Runnable { showScheduleWarning() }
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
                message = "Instrumentation Hook 已运行；process=$processName；规则来源=${rule.source}",
            )
        }

        val ruleSummary = "${rule.enabled}/${rule.dailyEnabled}/${rule.dailyLimitMillis}/${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis}/${rule.scheduleEnabled}/${rule.scheduleMode}/${ScheduleCodec.encode(rule.scheduleWindows)}/${rule.version}/${rule.exitWarningEnabled}/${rule.extensionMillis}/${rule.diagnosticsEnabled}/${rule.source}"
        if (lastRuleSummary != ruleSummary) {
            lastRuleSummary = ruleSummary
            diagnostic(
                activity,
                event = "RULE_READ",
                message = "enabled=${rule.enabled}, daily=${rule.dailyEnabled}/${rule.dailyLimitMillis / 1000}s, perLaunch=${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis / 1000}s, schedule=${rule.scheduleEnabled}/${rule.scheduleMode}/${rule.scheduleWindows.size}, warning=${rule.exitWarningEnabled}, extension=${rule.extensionMillis / 1000}s, source=${rule.source}",
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

        if (!sessionLaunchReported) {
            recordUsageEvent(
                activity = activity,
                durationMillis = 0L,
                launchIncrement = if (rule.usageStatsEnabled) 1 else 0,
                limitHitIncrement = 0,
            )
            sessionLaunchReported = true
        }

        if (rule.scheduleEnabled) {
            val scheduleDecision = evaluateSchedule(rule)
            if (!scheduleDecision.allowed) {
                forceScheduleExit(activity, rule, scheduleDecision, openedDuringBlockedTime = true)
                return
            }
        }

        activeActivity = WeakReference(activity)
        val startedNow = foregroundStartedAt == NOT_RUNNING
        if (startedNow) {
            foregroundStartedAt = SystemClock.elapsedRealtime()
        }
        val remainingMs = if (rule.dailyEnabled || rule.perLaunchEnabled) {
            scheduleDeadline(activity, rule)
        } else {
            null
        }
        val scheduleRemainingMs = if (!exitScheduled) scheduleScheduleBoundary(activity, rule) else null
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
            mainHandler.postDelayed(deadline, remainingMs)
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
        reportLimitHit(activity, rule)
        if (foregroundStartedAt != NOT_RUNNING) commitActiveSegment(activity, rule)
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
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
            "当前处于不可用时段，应用即将退出"
        } else {
            "当前处于不可用时段，下次可用：$nextAllowed"
        }
        finishTarget(activity, message)
    }

    private fun forceExit(activity: Activity, rule: HookRule, status: ThresholdStatus) {
        if (exitScheduled) return
        exitScheduled = true
        reportLimitHit(activity, rule)
        dismissWarning(resetForCurrentLimit = false)
        val segmentMs = activeSegmentMillis()
        if (rule.dailyEnabled) {
            val finalDailyUsed = (dailyUsedMillis(activity, rule) + segmentMs)
                .coerceAtMost(effectiveLimitMillis(rule.dailyLimitMillis))
            writeDailyState(activity, rule, finalDailyUsed)
        }
        if (rule.perLaunchEnabled) perLaunchCommittedMs += segmentMs
        foregroundStartedAt = NOT_RUNNING
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        dismissScheduleWarning()

        diagnostic(
            activity,
            level = "WARN",
            event = "LIMIT_REACHED",
            message = "达到${status.reachedLabels}阈值（本次已延时 ${grantedExtensionMs / 1000}s），准备结束 pid=${Process.myPid()}",
        )
        finishTarget(activity, "已达到使用时长，应用即将退出")
    }

    private fun finishTarget(activity: Activity, message: String) {
        runCatching {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            activity.finishAndRemoveTask()
            activity.finishAffinity()
            activity.moveTaskToBack(true)
        }.onFailure {
            diagnostic(activity, level = "ERROR", event = "FINISH_FAILED", message = it.toString())
        }
        mainHandler.postDelayed(
            {
                val pid = Process.myPid()
                runCatching { Process.killProcess(pid) }
                Runtime.getRuntime().exit(0)
            },
            EXIT_DELAY_MS,
        )
    }

    private fun commitActiveSegment(activity: Activity, rule: HookRule) {
        val segmentMs = activeSegmentMillis()
        if (segmentMs <= 0L) {
            foregroundStartedAt = NOT_RUNNING
            return
        }
        if (rule.dailyEnabled) {
            writeDailyState(activity, rule, dailyUsedMillis(activity, rule) + segmentMs)
        }
        if (rule.perLaunchEnabled) perLaunchCommittedMs += segmentMs
        foregroundStartedAt = NOT_RUNNING
    }

    private fun reportLimitHit(activity: Activity, rule: HookRule) {
        recordUsageEvent(
            activity = activity,
            durationMillis = 0L,
            launchIncrement = 0,
            limitHitIncrement = if (rule.usageStatsEnabled) 1 else 0,
        )
    }

    private fun recordUsageEvent(
        activity: Activity,
        durationMillis: Long,
        launchIncrement: Int,
        limitHitIncrement: Int,
    ) {
        val context = activity.applicationContext
        statsContext = context
        loadStatsOutbox(context)
        pendingStatsDurationMs += durationMillis.coerceAtLeast(0L)
        pendingStatsLaunches += launchIncrement.coerceAtLeast(0)
        pendingStatsLimitHits += limitHitIncrement.coerceAtLeast(0)
        pendingStatsHeartbeat = true
        persistStatsOutbox(context)
        flushUsageEvents(context)
    }

    private fun loadStatsOutbox(context: Context) {
        if (statsOutboxLoaded) return
        val prefs = context.getSharedPreferences(STATS_OUTBOX_PREFS, Context.MODE_PRIVATE)
        pendingStatsDurationMs = prefs.getLong(OUTBOX_DURATION_MS, 0L).coerceAtLeast(0L)
        pendingStatsLaunches = prefs.getInt(OUTBOX_LAUNCHES, 0).coerceAtLeast(0)
        pendingStatsLimitHits = prefs.getInt(OUTBOX_LIMIT_HITS, 0).coerceAtLeast(0)
        pendingStatsHeartbeat = prefs.getBoolean(OUTBOX_PENDING, false)
        statsOutboxLoaded = true
    }

    private fun persistStatsOutbox(context: Context) {
        context.getSharedPreferences(STATS_OUTBOX_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(OUTBOX_DURATION_MS, pendingStatsDurationMs)
            .putInt(OUTBOX_LAUNCHES, pendingStatsLaunches)
            .putInt(OUTBOX_LIMIT_HITS, pendingStatsLimitHits)
            .putBoolean(OUTBOX_PENDING, pendingStatsHeartbeat)
            .commit()
    }

    private fun flushUsageEvents(context: Context) {
        if (!pendingStatsHeartbeat) return
        val extras = Bundle().apply {
            putLong(RuleContract.KEY_DURATION_MS, pendingStatsDurationMs)
            putInt(RuleContract.KEY_LAUNCH_INCREMENT, pendingStatsLaunches)
            putInt(RuleContract.KEY_LIMIT_HIT_INCREMENT, pendingStatsLimitHits)
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
            pendingStatsDurationMs = 0L
            pendingStatsLaunches = 0
            pendingStatsLimitHits = 0
            pendingStatsHeartbeat = false
            persistStatsOutbox(context)
            statsRetryCount = 0
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
            return
        }

        if (!statsFailureLogged) {
            statsFailureLogged = true
            XposedBridge.log(
                "AppTimeLimiter: STATS_REPORT_FAILED package=$packageName error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "provider_rejected"}",
            )
            result.exceptionOrNull()?.let(XposedBridge::log)
            diagnostic(
                context,
                level = "WARN",
                event = "STATS_REPORT_FAILED",
                message = "使用统计写入失败，将自动重试；原因=${result.exceptionOrNull()?.javaClass?.simpleName ?: "provider_rejected"}",
            )
        }
        mainHandler.removeCallbacks(statsRetry)
        if (statsRetryCount < MAX_STATS_RETRIES) {
            val delayMs = STATS_RETRY_DELAYS_MS[statsRetryCount]
            statsRetryCount++
            mainHandler.postDelayed(statsRetry, delayMs)
        }
    }

    private fun stopTiming() {
        foregroundStartedAt = NOT_RUNNING
        activeActivity.clear()
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        mainHandler.removeCallbacks(scheduleDeadline)
        mainHandler.removeCallbacks(scheduleWarningDeadline)
        dismissWarning(resetForCurrentLimit = true)
        dismissScheduleWarning()
    }

    private fun showExitWarning() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
        if (scheduleWarningDialog?.isShowing == true) return
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
        if (warningShownForExtensionMs == grantedExtensionMs || warningDialog?.isShowing == true) return
        warningShownForExtensionMs = grantedExtensionMs

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${status.nextThresholdLabel}即将到期")
            .setMessage(exitWarningMessage(remainingMs, rule.extensionMillis, status.nextThresholdLabel))
            .setPositiveButton("延时 ${formatDuration(rule.extensionMillis)}") { _, _ ->
                grantExtension(activity, rule.extensionMillis)
            }
            .setNegativeButton("按时退出") { _, _ ->
                diagnostic(activity, event = "EXTENSION_SKIPPED", message = "用户选择按时退出")
            }
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            warningCountdown?.let(mainHandler::removeCallbacks)
            warningCountdown = null
            if (warningDialog === dialog) warningDialog = null
        }
        runCatching {
            dialog.show()
            warningDialog = dialog
            diagnostic(
                activity,
                event = "WARNING_SHOWN",
                message = "退出前提醒已显示；可延时=${rule.extensionMillis / 1000}s",
            )
            startWarningCountdown(dialog, activity, rule)
        }.onFailure {
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
        if (scheduleWarningDialog?.isShowing == true) return
        dismissWarning(resetForCurrentLimit = false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("即将进入不可用时段")
            .setMessage(scheduleWarningMessage(remainingMs))
            .setPositiveButton("知道了", null)
            .setCancelable(false)
            .create()
        dialog.setOnDismissListener {
            scheduleWarningCountdown?.let(mainHandler::removeCallbacks)
            scheduleWarningCountdown = null
            if (scheduleWarningDialog === dialog) scheduleWarningDialog = null
        }
        runCatching {
            dialog.show()
            scheduleWarningDialog = dialog
            diagnostic(
                activity,
                event = "SCHEDULE_WARNING_SHOWN",
                message = "不可用时段开始前提醒已显示",
            )
            startScheduleWarningCountdown(dialog, rule)
        }.onFailure {
            diagnostic(activity, level = "ERROR", event = "SCHEDULE_WARNING_FAILED", message = it.toString())
        }
    }

    private fun startScheduleWarningCountdown(dialog: AlertDialog, rule: HookRule) {
        val countdown = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || exitScheduled) return
                val decision = evaluateSchedule(rule)
                if (!decision.allowed) {
                    checkScheduleBoundary()
                    return
                }
                val remainingMs = decision.millisUntilTransition(ZonedDateTime.now()) ?: return
                dialog.setMessage(scheduleWarningMessage(remainingMs))
                if (remainingMs > 0L) mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
        scheduleWarningCountdown = countdown
        mainHandler.post(countdown)
    }

    private fun scheduleWarningMessage(remainingMs: Long): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return "将在 $seconds 秒后进入不可用时段并退出应用。\n时段限制不能通过延时按钮绕过。"
    }

    private fun startWarningCountdown(dialog: AlertDialog, activity: Activity, rule: HookRule) {
        val countdown = object : Runnable {
            override fun run() {
                if (!dialog.isShowing || exitScheduled) return
                val status = thresholdStatus(activity, rule)
                val remainingMs = status.remainingMillis
                dialog.setMessage(
                    exitWarningMessage(remainingMs, rule.extensionMillis, status.nextThresholdLabel),
                )
                if (remainingMs > 0L) mainHandler.postDelayed(this, COUNTDOWN_REFRESH_MS)
            }
        }
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
        warningCountdown?.let(mainHandler::removeCallbacks)
        warningCountdown = null
        warningDialog?.let { dialog -> runCatching { dialog.dismiss() } }
        warningDialog = null
        if (resetForCurrentLimit) warningShownForExtensionMs = Long.MIN_VALUE
    }

    private fun dismissScheduleWarning() {
        scheduleWarningCountdown?.let(mainHandler::removeCallbacks)
        scheduleWarningCountdown = null
        scheduleWarningDialog?.let { dialog -> runCatching { dialog.dismiss() } }
        scheduleWarningDialog = null
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
        thresholdLabel: String,
    ): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        return "$thresholdLabel 将在 $seconds 秒后到期并退出应用。\n" +
            "点击延时可同时延长两个已启用阈值 ${formatDuration(extensionMillis)}。"
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000L
        return if (seconds < 60L) "$seconds 秒" else "${seconds / 60L} 分钟"
    }

    private fun dailyUsedMillis(context: Context, rule: HookRule): Long {
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

    private fun thresholdStatus(context: Context, rule: HookRule): ThresholdStatus {
        val activeMs = activeSegmentMillis()
        val thresholds = buildList {
            if (rule.dailyEnabled) {
                add(
                    ThresholdRemaining(
                        label = "每日累计",
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.dailyLimitMillis),
                            dailyUsedMillis(context, rule),
                            activeMs,
                        ),
                    ),
                )
            }
            if (rule.perLaunchEnabled) {
                add(
                    ThresholdRemaining(
                        label = "单次打开",
                        remainingMillis = UsageMath.remainingMillis(
                            effectiveLimitMillis(rule.perLaunchLimitMillis),
                            perLaunchCommittedMs,
                            activeMs,
                        ),
                    ),
                )
            }
        }
        if (thresholds.isEmpty()) {
            return ThresholdStatus(Long.MAX_VALUE / 2L, false, "未启用", "未启用")
        }
        val earliestRemaining = UsageMath.earliestRemainingMillis(
            thresholds.map { it.remainingMillis },
        ) ?: Long.MAX_VALUE / 2L
        val earliest = thresholds.first { it.remainingMillis == earliestRemaining }
        val reachedLabels = thresholds.filter { it.remainingMillis == 0L }
            .joinToString("、") { it.label }
        return ThresholdStatus(
            remainingMillis = earliest.remainingMillis,
            reached = earliest.remainingMillis == 0L,
            reachedLabels = reachedLabels,
            nextThresholdLabel = earliest.label,
        )
    }

    private fun usageSummary(context: Context, rule: HookRule): String = buildList {
        if (rule.dailyEnabled) add("每日累计=${dailyUsedMillis(context, rule) / 1000.0}s")
        if (rule.perLaunchEnabled) add("单次累计=${perLaunchCommittedMs / 1000.0}s")
    }.joinToString("，")

    private fun writeDailyState(context: Context, rule: HookRule, usedMillis: Long) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DAY, dayToken())
            .putLong(KEY_VERSION, rule.version)
            .putLong(KEY_USED_MS, usedMillis.coerceAtLeast(0L))
            .apply()
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
                ).coerceAtLeast(1L) * 1000L,
                perLaunchEnabled = result.getBoolean(RuleContract.KEY_PER_LAUNCH_ENABLED, false),
                perLaunchLimitMillis = result.getLong(
                    RuleContract.KEY_PER_LAUNCH_LIMIT_SECONDS,
                    RuleRepository.DEFAULT_LIMIT_SECONDS,
                ).coerceAtLeast(1L) * 1000L,
                scheduleEnabled = result.getBoolean(RuleContract.KEY_SCHEDULE_ENABLED, false),
                scheduleMode = result.getString(RuleContract.KEY_SCHEDULE_MODE)
                    ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                    ?: ScheduleMode.BLOCK_DURING,
                scheduleWindows = ScheduleCodec.decode(
                    result.getString(RuleContract.KEY_SCHEDULE_WINDOWS),
                ),
                version = result.getLong(RuleContract.KEY_VERSION, 0L),
                exitWarningEnabled = result.getBoolean(RuleContract.KEY_EXIT_WARNING_ENABLED, true),
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
            return rule
        }

        if (!providerFailureLogged) {
            providerFailureLogged = true
            XposedBridge.log("AppTimeLimiter: PROVIDER_UNAVAILABLE package=$packageName, fallback=XSharedPreferences/local_cache")
        }
        if (reloadFallback) preferences.reload()
        val sharedRule = readXSharedRule()
        val cachedRule = readCachedRule(context)
        val rule = when {
            sharedRule != null && (cachedRule == null || sharedRule.version >= cachedRule.version) -> {
                cacheRule(context, sharedRule)
                sharedRule
            }
            cachedRule != null -> cachedRule
            else -> unavailableRule()
        }
        diagnosticsEnabled = rule.diagnosticsEnabled
        return rule
    }

    private fun readXSharedRule(): HookRule? {
        val prefix = "rule.$packageName."
        val version = preferences.getLong("${prefix}version", Long.MIN_VALUE)
        if (version == Long.MIN_VALUE) return null
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
            enabled = legacyEnabled && (dailyEnabled || perLaunchEnabled || scheduleEnabled),
            dailyEnabled = dailyEnabled,
            dailyLimitMillis = (if (dailyLimitSeconds >= 0L) dailyLimitSeconds else legacyLimitSeconds)
                .coerceAtLeast(1L) * 1000L,
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitMillis = (if (perLaunchLimitSeconds >= 0L) {
                perLaunchLimitSeconds
            } else {
                legacyLimitSeconds
            }).coerceAtLeast(1L) * 1000L,
            scheduleEnabled = scheduleEnabled,
            scheduleMode = preferences.getString(
                "${prefix}schedule_mode",
                ScheduleMode.BLOCK_DURING.name,
            )?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(
                preferences.getString("${prefix}schedule_windows", null),
            ),
            version = version,
            exitWarningEnabled = preferences.getBoolean(RuleRepository.KEY_EXIT_WARNING_ENABLED, true),
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
            rule.perLaunchEnabled,
            rule.perLaunchLimitMillis,
            rule.scheduleEnabled,
            rule.scheduleMode.name,
            ScheduleCodec.encode(rule.scheduleWindows),
            rule.version,
            rule.exitWarningEnabled,
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
            .putBoolean(CACHE_PER_LAUNCH_ENABLED, rule.perLaunchEnabled)
            .putLong(CACHE_PER_LAUNCH_LIMIT_MS, rule.perLaunchLimitMillis)
            .putBoolean(CACHE_SCHEDULE_ENABLED, rule.scheduleEnabled)
            .putString(CACHE_SCHEDULE_MODE, rule.scheduleMode.name)
            .putString(CACHE_SCHEDULE_WINDOWS, ScheduleCodec.encode(rule.scheduleWindows))
            .putLong(CACHE_RULE_VERSION, rule.version)
            .putBoolean(CACHE_EXIT_WARNING_ENABLED, rule.exitWarningEnabled)
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
            ).coerceAtLeast(1_000L),
            perLaunchEnabled = prefs.getBoolean(CACHE_PER_LAUNCH_ENABLED, false),
            perLaunchLimitMillis = prefs.getLong(
                CACHE_PER_LAUNCH_LIMIT_MS,
                RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
            ).coerceAtLeast(1_000L),
            scheduleEnabled = prefs.getBoolean(CACHE_SCHEDULE_ENABLED, false),
            scheduleMode = prefs.getString(CACHE_SCHEDULE_MODE, ScheduleMode.BLOCK_DURING.name)
                ?.let { runCatching { ScheduleMode.valueOf(it) }.getOrNull() }
                ?: ScheduleMode.BLOCK_DURING,
            scheduleWindows = ScheduleCodec.decode(prefs.getString(CACHE_SCHEDULE_WINDOWS, null)),
            version = prefs.getLong(CACHE_RULE_VERSION, 0L),
            exitWarningEnabled = prefs.getBoolean(CACHE_EXIT_WARNING_ENABLED, true),
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
        perLaunchEnabled = false,
        perLaunchLimitMillis = RuleRepository.DEFAULT_LIMIT_SECONDS * 1000L,
        scheduleEnabled = false,
        scheduleMode = ScheduleMode.BLOCK_DURING,
        scheduleWindows = emptyList(),
        version = Long.MIN_VALUE,
        exitWarningEnabled = true,
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
        val perLaunchEnabled: Boolean,
        val perLaunchLimitMillis: Long,
        val scheduleEnabled: Boolean,
        val scheduleMode: ScheduleMode,
        val scheduleWindows: List<ScheduleWindow>,
        val version: Long,
        val exitWarningEnabled: Boolean,
        val extensionMillis: Long,
        val diagnosticsEnabled: Boolean,
        val usageStatsEnabled: Boolean,
        val source: String,
    )

    private data class ThresholdRemaining(
        val label: String,
        val remainingMillis: Long,
    )

    private data class ThresholdStatus(
        val remainingMillis: Long,
        val reached: Boolean,
        val reachedLabels: String,
        val nextThresholdLabel: String,
    )

    private companion object {
        const val NOT_RUNNING = -1L
        const val STATE_PREFS = "__app_time_limiter_state__"
        const val RULE_CACHE_PREFS = "__app_time_limiter_rule_cache__"
        const val STATS_OUTBOX_PREFS = "__app_time_limiter_stats_outbox__"
        const val KEY_DAY = "day"
        const val KEY_VERSION = "rule_version"
        const val KEY_USED_MS = "used_ms"
        const val CACHE_PRESENT = "present"
        const val CACHE_SIGNATURE = "signature"
        const val CACHE_ENABLED = "enabled"
        const val CACHE_DAILY_ENABLED = "daily_enabled"
        const val CACHE_DAILY_LIMIT_MS = "daily_limit_ms"
        const val CACHE_PER_LAUNCH_ENABLED = "per_launch_enabled"
        const val CACHE_PER_LAUNCH_LIMIT_MS = "per_launch_limit_ms"
        const val CACHE_SCHEDULE_ENABLED = "schedule_enabled"
        const val CACHE_SCHEDULE_MODE = "schedule_mode"
        const val CACHE_SCHEDULE_WINDOWS = "schedule_windows"
        const val CACHE_RULE_VERSION = "rule_version"
        const val CACHE_EXIT_WARNING_ENABLED = "exit_warning_enabled"
        const val CACHE_EXTENSION_MS = "extension_ms"
        const val CACHE_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
        const val CACHE_USAGE_STATS_ENABLED = "usage_stats_enabled"
        const val OUTBOX_PENDING = "pending"
        const val OUTBOX_DURATION_MS = "duration_ms"
        const val OUTBOX_LAUNCHES = "launches"
        const val OUTBOX_LIMIT_HITS = "limit_hits"
        const val LOGGABLE_SEGMENT_MS = 1_000L
        const val EXIT_DELAY_MS = 350L
        const val WARNING_LEAD_MS = 5_000L
        const val COUNTDOWN_REFRESH_MS = 250L
        const val SCHEDULE_RECHECK_MAX_MS = 60_000L
        const val MAX_STATS_RETRIES = 3
        val STATS_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L)
        const val MAX_TOTAL_EXTENSION_MS = 24L * 60L * 60L * 1000L
    }
}
