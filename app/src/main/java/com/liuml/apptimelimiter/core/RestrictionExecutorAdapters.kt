package com.liuml.apptimelimiter.core

/** Small adapter used at the Android boundary so policy code does not know how an action runs. */
class CallbackRestrictionExecutor(
    private val available: () -> Boolean,
    private val action: (RestrictionRequest) -> Boolean,
    private val cancelAction: (String) -> Unit = {},
) : RestrictionExecutor {
    private val incidents = RestrictionIncidentDeduplicator()

    override fun isAvailable(): Boolean = runCatching { available() }.getOrDefault(false)

    override fun execute(request: RestrictionRequest): RestrictionExecutionResult {
        if (request.packageName.isBlank() || request.incidentId.isBlank()) {
            return RestrictionExecutionResult.REJECTED
        }
        if (!incidents.claim(request.incidentId)) {
            return RestrictionExecutionResult.ALREADY_EXECUTED
        }
        return runCatching {
            if (!isAvailable()) {
                RestrictionExecutionResult.FALLBACK_REQUIRED
            } else if (action(request)) {
                RestrictionExecutionResult.EXECUTED
            } else {
                RestrictionExecutionResult.FAILED
            }
        }.getOrElse { RestrictionExecutionResult.FAILED }
    }

    override fun cancel(requestId: String) = runCatching { cancelAction(requestId) }.getOrDefault(Unit)
}

typealias XposedExecutor = CallbackRestrictionExecutor
typealias AccessibilityExecutor = CallbackRestrictionExecutor
typealias ShizukuExecutor = CallbackRestrictionExecutor
