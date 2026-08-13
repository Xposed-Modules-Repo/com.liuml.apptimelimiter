package com.liuml.apptimelimiter.nonroot

enum class ForegroundPackageKind {
    TARGET_APP,
    TIME_STOP_ACTIVITY,
    TIME_STOP_OVERLAY,
    HOME,
    INPUT_METHOD,
    SYSTEM_UI,
    PERMISSION_UI,
    OTHER_APP,
    ;

    val isTransientSurface: Boolean
        get() = this == INPUT_METHOD ||
            this == SYSTEM_UI ||
            this == PERMISSION_UI ||
            this == TIME_STOP_OVERLAY
}

data class ForegroundExecutionSnapshot(
    val packageName: String,
    val kind: ForegroundPackageKind,
    val source: ForegroundSignalSource,
    val generation: Long,
    val observedAtMillis: Long,
    val confirmedByAccessibility: Boolean,
)

object ForegroundPackagePolicy {
    fun classify(
        packageName: String,
        ownPackageName: String,
        targetPackages: Set<String>,
        homePackages: Set<String>,
        inputMethodPackage: String?,
        overlayOwnedByTimeStop: Boolean = false,
        systemUiPackages: Set<String> = setOf("android", "com.android.systemui"),
        permissionPackages: Set<String> = setOf(
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
        ),
    ): ForegroundPackageKind = when {
        packageName == ownPackageName && overlayOwnedByTimeStop ->
            ForegroundPackageKind.TIME_STOP_OVERLAY
        packageName == ownPackageName -> ForegroundPackageKind.TIME_STOP_ACTIVITY
        packageName in homePackages -> ForegroundPackageKind.HOME
        inputMethodPackage != null && packageName == inputMethodPackage ->
            ForegroundPackageKind.INPUT_METHOD
        packageName in systemUiPackages -> ForegroundPackageKind.SYSTEM_UI
        packageName in permissionPackages -> ForegroundPackageKind.PERMISSION_UI
        packageName in targetPackages -> ForegroundPackageKind.TARGET_APP
        else -> ForegroundPackageKind.OTHER_APP
    }

    fun isAuthoritativeAccessibilitySignal(source: ForegroundSignalSource): Boolean = when (source) {
        ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE,
        ForegroundSignalSource.ACCESSIBILITY_WINDOWS_CHANGED,
        ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
        -> true
        ForegroundSignalSource.USAGE_STATS_RECOVERY,
        ForegroundSignalSource.USAGE_STATS_RECONCILE,
        -> false
    }
}

object NonRootActionGuard {
    fun mayExecuteDisruptiveAction(
        targetPackageName: String,
        current: ForegroundExecutionSnapshot?,
        expectedGeneration: Long? = null,
    ): Boolean = current != null &&
        current.packageName == targetPackageName &&
        current.kind == ForegroundPackageKind.TARGET_APP &&
        current.confirmedByAccessibility &&
        (expectedGeneration == null || current.generation == expectedGeneration)

    fun shouldCancelPendingAction(
        targetPackageName: String,
        newForegroundPackageName: String,
        newForegroundKind: ForegroundPackageKind,
        ownPackageName: String,
    ): Boolean = newForegroundPackageName != targetPackageName &&
        newForegroundPackageName != ownPackageName &&
        newForegroundKind != ForegroundPackageKind.TIME_STOP_OVERLAY

    /**
     * Transient system surfaces replace the execution snapshot without changing the underlying
     * foreground app. When that app emits a new authoritative accessibility event, restore the
     * target snapshot even though the package-level foreground session did not change.
     */
    fun shouldRestoreSamePackageSnapshot(
        currentForegroundPackageName: String?,
        signalPackageName: String,
        signalKind: ForegroundPackageKind,
        signalConfirmedByAccessibility: Boolean,
        currentExecutionSnapshot: ForegroundExecutionSnapshot?,
    ): Boolean = currentForegroundPackageName == signalPackageName &&
        signalKind == ForegroundPackageKind.TARGET_APP &&
        signalConfirmedByAccessibility &&
        (
            currentExecutionSnapshot?.packageName != signalPackageName ||
                currentExecutionSnapshot.kind != ForegroundPackageKind.TARGET_APP ||
                !currentExecutionSnapshot.confirmedByAccessibility
            )
}
