package com.liuml.apptimelimiter.ipc

import android.app.AppOpsManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.ScheduleCodec
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository

class RuleProvider : ContentProvider() {
    private var deviceUsageStatsRepository: DeviceUsageStatsRepository? = null

    override fun onCreate(): Boolean {
        context?.let { deviceUsageStatsRepository = DeviceUsageStatsRepository(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context ?: return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
        val ruleRepository = RuleRepository(appContext)
        return when (method) {
            RuleContract.METHOD_GET_RULE -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                val rule = ruleRepository.getRule(packageName)
                val settings = ruleRepository.getGlobalSettings()
                val systemUsageRepository = deviceUsageStatsRepository
                    ?: DeviceUsageStatsRepository(appContext).also {
                        deviceUsageStatsRepository = it
                    }
                val systemTodayUsedMillis = if (rule.dailyEnabled) {
                    systemUsageRepository.todayDuration(packageName) ?: -1L
                } else {
                    -1L
                }
                Bundle().apply {
                    putBoolean(RuleContract.KEY_OK, true)
                    putBoolean(RuleContract.KEY_ENABLED, rule.enabled)
                    putBoolean(RuleContract.KEY_DAILY_ENABLED, rule.dailyEnabled)
                    putLong(RuleContract.KEY_DAILY_LIMIT_SECONDS, rule.dailyLimitSeconds)
                    putBoolean(RuleContract.KEY_PER_LAUNCH_ENABLED, rule.perLaunchEnabled)
                    putLong(RuleContract.KEY_PER_LAUNCH_LIMIT_SECONDS, rule.perLaunchLimitSeconds)
                    putBoolean(RuleContract.KEY_SCHEDULE_ENABLED, rule.scheduleEnabled)
                    putString(RuleContract.KEY_SCHEDULE_MODE, rule.scheduleMode.name)
                    putString(
                        RuleContract.KEY_SCHEDULE_WINDOWS,
                        ScheduleCodec.encode(rule.scheduleWindows),
                    )
                    putBoolean(RuleContract.KEY_COOLDOWN_ENABLED, rule.cooldownEnabled)
                    putLong(RuleContract.KEY_COOLDOWN_SECONDS, rule.cooldownSeconds)
                    putLong(RuleContract.KEY_VERSION, rule.version)
                    putBoolean(RuleContract.KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
                    putLong(RuleContract.KEY_EXTENSION_SECONDS, settings.extensionSeconds)
                    putBoolean(RuleContract.KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
                    putBoolean(RuleContract.KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
                    putLong(RuleContract.KEY_SYSTEM_TODAY_USED_MS, systemTodayUsedMillis)
                }
            }

            RuleContract.METHOD_APPEND_LOG -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                if (!isConfiguredPackage(ruleRepository, packageName)) return denied()
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
                val persisted = UsageStatsRepository(appContext).record(
                    packageName = packageName,
                    durationMillis = (extras?.getLong(RuleContract.KEY_DURATION_MS, 0L) ?: 0L)
                        .coerceIn(0L, MAX_REPORTED_DURATION_MS),
                    launchIncrement = (extras?.getInt(RuleContract.KEY_LAUNCH_INCREMENT, 0) ?: 0)
                        .coerceIn(0, MAX_COUNTER_INCREMENT),
                    limitHitIncrement = extras?.getInt(
                        RuleContract.KEY_LIMIT_HIT_INCREMENT,
                        0,
                    )?.coerceIn(0, MAX_COUNTER_INCREMENT) ?: 0,
                    hookVersionCode = extras?.getInt(
                        RuleContract.KEY_HOOK_VERSION_CODE,
                        0,
                    )?.coerceIn(0, MAX_HOOK_VERSION_CODE) ?: 0,
                    dayToken = extras?.getString(RuleContract.KEY_DAY_TOKEN),
                )
                Bundle().apply { putBoolean(RuleContract.KEY_OK, persisted) }
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

    private fun isConfiguredPackage(repository: RuleRepository, packageName: String): Boolean =
        Binder.getCallingUid() == Process.myUid() || packageName in repository.configuredPackages()

    private fun denied() = Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }

    private companion object {
        const val MAX_REPORTED_DURATION_MS = 24L * 60L * 60L * 1000L
        const val MAX_COUNTER_INCREMENT = 100
        const val MAX_HOOK_VERSION_CODE = 1_000_000
        const val MAX_EVENT_LENGTH = 80
        const val MAX_MESSAGE_LENGTH = 2_000
        val ALLOWED_LOG_LEVELS = setOf("DEBUG", "INFO", "WARN", "ERROR")
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
