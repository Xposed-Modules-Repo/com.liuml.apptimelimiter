package com.liuml.apptimelimiter.nonroot

enum class BreakPageConfirmation {
    MATCHED,
    LATE_OR_EXTERNAL,
    ATTEMPT_MISMATCH,
    PACKAGE_MISMATCH,
}

object NonRootUiExecutionPolicy {
    fun shouldSuppressTargetSignalBehindBreakPage(
        uiState: NonRootUiExecutionState,
        signalPackageName: String,
        visibleRestrictionPackage: String?,
        breakPageActivityForeground: Boolean,
    ): Boolean =
        uiState == NonRootUiExecutionState.BREAK_PAGE_VISIBLE &&
            signalPackageName == visibleRestrictionPackage &&
            breakPageActivityForeground

    fun classifyConfirmation(
        pendingAttemptId: String?,
        pendingPackageName: String?,
        confirmedAttemptId: String,
        confirmedPackageName: String,
    ): BreakPageConfirmation = when {
        pendingAttemptId == null -> BreakPageConfirmation.LATE_OR_EXTERNAL
        pendingPackageName != confirmedPackageName -> BreakPageConfirmation.PACKAGE_MISMATCH
        pendingAttemptId != confirmedAttemptId -> BreakPageConfirmation.ATTEMPT_MISMATCH
        else -> BreakPageConfirmation.MATCHED
    }

    fun shouldRunConfirmationTimeout(
        pendingAttemptId: String?,
        timeoutAttemptId: String,
    ): Boolean = pendingAttemptId == timeoutAttemptId
}
