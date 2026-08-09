package com.liuml.apptimelimiter.nonroot

enum class NonRootPermissionIssue {
    ACCESSIBILITY_DISABLED,
    ACCESSIBILITY_DISCONNECTED,
    USAGE_ACCESS_MISSING,
    SHIZUKU_PERMISSION_REQUIRED,
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_FAILED,
}

object NonRootPermissionPolicy {
    fun issues(
        nonRootEnabled: Boolean,
        accessibilityState: AccessibilityRuntimeState,
        usageAccessGranted: Boolean,
        shizukuSelected: Boolean,
        shizukuState: ShizukuExecutionState,
    ): List<NonRootPermissionIssue> {
        if (!nonRootEnabled) return emptyList()
        return buildList {
            when (accessibilityState) {
                AccessibilityRuntimeState.DISABLED ->
                    add(NonRootPermissionIssue.ACCESSIBILITY_DISABLED)
                AccessibilityRuntimeState.ENABLED_DISCONNECTED ->
                    add(NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED)
                AccessibilityRuntimeState.CONNECTED -> Unit
            }
            if (!usageAccessGranted) {
                add(NonRootPermissionIssue.USAGE_ACCESS_MISSING)
            }
            if (shizukuSelected) {
                when (shizukuState) {
                    ShizukuExecutionState.PERMISSION_REQUIRED ->
                        add(NonRootPermissionIssue.SHIZUKU_PERMISSION_REQUIRED)
                    ShizukuExecutionState.UNAVAILABLE,
                    ShizukuExecutionState.DISABLED,
                    -> add(NonRootPermissionIssue.SHIZUKU_UNAVAILABLE)
                    ShizukuExecutionState.FAILED ->
                        add(NonRootPermissionIssue.SHIZUKU_FAILED)
                    ShizukuExecutionState.CONNECTING,
                    ShizukuExecutionState.READY,
                    -> Unit
                }
            }
        }
    }

    fun primaryIssue(issues: Collection<NonRootPermissionIssue>): NonRootPermissionIssue? =
        NonRootPermissionIssue.entries.firstOrNull(issues::contains)

    fun basicProtectionAvailable(issues: Collection<NonRootPermissionIssue>): Boolean =
        NonRootPermissionIssue.ACCESSIBILITY_DISABLED !in issues &&
            NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED !in issues &&
            NonRootPermissionIssue.USAGE_ACCESS_MISSING !in issues
}
