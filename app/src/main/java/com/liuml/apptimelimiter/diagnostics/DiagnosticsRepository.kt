package com.liuml.apptimelimiter.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.nonroot.NonRootProtectionStatusRepository
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionRepository
import com.liuml.apptimelimiter.security.ChildLockRepository
import com.liuml.apptimelimiter.statistics.UsageStatsRepository
import com.liuml.apptimelimiter.xposedstatus.XposedStatusRepository
import com.liuml.apptimelimiter.xposedstatus.ScopeSyncCoordinator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val logFile = File(appContext.filesDir, FILE_NAME)

    fun append(level: String, packageName: String, event: String, message: String, incidentId: String? = null) {
        if (!incidentId.isNullOrBlank()) {
            // Use explicit correlation only; never infer identities or reasons from free-form logs.
            recordIncident(packageName, incidentId, event, level)
        }
        synchronized(FILE_LOCK) {
            runCatching {
                rotateIfNeeded()
                val timestamp = SimpleDateFormat(
                    "MM-dd HH:mm:ss.SSS",
                    Locale.ROOT,
                ).format(Date())
                val cleanLevel = clean(level, MAX_LEVEL_LENGTH).ifBlank { "INFO" }
                val cleanPackage = clean(packageName, MAX_PACKAGE_LENGTH)
                val cleanEvent = clean(event, MAX_EVENT_LENGTH)
                val cleanMessage = clean(message, MAX_MESSAGE_LENGTH)
                logFile.appendText(
                    "$timestamp\t$cleanLevel\t$cleanPackage\t$cleanEvent\t$cleanMessage\n",
                )
            }.onFailure {
                Log.w(LOG_TAG, "Unable to append diagnostics", it)
            }
        }
    }

    fun recordIncident(packageName: String, incidentId: String, stage: String, result: String, reason: String = "") {
        val at = System.currentTimeMillis()
        val eventKey = java.util.UUID.randomUUID().toString()
        val generation = TIMELINE_GENERATION.get()
        runCatching {
            TIMELINE_WRITER.execute {
                synchronized(TIMELINE_LOCK) {
                    if (generation == TIMELINE_GENERATION.get() &&
                        runCatching { RuleRepository(appContext).getGlobalSettings().diagnosticsEnabled }.getOrDefault(false)) {
                        DiagnosticTimelineRepository(appContext).record(packageName, incidentId, stage, result, reason, eventKey, at)
                    }
                }
            }
        }
    }

    fun timeline(): DiagnosticTimelineRepository {
        DiagnosticTimelineDrain.drain(appContext)
        return DiagnosticTimelineRepository(appContext)
    }

    fun appendRateLimited(
        level: String,
        packageName: String,
        event: String,
        stateSignature: String,
        message: String,
        windowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
    ) {
        val key = "$packageName|$event|$stateSignature"
        if (!EVENT_LIMITER.shouldAccept(key, System.currentTimeMillis(), windowMillis)) return
        append(level, packageName, event, message)
    }

    fun readLatest(limit: Int = 200): List<String> = synchronized(FILE_LOCK) {
        runCatching {
            if (!logFile.exists()) emptyList()
            else logFile.readLines().takeLast(limit.coerceIn(1, MAX_READ_LINES)).asReversed()
        }.getOrElse {
            Log.w(LOG_TAG, "Unable to read diagnostics", it)
            emptyList()
        }
    }

    fun clear() {
        synchronized(FILE_LOCK) {
            if (logFile.exists()) logFile.writeText("")
        }
        synchronized(TIMELINE_LOCK) {
            TIMELINE_GENERATION.incrementAndGet()
            DiagnosticTimelineDrain.clear(appContext)
        }
    }

    fun exportForFeedback(): File {
        // Never hold the text-file lock while querying control state/outbox: PIN writes may log.
        val header = exportHeader()
        val events = runCatching { timeline().exportText() }.getOrDefault("")
        // Keep the export under Context.cacheDir so the FileProvider cache-path grants it.
        val exportDir = File(appContext.cacheDir, "feedback").apply { mkdirs() }
        val contents = synchronized(FILE_LOCK) {
            if (logFile.exists() && logFile.length() > 0L) {
                logFile.readText()
            } else {
                "暂无诊断日志。请确认已在设置中开启诊断日志，并复现问题。\n"
            }
        }
        return synchronized(EXPORT_LOCK) {
            File(exportDir, "app-time-limiter-diagnostics.txt").also { target ->
                target.writeText(header + contents + events)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return
        val retained = logFile.readLines().takeLast(RETAINED_LINES)
        logFile.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun clean(value: String, maxLength: Int): String =
        value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .take(maxLength)

    private fun exportHeader(): String = buildString {
        val ruleRepository = runCatching { RuleRepository(appContext) }.getOrNull()
        val settings = runCatching { ruleRepository?.getGlobalSettings() }.getOrNull()
        val accessibility = runCatching {
            NonRootProtectionStatusRepository.readAccessibilityRuntimeSnapshot(appContext)
        }.getOrNull()
        val nonRootHealth = runCatching {
            NonRootProtectionStatusRepository.get(appContext).healthSnapshot.value
        }.getOrNull()
        val shizukuState = runCatching {
            ShizukuExecutionRepository.get(appContext).state.value
        }.getOrNull()
        val xposed = runCatching { XposedStatusRepository.instance.snapshot.value }.getOrNull()
        val scopeSync = runCatching {
            ScopeSyncCoordinator.get(appContext).snapshot.value
        }.getOrNull()
        val hookSummaries = runCatching {
            val packages = ruleRepository?.configuredPackages().orEmpty()
            UsageStatsRepository(appContext).summariesToday(packages)
        }.getOrDefault(emptyList())
        val hasCurrentHookHeartbeat = hookSummaries.any {
            it.hookVersionCode >= BuildConfig.VERSION_CODE && it.lastHookEventAtMillis > 0L
        }
        val privateChildLockReady = runCatching {
            ChildLockRepository(appContext).isEnabled()
        }.getOrDefault(false)
        appendLine("# Time Stop diagnostics")
        appendLine(
            "# generated=" +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date()),
        )
        appendLine(
            "# app=${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}",
        )
        appendLine(
            "# android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "device=${clean(Build.MANUFACTURER, 80)}/${clean(Build.MODEL, 120)}",
        )
        appendLine("# locale=${appContext.resources.configuration.locales[0]}")
        appendLine(
            "# protection_mode=${settings?.protectionMode ?: "UNKNOWN"} " +
                "generation=${settings?.protectionModeGeneration ?: -1L} " +
                "child_lock=${settings?.childLockEnabled ?: false} " +
                "child_lock_pin_ready=$privateChildLockReady",
        )
        appendLine(
            "# accessibility=${accessibility?.state ?: "UNKNOWN"} " +
                "configured=${accessibility?.systemConfigured ?: false} " +
                "connected=${accessibility?.serviceConnected ?: false} " +
                "usage_access=${accessibility?.usageAccessGranted ?: false}",
        )
        appendLine("# shizuku=${shizukuState ?: "UNKNOWN"}")
        appendLine(
            "# xposed_source=${when {
                xposed?.connected == true -> "SERVICE"
                hasCurrentHookHeartbeat -> "HEARTBEAT_FALLBACK"
                else -> "UNAVAILABLE"
            }} " +
                "connected=${xposed?.connected ?: false} stale=${xposed?.stale ?: false} " +
                "snapshot_age_ms=${xposed?.capturedAtMillis?.takeIf { it > 0L }?.let { captured ->
                    (System.currentTimeMillis() - captured).coerceAtLeast(0L)
                } ?: -1L}",
        )
        appendLine(
            "# hook_versions=" + hookSummaries
                .filter { it.hookVersionCode > 0 }
                .sortedBy { it.packageName }
                .joinToString(",") {
                    "${clean(it.packageName, 120)}:${it.hookVersionCode}"
                }
                .ifBlank { "NONE" },
        )
        appendLine(
            "# scope_sync_connected=${scopeSync?.connected ?: false} " +
                "stale=${scopeSync?.stale ?: false} syncing=${scopeSync?.syncing ?: false} " +
                "desired=${scopeSync?.desiredPackages?.size ?: 0} " +
                "actual=${scopeSync?.actualPackages?.size ?: 0} " +
                "pending=${scopeSync?.pendingPackages?.size ?: 0} " +
                "failed=${scopeSync?.failedPackages?.size ?: 0}",
        )
        val breakPage = nonRootHealth?.breakPageCompatibility
        appendLine(
            "# break_page_last_failure_at=${breakPage?.lastFailureAtMillis ?: 0L} " +
                "stage=${breakPage?.lastFailureStage ?: "NONE"} " +
                "manufacturer=${clean(breakPage?.lastFailureManufacturer.orEmpty(), 80)}",
        )
        appendLine("# Times are local wall-clock timestamps; newest events are at the bottom.")
    }

    private companion object {
        const val LOG_TAG = "TimeStopDiagnostics"
        const val FILE_NAME = "diagnostics.log"
        const val MAX_FILE_BYTES = 256L * 1024L
        const val RETAINED_LINES = 500
        const val MAX_READ_LINES = 2_000
        const val MAX_LEVEL_LENGTH = 12
        const val MAX_PACKAGE_LENGTH = 256
        const val MAX_EVENT_LENGTH = 96
        const val MAX_MESSAGE_LENGTH = 2_048
        const val DEFAULT_DEDUPE_WINDOW_MILLIS = 5_000L
        val FILE_LOCK = Any()
        val EXPORT_LOCK = Any()
        val EVENT_LIMITER = DiagnosticEventLimiter()
        val TIMELINE_LOCK = Any()
        val TIMELINE_GENERATION = java.util.concurrent.atomic.AtomicLong()
        val TIMELINE_WRITER = java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(128),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "TimeStopTimeline").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
