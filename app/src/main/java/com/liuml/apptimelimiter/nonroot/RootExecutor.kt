package com.liuml.apptimelimiter.nonroot

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.core.RestrictionExecutionResult
import com.liuml.apptimelimiter.core.RestrictionExecutor
import com.liuml.apptimelimiter.core.RestrictionRequest
import com.liuml.apptimelimiter.data.RuleRepository
import java.util.concurrent.TimeUnit

class RootExecutor(context: Context) : RestrictionExecutor {
    private val appContext = context.applicationContext
    override fun isAvailable(): Boolean = runCommand("id", 1_500L) == 0

    override fun execute(request: RestrictionRequest): RestrictionExecutionResult {
        if (!isValidRequest(request) || !claim(request.incidentId)) {
            return if (request.incidentId.isNotBlank() && wasExecuted(request.incidentId)) {
                RestrictionExecutionResult.ALREADY_EXECUTED
            } else {
                RestrictionExecutionResult.REJECTED
            }
        }
        val command = "am force-stop --user ${request.userId} ${request.packageName}"
        val exitCode = runCommand(command, COMMAND_TIMEOUT_MILLIS)
        if (exitCode != 0) return RestrictionExecutionResult.FALLBACK_REQUIRED
        return if (isStillRunning(request.packageName) != false) {
            RestrictionExecutionResult.FALLBACK_REQUIRED
        } else {
            RestrictionExecutionResult.EXECUTED
        }
    }

    override fun cancel(requestId: String) = Unit

    private fun isValidRequest(request: RestrictionRequest): Boolean {
        if (!PackageNamePolicy.isValid(request.packageName) || request.userId < 0) return false
        if (request.foregroundPackage != request.packageName || request.incidentId.isBlank()) return false
        val info = runCatching {
            appContext.packageManager.getApplicationInfo(request.packageName, 0)
        }.getOrNull() ?: return false
        if (request.packageName == appContext.packageName) return false
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            return false
        }
        val home = appContext.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return ShizukuTargetPolicy.isAllowed(
            packageName = request.packageName,
            ownPackageName = appContext.packageName,
            configuredPackages = RuleRepository(appContext).configuredPackages(),
            systemOrUpdatedSystemApp = false,
            launcherPackage = home,
            protectedPackages = setOf("android", "com.android.systemui", "com.android.permissioncontroller"),
        )
    }

    /** A hidden process is not reliably exposed by ActivityManager; verify in the same su shell. */
    private fun isStillRunning(packageName: String): Boolean? = when (
        runCommand("pidof $packageName >/dev/null", PROCESS_CHECK_TIMEOUT_MILLIS)
    ) {
        0 -> true
        1 -> false
        else -> null
    }

    private fun runCommand(command: String, timeoutMillis: Long): Int = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return -1
        }
        process.inputStream.use { input ->
            val buffer = ByteArray(256)
            input.read(buffer)
        }
        process.exitValue()
    }.getOrDefault(-1)

    private fun claim(id: String): Boolean {
        return synchronized(EXECUTION_LOCK) {
            if (id.isBlank() || executed.contains(id)) return@synchronized false
            executed += id
            while (executed.size > MAX_DEDUPE_ENTRIES) executed.remove(executed.first())
            true
        }
    }

    private fun wasExecuted(id: String): Boolean = synchronized(EXECUTION_LOCK) {
        executed.contains(id)
    }

    companion object {
        private const val COMMAND_TIMEOUT_MILLIS = 3_000L
        private const val PROCESS_CHECK_TIMEOUT_MILLIS = 1_000L
        private const val MAX_DEDUPE_ENTRIES = 128
        private val EXECUTION_LOCK = Any()
        private val executed = LinkedHashSet<String>()
    }
}
