package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode

/** Single state vocabulary shared by restriction UI, parent unlock and execution adapters. */
enum class ControlRuntimeState {
    EVALUATING,
    LIMIT_CLAIMED,
    WAITING_PARENT_AUTH,
    WAITING_AD,
    OVERRIDE_PENDING,
    OVERRIDE_ACTIVE,
    EXECUTING_RESTRICTION,
    RESTRICTION_VISIBLE,
    COOLDOWN_ACTIVE,
    CANCELLED,
}

data class PersistentControlToken(
    val packageName: String,
    val groupId: String,
    val sessionId: String,
    val ruleVersion: Long,
    val groupVersion: Long,
    val protectionMode: ProtectionMode,
    val modeGeneration: Long,
    val foregroundGeneration: Long,
    val incidentId: String,
    val state: ControlRuntimeState,
    val issuedAtWallMillis: Long,
    val issuedAtElapsedMillis: Long,
    val expiresAtWallMillis: Long = 0L,
    val expiresAtElapsedMillis: Long = 0L,
) {
    fun isValidFor(
        packageName: String,
        groupId: String,
        sessionId: String,
        ruleVersion: Long,
        groupVersion: Long,
        protectionMode: ProtectionMode,
        modeGeneration: Long,
        foregroundGeneration: Long,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        if (this.packageName != packageName || this.groupId != groupId || this.sessionId != sessionId) return false
        if (this.ruleVersion != ruleVersion || this.groupVersion != groupVersion) return false
        if (this.protectionMode != protectionMode || this.modeGeneration != modeGeneration) return false
        if (this.foregroundGeneration != foregroundGeneration || incidentId.isBlank()) return false
        val wallValid = expiresAtWallMillis <= 0L || nowWallMillis < expiresAtWallMillis
        val elapsedValid = expiresAtElapsedMillis <= 0L || nowElapsedMillis < expiresAtElapsedMillis
        return wallValid && elapsedValid
    }
}

object ControlRuntimeStatePolicy {
    fun canTransition(from: ControlRuntimeState, to: ControlRuntimeState): Boolean = when (from) {
        ControlRuntimeState.EVALUATING -> to == ControlRuntimeState.LIMIT_CLAIMED || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.LIMIT_CLAIMED -> to == ControlRuntimeState.WAITING_PARENT_AUTH ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.WAITING_PARENT_AUTH -> to == ControlRuntimeState.OVERRIDE_ACTIVE ||
            to == ControlRuntimeState.WAITING_AD || to == ControlRuntimeState.OVERRIDE_PENDING ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.WAITING_AD -> to == ControlRuntimeState.OVERRIDE_PENDING ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.OVERRIDE_PENDING -> to == ControlRuntimeState.OVERRIDE_ACTIVE ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.OVERRIDE_ACTIVE -> to == ControlRuntimeState.COOLDOWN_ACTIVE ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.EXECUTING_RESTRICTION -> to == ControlRuntimeState.RESTRICTION_VISIBLE ||
            to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.RESTRICTION_VISIBLE -> to == ControlRuntimeState.WAITING_PARENT_AUTH ||
            to == ControlRuntimeState.WAITING_AD ||
            to == ControlRuntimeState.EXECUTING_RESTRICTION ||
            to == ControlRuntimeState.COOLDOWN_ACTIVE || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.COOLDOWN_ACTIVE -> to == ControlRuntimeState.EVALUATING || to == ControlRuntimeState.CANCELLED
        ControlRuntimeState.CANCELLED -> false
    }
}
