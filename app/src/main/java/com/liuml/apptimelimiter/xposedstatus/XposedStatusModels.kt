package com.liuml.apptimelimiter.xposedstatus

enum class HookTargetState {
    UP_TO_DATE,
    STALE,
    RELOADING,
    FAILED,
    UNKNOWN,
}

data class XposedRunningTarget(
    val processName: String,
    val loadedVersionCode: Long,
    val state: HookTargetState,
)

data class XposedFrameworkSnapshot(
    val connected: Boolean = false,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int = 0,
    val scopePackages: Set<String> = emptySet(),
    val runningTargets: List<XposedRunningTarget> = emptyList(),
    val capturedAtMillis: Long = 0L,
    val stale: Boolean = false,
    val errorMessage: String? = null,
)

enum class ScopeSyncPackageState {
    SYNCED,
    WAITING_CONFIRMATION,
    FAILED,
    FRAMEWORK_UNREADABLE,
}

data class ScopeSyncSnapshot(
    val connected: Boolean = false,
    val stale: Boolean = false,
    val syncing: Boolean = false,
    val desiredPackages: Set<String> = emptySet(),
    val actualPackages: Set<String> = emptySet(),
    val pendingPackages: Set<String> = emptySet(),
    val failedPackages: Set<String> = emptySet(),
    val lastError: String? = null,
    val targetGeneration: String = "",
    val updatedAtMillis: Long = 0L,
) {
    fun stateFor(packageName: String): ScopeSyncPackageState = when {
        !connected || stale -> ScopeSyncPackageState.FRAMEWORK_UNREADABLE
        packageName in actualPackages -> ScopeSyncPackageState.SYNCED
        packageName in failedPackages -> ScopeSyncPackageState.FAILED
        else -> ScopeSyncPackageState.WAITING_CONFIRMATION
    }
}

enum class ManagedAppHookState {
    NOT_IN_SCOPE,
    IN_SCOPE_IDLE,
    RUNNING_CURRENT,
    RUNNING_STALE,
    RUNNING_FAILED,
    LEGACY_VERIFIED,
    COMPATIBILITY_PENDING,
}

object XposedStatusPolicy {
    fun resolve(
        packageName: String,
        snapshot: XposedFrameworkSnapshot,
        legacyHookVersionCode: Int,
        currentVersionCode: Int,
    ): ManagedAppHookState {
        if (!snapshot.connected) {
            return if (legacyHookVersionCode >= currentVersionCode) {
                ManagedAppHookState.LEGACY_VERIFIED
            } else {
                ManagedAppHookState.COMPATIBILITY_PENDING
            }
        }
        if (packageName !in snapshot.scopePackages) {
            return ManagedAppHookState.NOT_IN_SCOPE
        }
        val targets = snapshot.runningTargets.filter {
            processBelongsToPackage(it.processName, packageName)
        }
        if (targets.isEmpty()) {
            return if (legacyHookVersionCode >= currentVersionCode) {
                ManagedAppHookState.LEGACY_VERIFIED
            } else {
                ManagedAppHookState.IN_SCOPE_IDLE
            }
        }
        if (targets.any { it.state == HookTargetState.FAILED }) {
            return ManagedAppHookState.RUNNING_FAILED
        }
        if (
            targets.any {
                it.state == HookTargetState.STALE ||
                    it.state == HookTargetState.RELOADING ||
                    it.state == HookTargetState.UNKNOWN ||
                    it.loadedVersionCode < currentVersionCode
            }
        ) {
            return ManagedAppHookState.RUNNING_STALE
        }
        return ManagedAppHookState.RUNNING_CURRENT
    }

    fun isScopeReady(state: ManagedAppHookState): Boolean =
        state == ManagedAppHookState.IN_SCOPE_IDLE ||
            state == ManagedAppHookState.RUNNING_CURRENT ||
            state == ManagedAppHookState.LEGACY_VERIFIED

    internal fun processBelongsToPackage(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
