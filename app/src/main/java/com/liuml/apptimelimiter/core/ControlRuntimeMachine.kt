package com.liuml.apptimelimiter.core

/**
 * Small, thread-safe state holder for one restriction incident. All asynchronous callbacks
 * must pass the same incident and session token before changing state.
 */
class ControlRuntimeMachine(
    val incidentId: String,
    private val token: ControlSessionToken,
    initialState: ControlRuntimeState = ControlRuntimeState.EVALUATING,
) {
    private var currentState = initialState

    @Synchronized
    fun state(): ControlRuntimeState = currentState

    @Synchronized
    fun transition(next: ControlRuntimeState, callbackToken: ControlSessionToken): Boolean {
        if (!ControlSessionTokenPolicy.matches(token, callbackToken)) return false
        if (next == currentState) return true
        if (!ControlRuntimeStatePolicy.canTransition(currentState, next)) return false
        currentState = next
        return true
    }

    @Synchronized
    fun cancel(callbackToken: ControlSessionToken): Boolean =
        transition(ControlRuntimeState.CANCELLED, callbackToken)
}

/** Keeps incident claims bounded and idempotent across retries in one process. */
class IncidentClaimRegistry(private val maxEntries: Int = 128) {
    private val claimed = LinkedHashSet<String>()

    @Synchronized
    fun claim(incidentId: String): Boolean {
        if (incidentId.isBlank()) return false
        if (!claimed.add(incidentId)) return false
        while (claimed.size > maxEntries.coerceAtLeast(1)) {
            claimed.iterator().apply { next(); remove() }
        }
        return true
    }
}
