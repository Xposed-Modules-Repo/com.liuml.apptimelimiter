package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode

object ProtectionExecutionPolicy {
    fun hookMayExecute(mode: ProtectionMode): Boolean = mode == ProtectionMode.XPOSED

    fun nonRootMayExecute(mode: ProtectionMode): Boolean = mode.usesNonRoot

    fun ruleSnapshotMayExecute(
        mode: ProtectionMode,
        trustedNonRootRequest: Boolean,
    ): Boolean = if (trustedNonRootRequest) {
        nonRootMayExecute(mode)
    } else {
        hookMayExecute(mode)
    }

    fun acceptHookSideEffect(mode: ProtectionMode): Boolean = hookMayExecute(mode)
}
