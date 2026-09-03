package com.liuml.apptimelimiter.xposedstatus

import android.content.Context
import com.liuml.apptimelimiter.BuildConfig
import com.liuml.apptimelimiter.core.DesiredScopePolicy
import com.liuml.apptimelimiter.core.ScopeReconciliationPolicy
import com.liuml.apptimelimiter.core.ScopeApprovalPolicy
import com.liuml.apptimelimiter.core.ScopeTargetGenerationPolicy
import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.data.InstalledAppsRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.migration.MigrationCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScopeSyncCoordinator private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val ruleRepository = RuleRepository(appContext)
    private val diagnosticsRepository = DiagnosticsRepository(appContext)
    private val statusRepository = XposedStatusRepository.instance
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scope-sync").apply { isDaemon = true }
    }
    private val initialized = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(ScopeSyncSnapshot())
    private val pendingLock = Any()
    private var pendingAutomatic = false
    private val pendingCallbacks = mutableListOf<(ScopeSyncResult) -> Unit>()

    val snapshot: StateFlow<ScopeSyncSnapshot> = _snapshot.asStateFlow()

    fun initialize() {
        if (!BuildConfig.MODERN_XPOSED_ENABLED) return
        statusRepository.initialize(appContext)
        if (!initialized.compareAndSet(false, true)) return
        appendDiagnostic("SCOPE_SYNC_COORDINATOR_INITIALIZED", "application=true")
        scope.launch {
            statusRepository.snapshot.collectLatest { framework ->
                publishFrameworkState(framework)
                if (framework.connected && !framework.stale) {
                    reconcile(automatic = true)
                }
            }
        }
        statusRepository.refresh()
    }

    fun notifyConfigurationChanged() {
        if (!BuildConfig.MODERN_XPOSED_ENABLED) {
            if (BuildConfig.LEGACY_MIGRATION_EXPORT_ENABLED) {
                MigrationCoordinator.get(appContext).refreshLegacyExport()
            }
            return
        }
        val desired = desiredPackages()
        val settings = ruleRepository.getGlobalSettings()
        // Capture every effective target-set transition before the asynchronous reconcile runs.
        targetGeneration(desired, settings.protectionModeGeneration)
        statusRepository.syncRulePreferences()
        reconcile(automatic = true)
    }

    fun syncNow(callback: (ScopeSyncResult) -> Unit = {}) {
        if (!BuildConfig.MODERN_XPOSED_ENABLED) {
            callback(ScopeSyncResult(emptySet(), emptySet(), "Modern Xposed is not enabled"))
            return
        }
        reconcile(automatic = false, callback = callback)
    }

    private fun reconcile(
        automatic: Boolean,
        callback: ((ScopeSyncResult) -> Unit)? = null,
    ) {
        initialize()
        synchronized(pendingLock) {
            if (callback != null) pendingCallbacks += callback
            if (!running.compareAndSet(false, true)) {
                pendingAutomatic = pendingAutomatic || automatic
                return
            }
        }
        executor.execute { performReconciliation(automatic) }
    }

    private fun performReconciliation(automatic: Boolean) {
        statusRepository.syncRulePreferences()
        val desired = desiredPackages()
        val settings = ruleRepository.getGlobalSettings()
        val generation = targetGeneration(
            packages = desired,
            protectionModeGeneration = settings.protectionModeGeneration,
        )
        val framework = statusRepository.snapshot.value
        val actual = framework.scopePackages
        val persistedFailed = readFailedPackages()
        val remainingFailed = persistedFailed - actual
        if (persistedFailed != remainingFailed) {
            preferences.edit()
                .putStringSet(KEY_FAILED_PACKAGES, remainingFailed)
                .apply {
                    if (remainingFailed.isEmpty()) remove(KEY_LAST_ERROR)
                }
                .commit()
        }
        val managed = preferences.getStringSet(KEY_MANAGED_PACKAGES, emptySet()).orEmpty() +
            desired.intersect(actual)
        persistManagedPackages(managed)

        if (!framework.connected || framework.stale) {
            _snapshot.value = ScopeSyncSnapshot(
                connected = false,
                stale = framework.stale,
                desiredPackages = desired,
                actualPackages = actual,
                pendingPackages = desired - actual,
                failedPackages = remainingFailed.intersect(desired),
                lastError = framework.errorMessage ?: "Xposed service unavailable",
                targetGeneration = generation,
                updatedAtMillis = System.currentTimeMillis(),
            )
            appendDiagnostic(
                event = "XPOSED_SCOPE_RECONCILE_DEFERRED",
                level = "WARN",
                signature = "${framework.stale}:$generation",
                message = "connected=${framework.connected} stale=${framework.stale} desired=${desired.size}",
            )
            finish(ScopeSyncResult(emptySet(), desired - actual, framework.errorMessage))
            return
        }

        val plan = ScopeReconciliationPolicy.resolve(
            desiredPackages = desired,
            actualPackages = actual,
            managedPackages = managed,
            protectionMode = settings.protectionMode,
            frameworkReadable = true,
        )
        val autoAlreadyAttempted = automatic &&
            preferences.getString(KEY_LAST_AUTO_REQUEST_GENERATION, null) == generation
        val requestPackages = if (autoAlreadyAttempted) emptySet() else plan.requestPackages

        _snapshot.value = ScopeSyncSnapshot(
            connected = true,
            syncing = plan.removePackages.isNotEmpty() || requestPackages.isNotEmpty(),
            desiredPackages = desired,
            actualPackages = actual,
            pendingPackages = desired - actual,
            failedPackages = remainingFailed.intersect(desired),
            targetGeneration = generation,
            updatedAtMillis = System.currentTimeMillis(),
        )
        appendDiagnostic(
            event = "XPOSED_SCOPE_RECONCILE_DIFF",
            signature = "$generation:${plan.requestPackages.size}:${plan.removePackages.size}:$autoAlreadyAttempted",
            message = "mode=${settings.protectionMode} desired=${desired.size} actual=${actual.size} request=${plan.requestPackages.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")} remove=${plan.removePackages.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")} autoSkipped=$autoAlreadyAttempted",
        )

        val afterRemoval: (String?) -> Unit = { removeError ->
            if (removeError != null) {
                publishFailure(desired, actual, generation, plan.removePackages, removeError)
                finish(ScopeSyncResult(emptySet(), desired - actual, removeError))
            } else {
                val retainedManaged = managed - plan.removePackages - (managed - desired - actual)
                persistManagedPackages(retainedManaged)
                if (requestPackages.isEmpty()) {
                    val error = if (
                        settings.protectionMode == ProtectionMode.XPOSED &&
                        plan.requestPackages.isNotEmpty() && autoAlreadyAttempted
                    ) {
                        preferences.getString(KEY_LAST_ERROR, null)
                    } else {
                        null
                    }
                    publishCompleted(
                        desired = desired,
                        actual = actual - plan.removePackages,
                        generation = generation,
                        error = error,
                    )
                    finish(ScopeSyncResult(emptySet(), desired - actual, error))
                } else {
                    if (automatic) {
                        preferences.edit()
                            .putString(KEY_LAST_AUTO_REQUEST_GENERATION, generation)
                            .commit()
                    }
                    requestScopeInternal(
                        packages = requestPackages,
                        automatic = automatic,
                        generation = generation,
                        desired = desired,
                        actual = actual - plan.removePackages,
                        callback = ::finish,
                    )
                }
            }
        }

        if (plan.removePackages.isEmpty()) {
            afterRemoval(null)
        } else {
            statusRepository.removeScope(plan.removePackages, afterRemoval)
        }
    }

    private fun requestScopeInternal(
        packages: Set<String>,
        automatic: Boolean,
        generation: String? = null,
        desired: Set<String>? = null,
        actual: Set<String>? = null,
        callback: (ScopeSyncResult) -> Unit,
    ) {
        val resolvedDesired = desired ?: desiredPackages()
        val settings = ruleRepository.getGlobalSettings()
        val resolvedGeneration = generation ?: targetGeneration(
            resolvedDesired,
            settings.protectionModeGeneration,
        )
        val resolvedActual = actual ?: statusRepository.snapshot.value.scopePackages
        if (!statusRepository.snapshot.value.connected) {
            callback(ScopeSyncResult(emptySet(), packages, "当前框架暂不可读"))
            return
        }
        if (automatic) {
            preferences.edit()
                .putString(KEY_LAST_AUTO_REQUEST_GENERATION, resolvedGeneration)
                .commit()
        }
        statusRepository.requestScope(packages) { approved, error ->
            val approval = ScopeApprovalPolicy.resolve(packages, approved)
            val accepted = approval.approvedPackages
            val remaining = approval.pendingPackages
            val managed = preferences.getStringSet(KEY_MANAGED_PACKAGES, emptySet()).orEmpty() +
                accepted
            persistManagedPackages(managed)
            if (remaining.isEmpty() && error == null) {
                preferences.edit()
                    .remove(KEY_FAILED_PACKAGES)
                    .remove(KEY_LAST_ERROR)
                    .commit()
            } else {
                preferences.edit()
                    .putStringSet(KEY_FAILED_PACKAGES, remaining)
                    .putString(KEY_LAST_ERROR, error ?: "部分应用未批准")
                    .commit()
            }
            _snapshot.value = ScopeSyncSnapshot(
                connected = true,
                syncing = false,
                desiredPackages = resolvedDesired,
                actualPackages = resolvedActual + accepted,
                pendingPackages = resolvedDesired - (resolvedActual + accepted),
                failedPackages = remaining,
                lastError = error ?: if (remaining.isNotEmpty()) "部分应用未批准" else null,
                targetGeneration = resolvedGeneration,
                updatedAtMillis = System.currentTimeMillis(),
            )
            appendDiagnostic(
                event = if (error == null && remaining.isEmpty()) {
                    "XPOSED_SCOPE_RECONCILE_COMPLETED"
                } else {
                    "XPOSED_SCOPE_RECONCILE_FAILED"
                },
                level = if (error == null && remaining.isEmpty()) "INFO" else "WARN",
                signature = "$resolvedGeneration:${accepted.size}:${remaining.size}:${error.orEmpty()}",
                message = "requested=${packages.size} approved=${accepted.size} pending=${remaining.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")} error=${error.orEmpty().take(300)}",
            )
            callback(ScopeSyncResult(accepted, remaining, error))
        }
    }

    private fun publishFrameworkState(framework: XposedFrameworkSnapshot) {
        val desired = desiredPackages()
        val settings = ruleRepository.getGlobalSettings()
        _snapshot.value = _snapshot.value.copy(
            connected = framework.connected,
            stale = framework.stale,
            desiredPackages = desired,
            actualPackages = framework.scopePackages,
            pendingPackages = desired - framework.scopePackages,
            targetGeneration = targetGeneration(
                desired,
                settings.protectionModeGeneration,
            ),
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun publishCompleted(
        desired: Set<String>,
        actual: Set<String>,
        generation: String,
        error: String?,
    ) {
        _snapshot.value = ScopeSyncSnapshot(
            connected = true,
            syncing = false,
            desiredPackages = desired,
            actualPackages = actual,
            pendingPackages = desired - actual,
            failedPackages = (readFailedPackages() - actual).intersect(desired),
            lastError = error,
            targetGeneration = generation,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun desiredPackages(): Set<String> {
        val configured = ruleRepository.configuredPackages()
        if (configured.isEmpty()) return emptySet()
        val installed = InstalledAppsRepository(appContext)
            .loadLaunchableApps()
            .mapTo(mutableSetOf()) { it.packageName }
        return configured.intersect(installed)
    }

    private fun publishFailure(
        desired: Set<String>,
        actual: Set<String>,
        generation: String,
        failed: Set<String>,
        error: String,
    ) {
        preferences.edit()
            .putStringSet(KEY_FAILED_PACKAGES, failed)
            .putString(KEY_LAST_ERROR, error.take(500))
            .commit()
        _snapshot.value = ScopeSyncSnapshot(
            connected = true,
            syncing = false,
            desiredPackages = desired,
            actualPackages = actual,
            pendingPackages = desired - actual,
            failedPackages = failed,
            lastError = error,
            targetGeneration = generation,
            updatedAtMillis = System.currentTimeMillis(),
        )
        appendDiagnostic(
            event = "XPOSED_SCOPE_RECONCILE_FAILED",
            level = "ERROR",
            signature = "$generation:${failed.sorted().joinToString(",")}:$error",
            message = "failed=${failed.sorted().take(MAX_DIAGNOSTIC_PACKAGES).joinToString(",")} error=${error.take(300)}",
        )
    }

    private fun finish(result: ScopeSyncResult) {
        val callbacks: List<(ScopeSyncResult) -> Unit>
        val runAgain: Boolean
        synchronized(pendingLock) {
            running.set(false)
            callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
            runAgain = pendingAutomatic
            pendingAutomatic = false
        }
        callbacks.forEach { callback -> runCatching { callback(result) } }
        if (runAgain) reconcile(automatic = true)
    }

    private fun persistManagedPackages(packages: Set<String>) {
        preferences.edit().putStringSet(KEY_MANAGED_PACKAGES, packages).commit()
    }

    private fun readFailedPackages(): Set<String> =
        preferences.getStringSet(KEY_FAILED_PACKAGES, emptySet()).orEmpty()

    private fun targetGeneration(
        packages: Set<String>,
        protectionModeGeneration: Long,
    ): String = synchronized(TARGET_GENERATION_LOCK) {
        val fingerprint = DesiredScopePolicy.generation(packages, protectionModeGeneration)
        val previousFingerprint = preferences.getString(KEY_TARGET_FINGERPRINT, null)
        val previousCounter = preferences.getLong(KEY_TARGET_GENERATION_COUNTER, 0L)
        val generation = ScopeTargetGenerationPolicy.resolve(
            previousFingerprint = previousFingerprint,
            previousCounter = previousCounter,
            currentFingerprint = fingerprint,
        )
        if (previousFingerprint != generation.fingerprint || previousCounter != generation.counter) {
            preferences.edit()
                .putString(KEY_TARGET_FINGERPRINT, generation.fingerprint)
                .putLong(KEY_TARGET_GENERATION_COUNTER, generation.counter)
                .commit()
        }
        "${generation.counter}:${generation.fingerprint}"
    }

    private fun appendDiagnostic(
        event: String,
        message: String,
        level: String = "INFO",
        signature: String = message,
    ) {
        diagnosticsRepository.appendRateLimited(
            level = level,
            packageName = appContext.packageName,
            event = event,
            stateSignature = signature.take(500),
            message = message.take(1_500),
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "scope_sync"
        private const val KEY_MANAGED_PACKAGES = "managed_packages"
        private const val KEY_FAILED_PACKAGES = "failed_packages"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_AUTO_REQUEST_GENERATION = "last_auto_request_generation"
        private const val KEY_TARGET_FINGERPRINT = "target_fingerprint"
        private const val KEY_TARGET_GENERATION_COUNTER = "target_generation_counter"
        private const val MAX_DIAGNOSTIC_PACKAGES = 20
        private val TARGET_GENERATION_LOCK = Any()

        @Volatile
        private var instance: ScopeSyncCoordinator? = null

        fun get(context: Context): ScopeSyncCoordinator =
            instance ?: synchronized(this) {
                instance ?: ScopeSyncCoordinator(context).also { instance = it }
            }
    }
}

data class ScopeSyncResult(
    val approvedPackages: Set<String>,
    val pendingPackages: Set<String>,
    val errorMessage: String?,
)
