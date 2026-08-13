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

    /**
     * Usage reports arriving through the exported provider are external Hook side effects unless
     * they come from Time Stop's own UID. Legacy Hook builds may omit their version metadata, so
     * that field must never be used to bypass the selected protection mode.
     */
    fun acceptUsageReport(
        mode: ProtectionMode,
        trustedManagerRequest: Boolean,
    ): Boolean = trustedManagerRequest || acceptHookSideEffect(mode)
}
