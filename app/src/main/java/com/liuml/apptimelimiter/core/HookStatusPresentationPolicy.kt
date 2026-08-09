package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.xposedstatus.ManagedAppHookState

object HookStatusPresentationPolicy {
    fun isDefiniteIssue(state: ManagedAppHookState): Boolean =
        state == ManagedAppHookState.NOT_IN_SCOPE ||
            state == ManagedAppHookState.RUNNING_STALE ||
            state == ManagedAppHookState.RUNNING_FAILED

    fun shouldShowAppIssue(
        nonRootModeEnabled: Boolean,
        state: ManagedAppHookState,
    ): Boolean = !nonRootModeEnabled && isDefiniteIssue(state)

    fun scopeReminderPackages(
        nonRootModeEnabled: Boolean,
        candidatePackages: Set<String>,
        states: Map<String, ManagedAppHookState>,
    ): Set<String> {
        if (nonRootModeEnabled) return emptySet()
        return candidatePackages.filterTo(mutableSetOf()) {
            states[it] == ManagedAppHookState.NOT_IN_SCOPE
        }
    }
}
