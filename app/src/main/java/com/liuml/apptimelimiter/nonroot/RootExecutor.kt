package com.liuml.apptimelimiter.nonroot

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import com.liuml.apptimelimiter.core.PackageNamePolicy
import com.liuml.apptimelimiter.core.RestrictionExecutionResult
import com.liuml.apptimelimiter.core.RestrictionExecutor
import com.liuml.apptimelimiter.core.RestrictionRequest
import com.liuml.apptimelimiter.core.RootProcessVerificationPolicy
import com.liuml.apptimelimiter.core.RootExecutionSafetyPolicy
import com.liuml.apptimelimiter.data.RuleRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.io.ByteArrayOutputStream

class RootExecutor(context: Context) : RestrictionExecutor {
    private val appContext = context.applicationContext
    override fun isAvailable(): Boolean = runCommand("id", 1_500L) == 0

    /** User-initiated authorization needs time for the root manager's confirmation dialog. */
    fun requestAuthorization(): Boolean = runCommand("id", 30_000L) == 0

    override fun execute(request: RestrictionRequest): RestrictionExecutionResult {
        val diagnostics = com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository(appContext)
        diagnostics.recordIncident(request.packageName, request.incidentId, "ROOT_FORCE_STOP_REQUESTED", "REQUESTED")
        return executeValidated(request).also { result ->
            diagnostics.recordIncident(request.packageName, request.incidentId, "ROOT_FORCE_STOP_RESULT", result.name)
        }
    }

    private fun executeValidated(request: RestrictionRequest): RestrictionExecutionResult {
        // The interface is synchronous: never wait for su or PackageManager on the UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return RestrictionExecutionResult.FALLBACK_REQUIRED
        RootExecutionSafetyPolicy.validate { isValidRequest(request) }?.let { return it }
        val target = resolveTarget(request) ?: return RestrictionExecutionResult.FALLBACK_REQUIRED
        val key = "${request.userId}:${request.packageName}:${request.incidentId}"
        return RootExecutionSafetyPolicy.execute(executions, key) {
            val command = "am force-stop --user ${request.userId} ${request.packageName}"
            val exitCode = runCommand(command, COMMAND_TIMEOUT_MILLIS)
            if (exitCode != 0 || verifyProcesses(target) != RootProcessVerificationPolicy.Result.ABSENT) {
                RestrictionExecutionResult.FALLBACK_REQUIRED
            } else {
                RestrictionExecutionResult.EXECUTED
            }
        }
    }

    override fun cancel(requestId: String) = Unit

    private fun isValidRequest(request: RestrictionRequest): Boolean {
        if (!PackageNamePolicy.isValid(request.packageName) || request.userId < 0) return false
        if (request.foregroundPackage != request.packageName || request.incidentId.isBlank()) return false
        val info = appContext.packageManager.getApplicationInfo(request.packageName, 0)
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

    private fun resolveTarget(request: RestrictionRequest): RootProcessVerificationPolicy.Target? = runCatching {
        val info = appContext.packageManager.getPackageInfo(
            request.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
                PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val application = info.applicationInfo ?: return null
        val names = buildSet {
            add(application.processName ?: request.packageName)
            info.activities?.forEach { add(it.processName ?: application.processName ?: request.packageName) }
            info.receivers?.forEach { add(it.processName ?: application.processName ?: request.packageName) }
            info.services?.forEach { add(it.processName ?: application.processName ?: request.packageName) }
            info.providers?.forEach { add(it.processName ?: application.processName ?: request.packageName) }
        }.map { if (it.startsWith(':')) request.packageName + it else it }.toSet()
        // Never synthesize another user's UID from this user's package metadata.
        RootProcessVerificationPolicy.Target(request.packageName, request.userId, application.uid, names)
            .takeIf { RootProcessVerificationPolicy.isValid(it) }
    }.getOrNull()

    /** Fixed read-only command; no package, process name or UID is interpolated into the shell. */
    private fun verifyProcesses(target: RootProcessVerificationPolicy.Target): RootProcessVerificationPolicy.Result {
        if (!snapshotPermit.tryAcquire()) return RootProcessVerificationPolicy.Result.UNKNOWN
        var process: Process? = null
        var readerStarted = false
        return try {
            val deadline = SystemClock.elapsedRealtime() + PROCESS_CHECK_TIMEOUT_MILLIS
            val child = ProcessBuilder("su", "-c", RootProcessVerificationPolicy.COMMAND)
                .redirectErrorStream(true).start()
            process = child
            val capture = FutureTask<String?> {
                try {
                    child.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > RootProcessVerificationPolicy.MAX_OUTPUT_BYTES) {
                                return@FutureTask null
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                } finally {
                    snapshotPermit.release()
                }
            }
            Thread(capture, "root-process-snapshot").apply { isDaemon = true; start() }
            readerStarted = true
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (!child.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                RootProcessVerificationPolicy.Result.UNKNOWN
            } else {
                val output = capture.get(
                    (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS,
                )
                RootProcessVerificationPolicy.verify(target, child.exitValue(), output)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            RootProcessVerificationPolicy.Result.UNKNOWN
        } catch (_: Exception) {
            RootProcessVerificationPolicy.Result.UNKNOWN
        } finally {
            runCatching { process?.destroyForcibly() }
            // A stuck pipe must not spawn unbounded readers on subsequent attempts.
            // Do not cancel the FutureTask before it starts: its finally releases the permit.
            if (!readerStarted) snapshotPermit.release()
        }
    }

    private fun runCommand(command: String, timeoutMillis: Long): Int =
        RootExecutionSafetyPolicy.runCommand(timeoutMillis) {
            ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .redirectOutput(java.io.File("/dev/null"))
            .start()
        }

    companion object {
        private const val COMMAND_TIMEOUT_MILLIS = 3_000L
        private const val PROCESS_CHECK_TIMEOUT_MILLIS = 1_000L
        private val snapshotPermit = Semaphore(1)
        private val executions = com.liuml.apptimelimiter.core.RestrictionExecutionLedger()
    }
}
