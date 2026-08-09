package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.AccessibilityRuntimeState
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState

enum class ProtectionPresentationState {
    NO_TARGETS,
    XPOSED_SCOPE_READY,
    XPOSED_HOOK_VERIFIED,
    XPOSED_WAITING_VERIFICATION,
    XPOSED_REPAIR_REQUIRED,
    ACCESSIBILITY_RUNNING,
    ACCESSIBILITY_SHIZUKU_RUNNING,
    ACCESSIBILITY_SHIZUKU_FALLBACK,
    ACCESSIBILITY_NOT_READY,
}

enum class ProtectionPresentationSeverity {
    HEALTHY,
    INFORMATION,
    WARNING,
    REPAIR_REQUIRED,
}

data class ProtectionPresentationSnapshot(
    val selectedMode: ProtectionMode,
    val state: ProtectionPresentationState,
    val severity: ProtectionPresentationSeverity,
    val targetCount: Int,
    val explicitIssuePackages: Set<String> = emptySet(),
)

object ProtectionPresentationPolicy {
    fun resolve(
        selectedMode: ProtectionMode,
        targets: Collection<TargetProtectionStatus>,
        accessibilityState: AccessibilityRuntimeState,
        usageAccessGranted: Boolean,
        shizukuState: ShizukuExecutionState,
    ): ProtectionPresentationSnapshot {
        if (targets.isEmpty()) {
            return ProtectionPresentationSnapshot(
                selectedMode = selectedMode,
                state = ProtectionPresentationState.NO_TARGETS,
                severity = ProtectionPresentationSeverity.INFORMATION,
                targetCount = 0,
            )
        }
        return if (selectedMode == ProtectionMode.XPOSED) {
            resolveXposed(selectedMode, targets)
        } else {
            resolveNonRoot(
                selectedMode = selectedMode,
                targetCount = targets.size,
                accessibilityState = accessibilityState,
                usageAccessGranted = usageAccessGranted,
                shizukuState = shizukuState,
            )
        }
    }

    private fun resolveXposed(
        selectedMode: ProtectionMode,
        targets: Collection<TargetProtectionStatus>,
    ): ProtectionPresentationSnapshot {
        val explicitIssues = targets.filter { status ->
            status.scopeState == ScopeState.NOT_IN_SCOPE ||
                status.hookState == HookVerificationState.OUTDATED ||
                status.hookState == HookVerificationState.FAILED
        }.mapTo(linkedSetOf(), TargetProtectionStatus::packageName)
        if (explicitIssues.isNotEmpty()) {
            return ProtectionPresentationSnapshot(
                selectedMode = selectedMode,
                state = ProtectionPresentationState.XPOSED_REPAIR_REQUIRED,
                severity = ProtectionPresentationSeverity.REPAIR_REQUIRED,
                targetCount = targets.size,
                explicitIssuePackages = explicitIssues,
            )
        }
        val hookVerified = targets.any {
            it.hookState == HookVerificationState.RUNNING_CURRENT ||
                it.hookState == HookVerificationState.VERIFIED_CURRENT
        }
        if (hookVerified) {
            return ProtectionPresentationSnapshot(
                selectedMode = selectedMode,
                state = ProtectionPresentationState.XPOSED_HOOK_VERIFIED,
                severity = ProtectionPresentationSeverity.HEALTHY,
                targetCount = targets.size,
            )
        }
        val scopeReady = targets.any {
            it.scopeState == ScopeState.IN_SCOPE ||
                it.hookState == HookVerificationState.IN_SCOPE_IDLE
        }
        return ProtectionPresentationSnapshot(
            selectedMode = selectedMode,
            state = if (scopeReady) {
                ProtectionPresentationState.XPOSED_SCOPE_READY
            } else {
                ProtectionPresentationState.XPOSED_WAITING_VERIFICATION
            },
            severity = if (scopeReady) {
                ProtectionPresentationSeverity.HEALTHY
            } else {
                ProtectionPresentationSeverity.INFORMATION
            },
            targetCount = targets.size,
        )
    }

    private fun resolveNonRoot(
        selectedMode: ProtectionMode,
        targetCount: Int,
        accessibilityState: AccessibilityRuntimeState,
        usageAccessGranted: Boolean,
        shizukuState: ShizukuExecutionState,
    ): ProtectionPresentationSnapshot {
        if (accessibilityState != AccessibilityRuntimeState.CONNECTED || !usageAccessGranted) {
            return ProtectionPresentationSnapshot(
                selectedMode = selectedMode,
                state = ProtectionPresentationState.ACCESSIBILITY_NOT_READY,
                severity = ProtectionPresentationSeverity.REPAIR_REQUIRED,
                targetCount = targetCount,
            )
        }
        if (selectedMode == ProtectionMode.ACCESSIBILITY_SHIZUKU) {
            return ProtectionPresentationSnapshot(
                selectedMode = selectedMode,
                state = if (shizukuState == ShizukuExecutionState.READY) {
                    ProtectionPresentationState.ACCESSIBILITY_SHIZUKU_RUNNING
                } else {
                    ProtectionPresentationState.ACCESSIBILITY_SHIZUKU_FALLBACK
                },
                severity = if (shizukuState == ShizukuExecutionState.READY) {
                    ProtectionPresentationSeverity.HEALTHY
                } else {
                    ProtectionPresentationSeverity.WARNING
                },
                targetCount = targetCount,
            )
        }
        return ProtectionPresentationSnapshot(
            selectedMode = selectedMode,
            state = ProtectionPresentationState.ACCESSIBILITY_RUNNING,
            severity = ProtectionPresentationSeverity.HEALTHY,
            targetCount = targetCount,
        )
    }
}
