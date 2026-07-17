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
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.ipc.RuleContract
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Calendar

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

    private val deadline = Runnable { checkDeadline() }
    private val warningDeadline = Runnable { showExitWarning() }

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

        val ruleSummary = "${rule.enabled}/${rule.dailyEnabled}/${rule.dailyLimitMillis}/${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis}/${rule.version}/${rule.exitWarningEnabled}/${rule.extensionMillis}/${rule.diagnosticsEnabled}/${rule.source}"
        if (lastRuleSummary != ruleSummary) {
            lastRuleSummary = ruleSummary
            diagnostic(
                activity,
                event = "RULE_READ",
                message = "enabled=${rule.enabled}, daily=${rule.dailyEnabled}/${rule.dailyLimitMillis / 1000}s, perLaunch=${rule.perLaunchEnabled}/${rule.perLaunchLimitMillis / 1000}s, warning=${rule.exitWarningEnabled}, extension=${rule.extensionMillis / 1000}s, source=${rule.source}",
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

        activeActivity = WeakReference(activity)
        val startedNow = foregroundStartedAt == NOT_RUNNING
        if (startedNow) foregroundStartedAt = SystemClock.elapsedRealtime()
        val remainingMs = scheduleDeadline(activity, rule)
        if (startedNow && !exitScheduled) {
            diagnostic(
                activity,
                event = "TIMER_START",
                message = "开始前台计时，最早阈值剩余=${remainingMs / 1000.0}s",
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
        dismissWarning(resetForCurrentLimit = true)
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

    private fun forceExit(activity: Activity, rule: HookRule, status: ThresholdStatus) {
        if (exitScheduled) return
        exitScheduled = true
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

        diagnostic(
            activity,
            level = "WARN",
            event = "LIMIT_REACHED",
            message = "达到${status.reachedLabels}阈值（本次已延时 ${grantedExtensionMs / 1000}s），准备结束 pid=${Process.myPid()}",
        )
        runCatching {
            Toast.makeText(activity, "已达到使用时长，应用即将退出", Toast.LENGTH_LONG).show()
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

    private fun stopTiming() {
        foregroundStartedAt = NOT_RUNNING
        activeActivity.clear()
        mainHandler.removeCallbacks(deadline)
        mainHandler.removeCallbacks(warningDeadline)
        dismissWarning(resetForCurrentLimit = true)
    }

    private fun showExitWarning() {
        val activity = activeActivity.get() ?: return
        if (activity.isFinishing || activity.isDestroyed || exitScheduled) return
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
                source = "provider",
            )
            diagnosticsEnabled = rule.diagnosticsEnabled
            return rule
        }

        if (!providerFailureLogged) {
            providerFailureLogged = true
            XposedBridge.log("AppTimeLimiter: PROVIDER_UNAVAILABLE package=$packageName, fallback=XSharedPreferences")
        }
        if (reloadFallback) preferences.reload()
        val prefix = "rule.$packageName."
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
        val rule = HookRule(
            enabled = legacyEnabled && (dailyEnabled || perLaunchEnabled),
            dailyEnabled = dailyEnabled,
            dailyLimitMillis = (if (dailyLimitSeconds >= 0L) dailyLimitSeconds else legacyLimitSeconds)
                .coerceAtLeast(1L) * 1000L,
            perLaunchEnabled = perLaunchEnabled,
            perLaunchLimitMillis = (if (perLaunchLimitSeconds >= 0L) {
                perLaunchLimitSeconds
            } else {
                legacyLimitSeconds
            }).coerceAtLeast(1L) * 1000L,
            version = preferences.getLong("${prefix}version", 0L),
            exitWarningEnabled = preferences.getBoolean(RuleRepository.KEY_EXIT_WARNING_ENABLED, true),
            extensionMillis = preferences.getLong(
                RuleRepository.KEY_EXTENSION_SECONDS,
                RuleRepository.DEFAULT_EXTENSION_SECONDS,
            ).coerceIn(
                RuleRepository.MIN_EXTENSION_SECONDS,
                RuleRepository.MAX_EXTENSION_SECONDS,
            ) * 1000L,
            diagnosticsEnabled = preferences.getBoolean(RuleRepository.KEY_DIAGNOSTICS_ENABLED, true),
            source = "xsharedpreferences",
        )
        diagnosticsEnabled = rule.diagnosticsEnabled
        return rule
    }

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
        val version: Long,
        val exitWarningEnabled: Boolean,
        val extensionMillis: Long,
        val diagnosticsEnabled: Boolean,
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
        const val KEY_DAY = "day"
        const val KEY_VERSION = "rule_version"
        const val KEY_USED_MS = "used_ms"
        const val LOGGABLE_SEGMENT_MS = 1_000L
        const val EXIT_DELAY_MS = 350L
        const val WARNING_LEAD_MS = 5_000L
        const val COUNTDOWN_REFRESH_MS = 250L
        const val MAX_TOTAL_EXTENSION_MS = 24L * 60L * 60L * 1000L
    }
}
