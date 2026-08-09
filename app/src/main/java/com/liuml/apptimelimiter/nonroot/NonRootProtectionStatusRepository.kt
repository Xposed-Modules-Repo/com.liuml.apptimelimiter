package com.liuml.apptimelimiter.nonroot

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import com.liuml.apptimelimiter.statistics.DeviceUsageStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NonRootProtectionStatusRepository private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shizuku = ShizukuExecutionRepository.get(appContext)
    private val breakPageCompatibilityStore = BreakPageCompatibilityStore(appContext)
    private val _snapshot = MutableStateFlow(ProtectionEngineSnapshot())
    private val _healthSnapshot = MutableStateFlow(NonRootHealthSnapshot())
    private var lastAccessibilitySnapshot = AccessibilityRuntimeSnapshot()
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    val snapshot: StateFlow<ProtectionEngineSnapshot> = _snapshot.asStateFlow()
    val healthSnapshot: StateFlow<NonRootHealthSnapshot> = _healthSnapshot.asStateFlow()

    init {
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            settingsObserver,
        )
        appContext.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            settingsObserver,
        )
        scope.launch {
            shizuku.state.collect { refresh() }
        }
        refresh()
    }

    @Synchronized
    fun refresh() {
        shizuku.refresh()
        val settings = RuleRepository(appContext).getGlobalSettings()
        val accessibility = readAccessibilityRuntimeSnapshot(appContext)
        recordAccessibilityStateTransition(accessibility, settings.diagnosticsEnabled)
        _healthSnapshot.update {
            it.copy(
                compatibilityMode = settings.nonRootCompatibilityMode,
                accessibilityRuntimeState = accessibility.state,
                accessibilityDetectionSource = accessibility.detectionSource,
                accessibilityStateChangedAtMillis = if (
                    it.accessibilityRuntimeState != accessibility.state
                ) {
                    System.currentTimeMillis()
                } else {
                    it.accessibilityStateChangedAtMillis
                },
                breakPageCompatibility = breakPageCompatibilityStore.snapshot(),
            )
        }
        val shizukuState = shizuku.state.value
        _snapshot.value = ProtectionEnginePolicy.resolve(
            protectionMode = settings.protectionMode,
            accessibilityEnabled = accessibility.serviceConnected,
            accessibilityConfigured = accessibility.systemConfigured,
            usageAccessGranted = accessibility.usageAccessGranted,
            shizukuAvailable = shizukuState != ShizukuExecutionState.UNAVAILABLE &&
                shizukuState != ShizukuExecutionState.DISABLED,
            shizukuPermissionGranted = shizukuState == ShizukuExecutionState.READY ||
                shizukuState == ShizukuExecutionState.CONNECTING,
        )
        TimeStopAccessibilityService.refreshConfiguration()
    }

    fun recordAccessibilitySignal(
        source: ForegroundSignalSource,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        _healthSnapshot.update {
            it.copy(
                lastAccessibilityEventAtMillis = observedAtMillis,
                lastAccessibilitySource = source,
            )
        }
    }

    fun recordForegroundAccepted(
        source: ForegroundSignalSource,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        _healthSnapshot.update {
            it.copy(
                lastForegroundAcceptedAtMillis = observedAtMillis,
                lastForegroundSource = source,
            )
        }
    }

    fun recordUsageReconciliation(
        matched: Boolean,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        _healthSnapshot.update {
            it.copy(
                lastUsageReconciliationAtMillis = observedAtMillis,
                lastUsageReconciliationMatched = matched,
            )
        }
    }

    fun recordUiState(
        state: NonRootUiExecutionState,
        event: String,
        observedAtMillis: Long = System.currentTimeMillis(),
    ) {
        _healthSnapshot.update {
            it.copy(
                uiState = state,
                lastUiEventAtMillis = observedAtMillis,
                lastUiEvent = event.take(80),
            )
        }
    }

    fun recordBreakPageRequested() {
        breakPageCompatibilityStore.recordRequested()
        refreshBreakPageCompatibility()
    }

    fun recordBreakPageConfirmed() {
        breakPageCompatibilityStore.recordConfirmed()
        refreshBreakPageCompatibility()
    }

    fun recordBreakPageFailure(
        packageName: String,
        stage: BreakPageCompatibilityStage,
        detail: String,
    ): Boolean {
        breakPageCompatibilityStore.recordFailure(packageName, stage, detail)
        refreshBreakPageCompatibility()
        return breakPageCompatibilityStore.shouldShowGuidance()
    }

    fun markBreakPageGuidanceShown() {
        breakPageCompatibilityStore.markGuidanceShown()
    }

    private fun refreshBreakPageCompatibility() {
        _healthSnapshot.update {
            it.copy(breakPageCompatibility = breakPageCompatibilityStore.snapshot())
        }
    }

    fun openAccessibilitySettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    fun notifyServiceConnectionChanged(connected: Boolean) {
        refresh()
        val settings = RuleRepository(appContext).getGlobalSettings()
        if (settings.diagnosticsEnabled) {
            DiagnosticsRepository(appContext).append(
                level = "INFO",
                packageName = appContext.packageName,
                event = if (connected) {
                    "ACCESSIBILITY_SERVICE_CONNECTED"
                } else {
                    "ACCESSIBILITY_SERVICE_DISCONNECTED"
                },
                message = "connected=$connected",
            )
        }
    }

    private fun recordAccessibilityStateTransition(
        current: AccessibilityRuntimeSnapshot,
        diagnosticsEnabled: Boolean,
    ) {
        val previous = lastAccessibilitySnapshot
        lastAccessibilitySnapshot = current
        if (!diagnosticsEnabled || previous == current) return
        val diagnostics = DiagnosticsRepository(appContext)
        diagnostics.append(
            level = "INFO",
            packageName = appContext.packageName,
            event = "ACCESSIBILITY_STATE_CHANGED",
            message = "${previous.state}->${current.state}, source=${current.detectionSource}, " +
                "secure=${current.configuredBySecureSettings}, " +
                "manager=${current.configuredByAccessibilityManager}, " +
                "connected=${current.serviceConnected}, usage=${current.usageAccessGranted}",
        )
        if (current.systemConfigured) {
            diagnostics.append(
                level = "INFO",
                packageName = appContext.packageName,
                event = "ACCESSIBILITY_COMPONENT_DETECTED",
                message = "source=${current.detectionSource}, state=${current.state}",
            )
        }
    }

    companion object {
        @Volatile
        private var instance: NonRootProtectionStatusRepository? = null

        fun get(context: Context): NonRootProtectionStatusRepository =
            instance ?: synchronized(this) {
                instance ?: NonRootProtectionStatusRepository(context).also {
                    instance = it
                }
            }

        fun isAccessibilityEnabled(context: Context): Boolean =
            readAccessibilityRuntimeSnapshot(context).systemConfigured

        fun isAccessibilityConnected(context: Context): Boolean =
            readAccessibilityRuntimeSnapshot(context).serviceConnected

        fun readAccessibilityRuntimeSnapshot(context: Context): AccessibilityRuntimeSnapshot {
            val appContext = context.applicationContext
            val expectedPackage = appContext.packageName
            val expectedClass = TimeStopAccessibilityService::class.java.name
            val configuredBySecureSettings = runCatching {
                AccessibilityRuntimePolicy.containsComponent(
                    enabledServices = Settings.Secure.getString(
                        appContext.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ),
                    expectedPackageName = expectedPackage,
                    expectedClassName = expectedClass,
                )
            }.getOrDefault(false)
            val configuredByManager = runCatching {
                val manager = appContext.getSystemService(AccessibilityManager::class.java)
                    ?: return@runCatching false
                manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                ).any { info ->
                    AccessibilityRuntimePolicy.componentMatches(
                        packageName = info.resolveInfo.serviceInfo.packageName,
                        className = info.resolveInfo.serviceInfo.name,
                        expectedPackageName = expectedPackage,
                        expectedClassName = expectedClass,
                    )
                }
            }.getOrDefault(false)
            return AccessibilityRuntimePolicy.resolve(
                configuredBySecureSettings = configuredBySecureSettings,
                configuredByAccessibilityManager = configuredByManager,
                serviceConnected = TimeStopAccessibilityService.isServiceConnected(),
                usageAccessGranted = DeviceUsageStatsRepository(appContext).hasUsageAccess(),
            )
        }
    }
}
