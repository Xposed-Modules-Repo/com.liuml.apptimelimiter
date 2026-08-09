package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState

enum class ScopeState {
    IN_SCOPE,
    NOT_IN_SCOPE,
    UNKNOWN,
    NOT_APPLICABLE,
}

enum class HookVerificationState {
    RUNNING_CURRENT,
    VERIFIED_CURRENT,
    IN_SCOPE_IDLE,
    YIELDED,
    PENDING_REOPEN,
    OUTDATED,
    FAILED,
    NOT_APPLICABLE,
}

enum class EffectiveController {
    XPOSED,
    ACCESSIBILITY,
    ACCESSIBILITY_SHIZUKU,
    ACCESSIBILITY_FALLBACK,
    WAITING_REOPEN,
    INACTIVE,
}

enum class ProtectionHealth {
    HEALTHY,
    DEGRADED,
    REPAIR_REQUIRED,
}

enum class ProtectionProductMessage {
    LSPOSED_TAKEN_OVER,
    NON_ROOT_TAKEN_OVER,
    ACCESSIBILITY_MISSING,
    ACCESSIBILITY_DISCONNECTED,
    USAGE_ACCESS_MISSING,
    SHIZUKU_FALLBACK,
    SCOPE_MISSING,
    SCOPE_UNKNOWN,
    LSPOSED_SCOPE_READY,
    HOOK_PENDING_REOPEN,
    HOOK_OUTDATED,
    HOOK_FAILED,
}

data class TargetProtectionStatus(
    val packageName: String,
    val hasSavedRule: Boolean,
    val scopeState: ScopeState,
    val hookState: HookVerificationState,
    val accessibilityEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val shizukuState: ShizukuExecutionState,
    val controller: EffectiveController,
    val health: ProtectionHealth,
    val message: ProtectionProductMessage,
)

data class ProtectionOverview(
    val selectedMode: ProtectionMode,
    val targetCount: Int,
    val issueCount: Int,
    val hasGlobalIssue: Boolean = false,
    val effective: Boolean,
)

data class TargetProtectionInput(
    val packageName: String,
    val hasSavedRule: Boolean,
    val frameworkConnected: Boolean,
    val managedHookState: ManagedAppHookState,
    val hookVersionCode: Int,
    val hookModeGeneration: Long,
    val currentVersionCode: Int,
    val selectedMode: ProtectionMode,
    val selectedModeGeneration: Long,
    val accessibilityEnabled: Boolean,
    val accessibilityConfigured: Boolean = accessibilityEnabled,
    val usageAccessGranted: Boolean,
    val shizukuState: ShizukuExecutionState,
)

object ProtectionStatusPolicy {
    fun resolveTarget(input: TargetProtectionInput): TargetProtectionStatus {
        val scopeState = scopeState(input)
        val hookState = hookState(input)
        val basicReady = input.accessibilityEnabled && input.usageAccessGranted
        val controller = when (input.selectedMode) {
            ProtectionMode.XPOSED -> when (hookState) {
                HookVerificationState.RUNNING_CURRENT,
                HookVerificationState.VERIFIED_CURRENT,
                HookVerificationState.IN_SCOPE_IDLE,
                -> EffectiveController.XPOSED
                HookVerificationState.PENDING_REOPEN -> EffectiveController.WAITING_REOPEN
                else -> EffectiveController.INACTIVE
            }
            ProtectionMode.ACCESSIBILITY -> if (basicReady) {
                EffectiveController.ACCESSIBILITY
            } else {
                EffectiveController.INACTIVE
            }
            ProtectionMode.ACCESSIBILITY_SHIZUKU -> when {
                !basicReady -> EffectiveController.INACTIVE
                input.shizukuState == ShizukuExecutionState.READY ->
                    EffectiveController.ACCESSIBILITY_SHIZUKU
                else -> EffectiveController.ACCESSIBILITY_FALLBACK
            }
        }
        val message = productMessage(input, scopeState, hookState, controller)
        val health = when {
            !input.hasSavedRule -> ProtectionHealth.REPAIR_REQUIRED
            input.selectedMode.usesNonRoot -> when (controller) {
                EffectiveController.ACCESSIBILITY_FALLBACK -> ProtectionHealth.DEGRADED
                else -> ProtectionHealth.HEALTHY
            }
            scopeState == ScopeState.NOT_IN_SCOPE ||
                hookState == HookVerificationState.OUTDATED ||
                hookState == HookVerificationState.FAILED -> ProtectionHealth.REPAIR_REQUIRED
            scopeState == ScopeState.UNKNOWN ||
                controller == EffectiveController.WAITING_REOPEN -> ProtectionHealth.DEGRADED
            else -> ProtectionHealth.HEALTHY
        }
        return TargetProtectionStatus(
            packageName = input.packageName,
            hasSavedRule = input.hasSavedRule,
            scopeState = scopeState,
            hookState = hookState,
            accessibilityEnabled = input.accessibilityEnabled,
            usageAccessGranted = input.usageAccessGranted,
            shizukuState = input.shizukuState,
            controller = controller,
            health = health,
            message = message,
        )
    }

    fun overview(
        selectedMode: ProtectionMode,
        targets: Collection<TargetProtectionStatus>,
    ): ProtectionOverview {
        val targetIssueCount = if (selectedMode.usesNonRoot) {
            targets.count { !it.hasSavedRule }
        } else {
            targets.count { it.health == ProtectionHealth.REPAIR_REQUIRED }
        }
        val hasGlobalIssue = when {
            selectedMode.usesNonRoot -> targets.isNotEmpty() && targets.none {
                it.controller == EffectiveController.ACCESSIBILITY ||
                    it.controller == EffectiveController.ACCESSIBILITY_SHIZUKU ||
                    it.controller == EffectiveController.ACCESSIBILITY_FALLBACK
            }
            else -> targets.any { it.scopeState == ScopeState.UNKNOWN }
        }
        val effective = when (selectedMode) {
            ProtectionMode.XPOSED -> targets.any {
                it.controller == EffectiveController.XPOSED
            }
            ProtectionMode.ACCESSIBILITY,
            ProtectionMode.ACCESSIBILITY_SHIZUKU,
            -> targets.isNotEmpty() && targets.any {
                it.controller == EffectiveController.ACCESSIBILITY ||
                    it.controller == EffectiveController.ACCESSIBILITY_SHIZUKU ||
                    it.controller == EffectiveController.ACCESSIBILITY_FALLBACK
            }
        }
        return ProtectionOverview(
            selectedMode = selectedMode,
            targetCount = targets.size,
            issueCount = targetIssueCount,
            hasGlobalIssue = hasGlobalIssue,
            effective = effective,
        )
    }

    private fun scopeState(input: TargetProtectionInput): ScopeState {
        if (input.selectedMode != ProtectionMode.XPOSED) return ScopeState.NOT_APPLICABLE
        if (!input.frameworkConnected) return ScopeState.UNKNOWN
        return if (input.managedHookState == ManagedAppHookState.NOT_IN_SCOPE) {
            ScopeState.NOT_IN_SCOPE
        } else {
            ScopeState.IN_SCOPE
        }
    }

    private fun hookState(input: TargetProtectionInput): HookVerificationState {
        val currentHeartbeat = input.hookVersionCode >= input.currentVersionCode &&
            input.hookModeGeneration >= input.selectedModeGeneration
        if (input.selectedMode.usesNonRoot) {
            return HookVerificationState.NOT_APPLICABLE
        }
        if (input.managedHookState == ManagedAppHookState.NOT_IN_SCOPE) {
            return HookVerificationState.NOT_APPLICABLE
        }
        if (input.managedHookState == ManagedAppHookState.RUNNING_FAILED) {
            return HookVerificationState.FAILED
        }
        if (input.managedHookState == ManagedAppHookState.RUNNING_STALE) {
            return HookVerificationState.OUTDATED
        }
        return when (input.managedHookState) {
            ManagedAppHookState.RUNNING_CURRENT -> HookVerificationState.RUNNING_CURRENT
            ManagedAppHookState.IN_SCOPE_IDLE -> HookVerificationState.IN_SCOPE_IDLE
            ManagedAppHookState.LEGACY_VERIFIED -> HookVerificationState.VERIFIED_CURRENT
            ManagedAppHookState.COMPATIBILITY_PENDING -> if (currentHeartbeat) {
                HookVerificationState.VERIFIED_CURRENT
            } else {
                HookVerificationState.PENDING_REOPEN
            }
            ManagedAppHookState.NOT_IN_SCOPE -> HookVerificationState.NOT_APPLICABLE
            ManagedAppHookState.RUNNING_STALE -> HookVerificationState.OUTDATED
            ManagedAppHookState.RUNNING_FAILED -> HookVerificationState.FAILED
        }
    }

    private fun productMessage(
        input: TargetProtectionInput,
        scopeState: ScopeState,
        hookState: HookVerificationState,
        controller: EffectiveController,
    ): ProtectionProductMessage = when {
        !input.accessibilityEnabled && input.selectedMode.usesNonRoot ->
            if (input.accessibilityConfigured) {
                ProtectionProductMessage.ACCESSIBILITY_DISCONNECTED
            } else {
                ProtectionProductMessage.ACCESSIBILITY_MISSING
            }
        input.accessibilityEnabled &&
            !input.usageAccessGranted &&
            input.selectedMode.usesNonRoot -> ProtectionProductMessage.USAGE_ACCESS_MISSING
        scopeState == ScopeState.NOT_IN_SCOPE -> ProtectionProductMessage.SCOPE_MISSING
        scopeState == ScopeState.UNKNOWN &&
            hookState == HookVerificationState.PENDING_REOPEN ->
            ProtectionProductMessage.SCOPE_UNKNOWN
        hookState == HookVerificationState.IN_SCOPE_IDLE ->
            ProtectionProductMessage.LSPOSED_SCOPE_READY
        hookState == HookVerificationState.OUTDATED -> ProtectionProductMessage.HOOK_OUTDATED
        hookState == HookVerificationState.FAILED -> ProtectionProductMessage.HOOK_FAILED
        hookState == HookVerificationState.PENDING_REOPEN ->
            ProtectionProductMessage.HOOK_PENDING_REOPEN
        controller == EffectiveController.ACCESSIBILITY_FALLBACK ->
            ProtectionProductMessage.SHIZUKU_FALLBACK
        controller == EffectiveController.XPOSED ->
            ProtectionProductMessage.LSPOSED_TAKEN_OVER
        else -> ProtectionProductMessage.NON_ROOT_TAKEN_OVER
    }
}
