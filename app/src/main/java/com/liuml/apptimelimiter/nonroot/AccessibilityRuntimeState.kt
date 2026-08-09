package com.liuml.apptimelimiter.nonroot

enum class AccessibilityRuntimeState {
    DISABLED,
    ENABLED_DISCONNECTED,
    CONNECTED,
}

enum class AccessibilityDetectionSource {
    NONE,
    SECURE_SETTINGS,
    ACCESSIBILITY_MANAGER,
    LIVE_SERVICE,
}

data class AccessibilityRuntimeSnapshot(
    val state: AccessibilityRuntimeState = AccessibilityRuntimeState.DISABLED,
    val configuredBySecureSettings: Boolean = false,
    val configuredByAccessibilityManager: Boolean = false,
    val serviceConnected: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val detectionSource: AccessibilityDetectionSource = AccessibilityDetectionSource.NONE,
) {
    val systemConfigured: Boolean
        get() = configuredBySecureSettings || configuredByAccessibilityManager || serviceConnected

    val readyForProtection: Boolean
        get() = serviceConnected && usageAccessGranted
}

object AccessibilityRuntimePolicy {
    fun resolve(
        configuredBySecureSettings: Boolean,
        configuredByAccessibilityManager: Boolean,
        serviceConnected: Boolean,
        usageAccessGranted: Boolean,
    ): AccessibilityRuntimeSnapshot {
        val state = when {
            serviceConnected -> AccessibilityRuntimeState.CONNECTED
            configuredBySecureSettings || configuredByAccessibilityManager ->
                AccessibilityRuntimeState.ENABLED_DISCONNECTED
            else -> AccessibilityRuntimeState.DISABLED
        }
        val source = when {
            serviceConnected -> AccessibilityDetectionSource.LIVE_SERVICE
            configuredBySecureSettings -> AccessibilityDetectionSource.SECURE_SETTINGS
            configuredByAccessibilityManager -> AccessibilityDetectionSource.ACCESSIBILITY_MANAGER
            else -> AccessibilityDetectionSource.NONE
        }
        return AccessibilityRuntimeSnapshot(
            state = state,
            configuredBySecureSettings = configuredBySecureSettings,
            configuredByAccessibilityManager = configuredByAccessibilityManager,
            serviceConnected = serviceConnected,
            usageAccessGranted = usageAccessGranted,
            detectionSource = source,
        )
    }

    fun containsComponent(
        enabledServices: String?,
        expectedPackageName: String,
        expectedClassName: String,
    ): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        val expectedClass = normalizeClassName(expectedPackageName, expectedClassName)
        return enabledServices.split(':').any { flattened ->
            val separator = flattened.indexOf('/')
            if (separator <= 0 || separator >= flattened.lastIndex) return@any false
            val packageName = flattened.substring(0, separator).trim()
            val className = flattened.substring(separator + 1).trim()
            packageName == expectedPackageName &&
                normalizeClassName(packageName, className) == expectedClass
        }
    }

    fun componentMatches(
        packageName: String?,
        className: String?,
        expectedPackageName: String,
        expectedClassName: String,
    ): Boolean = packageName == expectedPackageName &&
        normalizeClassName(expectedPackageName, className.orEmpty()) ==
        normalizeClassName(expectedPackageName, expectedClassName)

    internal fun normalizeClassName(packageName: String, className: String): String = when {
        className.startsWith('.') -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }
}
