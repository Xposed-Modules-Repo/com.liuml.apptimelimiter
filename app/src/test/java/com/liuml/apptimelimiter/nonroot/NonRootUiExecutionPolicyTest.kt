package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootUiExecutionPolicyTest {
    @Test
    fun `stale target signal is suppressed while break page remains foreground`() {
        assertTrue(
            NonRootUiExecutionPolicy.shouldSuppressTargetSignalBehindBreakPage(
                uiState = NonRootUiExecutionState.BREAK_PAGE_VISIBLE,
                signalPackageName = "com.example.target",
                visibleRestrictionPackage = "com.example.target",
                breakPageActivityForeground = true,
            ),
        )
        assertFalse(
            NonRootUiExecutionPolicy.shouldSuppressTargetSignalBehindBreakPage(
                uiState = NonRootUiExecutionState.BREAK_PAGE_VISIBLE,
                signalPackageName = "com.example.target",
                visibleRestrictionPackage = "com.example.target",
                breakPageActivityForeground = false,
            ),
        )
    }

    @Test
    fun `foreground confirmation must match attempt and package`() {
        assertEquals(
            BreakPageConfirmation.MATCHED,
            NonRootUiExecutionPolicy.classifyConfirmation(
                pendingAttemptId = "attempt-1",
                pendingPackageName = "com.example.target",
                confirmedAttemptId = "attempt-1",
                confirmedPackageName = "com.example.target",
            ),
        )
        assertEquals(
            BreakPageConfirmation.ATTEMPT_MISMATCH,
            NonRootUiExecutionPolicy.classifyConfirmation(
                pendingAttemptId = "attempt-1",
                pendingPackageName = "com.example.target",
                confirmedAttemptId = "attempt-2",
                confirmedPackageName = "com.example.target",
            ),
        )
        assertEquals(
            BreakPageConfirmation.PACKAGE_MISMATCH,
            NonRootUiExecutionPolicy.classifyConfirmation(
                pendingAttemptId = "attempt-1",
                pendingPackageName = "com.example.target",
                confirmedAttemptId = "attempt-1",
                confirmedPackageName = "com.example.other",
            ),
        )
    }

    @Test
    fun `late activity confirmation does not cancel another pending attempt`() {
        assertEquals(
            BreakPageConfirmation.LATE_OR_EXTERNAL,
            NonRootUiExecutionPolicy.classifyConfirmation(
                pendingAttemptId = null,
                pendingPackageName = null,
                confirmedAttemptId = "attempt-1",
                confirmedPackageName = "com.example.target",
            ),
        )
        assertTrue(
            NonRootUiExecutionPolicy.shouldRunConfirmationTimeout(
                pendingAttemptId = "attempt-1",
                timeoutAttemptId = "attempt-1",
            ),
        )
        assertFalse(
            NonRootUiExecutionPolicy.shouldRunConfirmationTimeout(
                pendingAttemptId = "attempt-2",
                timeoutAttemptId = "attempt-1",
            ),
        )
    }
}
