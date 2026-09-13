package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode

data class ControlSessionToken(
    val packageName: String,
    val groupId: String,
    val sessionId: String,
    val ruleVersion: Long,
    val groupVersion: Long,
    val protectionMode: ProtectionMode,
    val modeGeneration: Long,
    val foregroundGeneration: Long,
)

object ControlSessionTokenPolicy {
    fun isStructurallyValid(token: ControlSessionToken): Boolean =
        token.packageName.isNotBlank() &&
            token.sessionId.isNotBlank() &&
            token.ruleVersion >= 0L &&
            token.groupVersion >= 0L &&
            token.modeGeneration >= 0L &&
            token.foregroundGeneration >= 0L

    fun matches(
        token: ControlSessionToken,
        current: ControlSessionToken,
    ): Boolean = isStructurallyValid(token) &&
        isStructurallyValid(current) &&
        token == current
}
