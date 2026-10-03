package com.liuml.apptimelimiter.core

/** No heartbeat alone is not an error; healthy or merely idle apps must not be stopped. */
object HookReloadTargetPolicy {
    fun packages(targets: List<TargetProtectionStatus>): List<String> = targets.filter {
        it.hasSavedRule && it.scopeState != ScopeState.NOT_IN_SCOPE &&
            it.scopeState != ScopeState.NOT_APPLICABLE && it.hookState in setOf(
                HookVerificationState.OUTDATED, HookVerificationState.FAILED,
                HookVerificationState.PENDING_REOPEN,
            )
    }.map { it.packageName }.distinct().sorted()
}
