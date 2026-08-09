package com.liuml.apptimelimiter.nonroot

enum class NonRootUiExecutionState {
    IDLE,
    PLAN_PENDING,
    PLAN_VISIBLE,
    BREAK_PAGE_PENDING,
    BREAK_PAGE_VISIBLE,
    ;

    val isRestrictionUi: Boolean
        get() = this == BREAK_PAGE_PENDING || this == BREAK_PAGE_VISIBLE
}

data class NonRootHealthSnapshot(
    val accessibilityRuntimeState: AccessibilityRuntimeState = AccessibilityRuntimeState.DISABLED,
    val accessibilityDetectionSource: AccessibilityDetectionSource =
        AccessibilityDetectionSource.NONE,
    val accessibilityStateChangedAtMillis: Long = 0L,
    val compatibilityMode: com.liuml.apptimelimiter.data.NonRootCompatibilityMode =
        com.liuml.apptimelimiter.data.NonRootCompatibilityMode.STANDARD,
    val lastAccessibilityEventAtMillis: Long = 0L,
    val lastAccessibilitySource: ForegroundSignalSource? = null,
    val lastForegroundAcceptedAtMillis: Long = 0L,
    val lastForegroundSource: ForegroundSignalSource? = null,
    val lastUsageReconciliationAtMillis: Long = 0L,
    val lastUsageReconciliationMatched: Boolean? = null,
    val uiState: NonRootUiExecutionState = NonRootUiExecutionState.IDLE,
    val lastUiEventAtMillis: Long = 0L,
    val lastUiEvent: String = "",
    val breakPageCompatibility: BreakPageCompatibilitySnapshot =
        BreakPageCompatibilitySnapshot(),
)

object NonRootUiRecoveryPolicy {
    fun shouldRestorePlanAfterServiceRestart(
        uiState: NonRootUiExecutionState,
        planPromptHandled: Boolean,
        planActive: Boolean,
    ): Boolean =
        uiState == NonRootUiExecutionState.PLAN_VISIBLE &&
            planPromptHandled &&
            !planActive

    fun shouldRetryPlanDetach(
        attemptsCompleted: Int,
        maxAttempts: Int,
        sameForegroundPackage: Boolean,
        sameSession: Boolean,
        restrictionUiActive: Boolean,
        persistentRestrictionActive: Boolean,
    ): Boolean =
        attemptsCompleted < maxAttempts &&
            sameForegroundPackage &&
            sameSession &&
            !restrictionUiActive &&
            !persistentRestrictionActive

    fun retryDelayMillis(
        attemptsCompleted: Int,
        firstDelayMillis: Long = 300L,
        secondDelayMillis: Long = 800L,
    ): Long = if (attemptsCompleted <= 1) firstDelayMillis else secondDelayMillis
}
