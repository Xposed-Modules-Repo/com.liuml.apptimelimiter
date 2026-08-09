package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.nonroot.NonRootPermissionIssue

data class PermissionRepairPromptDecision(
    val signature: String,
    val shouldShow: Boolean,
    val reason: PermissionRepairPromptReason,
)

enum class PermissionRepairPromptReason {
    SHOWN,
    NOT_APPLICABLE,
    NO_TARGETS,
    NO_ISSUES,
    SUPPRESSED,
    COOLDOWN,
}

object PermissionRepairPromptPolicy {
    const val DEFAULT_COOLDOWN_MILLIS = 72L * 60L * 60L * 1_000L
    const val ACCESSIBILITY_RECONNECT_GRACE_MILLIS = 3_000L
    private const val MAX_SUPPRESSED_SIGNATURES = 24

    fun signature(
        mode: ProtectionMode,
        issues: Collection<NonRootPermissionIssue>,
    ): String = buildString {
        append(mode.name)
        append(':')
        append(issues.map(Enum<*>::name).distinct().sorted().joinToString(","))
    }

    fun initialDelayMillis(issues: Collection<NonRootPermissionIssue>): Long =
        if (NonRootPermissionIssue.ACCESSIBILITY_DISCONNECTED in issues) {
            ACCESSIBILITY_RECONNECT_GRACE_MILLIS
        } else {
            0L
        }

    fun decide(
        mode: ProtectionMode,
        targetCount: Int,
        issues: Collection<NonRootPermissionIssue>,
        suppressedSignatures: Set<String>,
        lastShownSignature: String?,
        lastShownAtMillis: Long,
        nowMillis: Long,
        cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    ): PermissionRepairPromptDecision {
        val signature = signature(mode, issues)
        val reason = when {
            !mode.usesNonRoot -> PermissionRepairPromptReason.NOT_APPLICABLE
            targetCount <= 0 -> PermissionRepairPromptReason.NO_TARGETS
            issues.isEmpty() -> PermissionRepairPromptReason.NO_ISSUES
            signature in suppressedSignatures -> PermissionRepairPromptReason.SUPPRESSED
            lastShownSignature == signature &&
                lastShownAtMillis > 0L &&
                nowMillis >= lastShownAtMillis &&
                nowMillis - lastShownAtMillis < cooldownMillis ->
                PermissionRepairPromptReason.COOLDOWN
            else -> PermissionRepairPromptReason.SHOWN
        }
        return PermissionRepairPromptDecision(
            signature = signature,
            shouldShow = reason == PermissionRepairPromptReason.SHOWN,
            reason = reason,
        )
    }

    fun addSuppressed(
        existing: Set<String>,
        signature: String,
    ): Set<String> = (existing + signature)
        .filter(String::isNotBlank)
        .takeLast(MAX_SUPPRESSED_SIGNATURES)
        .toSet()
}
