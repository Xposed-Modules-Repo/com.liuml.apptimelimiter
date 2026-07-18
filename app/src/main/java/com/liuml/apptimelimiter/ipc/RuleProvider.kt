package com.liuml.apptimelimiter.ipc

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

class RuleProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context ?: return Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }
        return when (method) {
            RuleContract.METHOD_GET_RULE -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                val rule = RuleRepository(appContext).getRule(packageName)
                val settings = RuleRepository(appContext).getGlobalSettings()
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
                    putLong(RuleContract.KEY_VERSION, rule.version)
                    putBoolean(RuleContract.KEY_EXIT_WARNING_ENABLED, settings.exitWarningEnabled)
                    putLong(RuleContract.KEY_EXTENSION_SECONDS, settings.extensionSeconds)
                    putBoolean(RuleContract.KEY_DIAGNOSTICS_ENABLED, settings.diagnosticsEnabled)
                    putBoolean(RuleContract.KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
                }
            }

            RuleContract.METHOD_APPEND_LOG -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                DiagnosticsRepository(appContext).append(
                    level = extras?.getString(RuleContract.KEY_LEVEL).orEmpty().ifBlank { "INFO" },
                    packageName = packageName,
                    event = extras?.getString(RuleContract.KEY_EVENT).orEmpty().ifBlank { "UNKNOWN" },
                    message = extras?.getString(RuleContract.KEY_MESSAGE).orEmpty(),
                )
                Bundle().apply { putBoolean(RuleContract.KEY_OK, true) }
            }

            RuleContract.METHOD_RECORD_USAGE -> {
                val packageName = arg.orEmpty()
                if (!isCallerAllowed(packageName)) return denied()
                val persisted = UsageStatsRepository(appContext).record(
                    packageName = packageName,
                    durationMillis = extras?.getLong(RuleContract.KEY_DURATION_MS, 0L) ?: 0L,
                    launchIncrement = extras?.getInt(RuleContract.KEY_LAUNCH_INCREMENT, 0) ?: 0,
                    limitHitIncrement = extras?.getInt(
                        RuleContract.KEY_LIMIT_HIT_INCREMENT,
                        0,
                    ) ?: 0,
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
        return context?.packageManager?.getPackagesForUid(callingUid).orEmpty().contains(requestedPackage)
    }

    private fun denied() = Bundle().apply { putBoolean(RuleContract.KEY_OK, false) }

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
