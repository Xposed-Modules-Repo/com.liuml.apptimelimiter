package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.ForceStopEnhancement
import com.liuml.apptimelimiter.nonroot.AccessibilityRuntimeState
import com.liuml.apptimelimiter.nonroot.ShizukuExecutionState
import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState

enum class ProtectionRequirement {
    LSPOSED_HOOK,
    ACCESSIBILITY_SERVICE,
    USAGE_ACCESS,
    SHIZUKU,
    ROOT,
}

enum class ProtectionRequirementState {
    READY,
    NEEDS_ACTION,
    WAITING,
    UNAVAILABLE,
}

data class ProtectionRequirementItem(
    val requirement: ProtectionRequirement,
    val state: ProtectionRequirementState,
)

data class ProtectionModeStatus(
    val mode: ProtectionMode,
    val items: List<ProtectionRequirementItem>,
) {
    val isEffective: Boolean
        get() = items.all { it.state == ProtectionRequirementState.READY ||
            it.state == ProtectionRequirementState.WAITING }
}

object ProtectionModeRequirementsPolicy {
    fun resolve(
        mode: ProtectionMode,
        hookState: ManagedAppHookState?,
        accessibilityState: AccessibilityRuntimeState,
        usageAccessGranted: Boolean,
        shizukuState: ShizukuExecutionState,
        xposedRootEnhancementEnabled: Boolean,
        accessibilityEnhancement: ForceStopEnhancement,
    ): ProtectionModeStatus {
        val items = when (mode) {
            ProtectionMode.XPOSED -> buildList {
                add(
                ProtectionRequirementItem(
                    ProtectionRequirement.LSPOSED_HOOK,
                    when (hookState) {
                        ManagedAppHookState.RUNNING_CURRENT,
                        ManagedAppHookState.IN_SCOPE_IDLE,
                        ManagedAppHookState.LEGACY_VERIFIED,
                        -> ProtectionRequirementState.READY
                        ManagedAppHookState.RUNNING_STALE,
                        ManagedAppHookState.RUNNING_FAILED,
                        ManagedAppHookState.NOT_IN_SCOPE,
                        -> ProtectionRequirementState.NEEDS_ACTION
                        else -> ProtectionRequirementState.WAITING
                    },
                ),
                )
                if (xposedRootEnhancementEnabled) {
                    add(ProtectionRequirementItem(ProtectionRequirement.ROOT, ProtectionRequirementState.WAITING))
                }
            }
            ProtectionMode.ACCESSIBILITY -> buildList {
                add(
                    ProtectionRequirementItem(
                        ProtectionRequirement.ACCESSIBILITY_SERVICE,
                        if (accessibilityState == AccessibilityRuntimeState.CONNECTED) {
                            ProtectionRequirementState.READY
                        } else {
                            ProtectionRequirementState.NEEDS_ACTION
                        },
                    ),
                )
                add(
                    ProtectionRequirementItem(
                        ProtectionRequirement.USAGE_ACCESS,
                        if (usageAccessGranted) {
                            ProtectionRequirementState.READY
                        } else {
                            ProtectionRequirementState.NEEDS_ACTION
                        },
                    ),
                )
                if (accessibilityEnhancement == ForceStopEnhancement.SHIZUKU) {
                    add(
                        ProtectionRequirementItem(
                            ProtectionRequirement.SHIZUKU,
                            if (shizukuState == ShizukuExecutionState.READY) {
                                ProtectionRequirementState.READY
                            } else {
                                ProtectionRequirementState.UNAVAILABLE
                            },
                        ),
                    )
                }
                if (accessibilityEnhancement == ForceStopEnhancement.ROOT) {
                    add(
                        ProtectionRequirementItem(
                            ProtectionRequirement.ROOT,
                            ProtectionRequirementState.WAITING,
                        ),
                    )
                }
            }
        }
        return ProtectionModeStatus(mode, items)
    }
}
