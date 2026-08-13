package com.liuml.apptimelimiter.nonroot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.liuml.apptimelimiter.data.NonRootCompatibilityMode
import com.liuml.apptimelimiter.data.RuleRepository
import com.liuml.apptimelimiter.diagnostics.DiagnosticsRepository
import java.lang.ref.WeakReference

class TimeStopAccessibilityService : AccessibilityService() {
    private var coordinator: ForegroundControlCoordinator? = null
    private var receiverRegistered = false
    private var packageReceiverRegistered = false
    private var compatibilityMode = NonRootCompatibilityMode.STANDARD

    private val environmentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> coordinator?.onScreenInteractiveChanged(false)
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> coordinator?.onScreenInteractiveChanged(isInteractive())
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                -> coordinator?.onTimeEnvironmentChanged()
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            coordinator?.refreshHomePackages()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator?.destroy()
        coordinator = ForegroundControlCoordinator(this)
        activeService = WeakReference(this)
        applyConfiguredEventTypes()
        registerEnvironmentReceiver()
        registerPackageReceiver()
        coordinator?.recoverForegroundFromUsageStats()
        NonRootProtectionStatusRepository.get(this).notifyServiceConnectionChanged(true)
        if (RuleRepository(this).getGlobalSettings().diagnosticsEnabled) {
            DiagnosticsRepository(this).append(
                level = "INFO",
                packageName = packageName,
                event = "NON_ROOT_SERVICE_CONNECTED",
                message = "Accessibility service connected; content retrieval disabled",
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val source = ForegroundSignalPolicy.sourceForEvent(
            eventType = eventType,
            mode = compatibilityMode,
        ) ?: return
        NonRootProtectionStatusRepository.get(this).recordAccessibilitySignal(source)
        val packageName = event?.let {
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                eventType = eventType,
                packageName = it.packageName,
                ownPackageName = packageName,
                ignoreOwnPackageWindowEvent =
                    coordinator?.shouldIgnoreOwnOverlayWindowEvents() == true,
                compatibilityMode = compatibilityMode,
            )
        } ?: return
        coordinator?.onForegroundSignal(
            packageName = packageName,
            eventType = eventType,
            source = source,
            observedAtMillis = System.currentTimeMillis(),
        )
    }

    override fun onInterrupt() {
        coordinator?.onServiceInterrupted()
    }

    override fun onDestroy() {
        coordinator?.destroy()
        coordinator = null
        if (activeService?.get() === this) {
            activeService = null
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(environmentReceiver) }
            receiverRegistered = false
        }
        if (packageReceiverRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            packageReceiverRegistered = false
        }
        NonRootProtectionStatusRepository.get(this).notifyServiceConnectionChanged(false)
        super.onDestroy()
    }

    private fun registerEnvironmentReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(environmentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(environmentReceiver, filter)
        }
        receiverRegistered = true
        coordinator?.onScreenInteractiveChanged(isInteractive())
    }

    private fun registerPackageReceiver() {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, filter)
        }
        packageReceiverRegistered = true
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun applyConfiguredEventTypes() {
        val settings = RuleRepository(this).getGlobalSettings()
        val configured = settings.nonRootCompatibilityMode
        compatibilityMode = configured
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                if (configured == NonRootCompatibilityMode.ENHANCED_EVENTS) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                } else {
                    0
                }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = ACCESSIBILITY_NOTIFICATION_TIMEOUT_MILLIS
        info.flags = 0
        runCatching { setServiceInfo(info) }
            .onFailure { error ->
                if (settings.diagnosticsEnabled) {
                    DiagnosticsRepository(this).append(
                        level = "ERROR",
                        packageName = packageName,
                        event = "NON_ROOT_SERVICE_INFO_APPLY_FAILED",
                        message = "mode=$configured, cause=${error.javaClass.name}",
                    )
                }
            }
    }

    companion object {
        @Volatile
        private var activeService: WeakReference<TimeStopAccessibilityService>? = null

        fun notifyRestrictionPageShown(targetPackage: String, attemptId: String): Boolean =
            activeService?.get()?.coordinator?.onRestrictionPageShown(targetPackage, attemptId) == true

        fun notifyRestrictionPageClosed(targetPackage: String) {
            activeService?.get()?.coordinator?.onRestrictionPageClosed(targetPackage)
        }

        fun refreshConfiguration() {
            val service = activeService?.get() ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.applyConfiguredEventTypes()
            } else {
                MAIN_HANDLER.post {
                    activeService?.get()
                        ?.takeIf { it === service }
                        ?.applyConfiguredEventTypes()
                }
            }
        }

        fun notifyProtectionModeChanged(
            mode: com.liuml.apptimelimiter.data.ProtectionMode,
            generation: Long,
        ) {
            activeService?.get()?.coordinator?.onProtectionModeChanged(mode, generation)
        }

        fun isServiceConnected(): Boolean = activeService?.get() != null

        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private const val ACCESSIBILITY_NOTIFICATION_TIMEOUT_MILLIS = 100L
    }
}
