package com.liuml.apptimelimiter.nonroot

import android.view.accessibility.AccessibilityEvent
import com.liuml.apptimelimiter.data.NonRootCompatibilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSignalPolicyTest {
    @Test
    fun `standard mode does not subscribe to content signals`() {
        assertNull(
            ForegroundSignalPolicy.sourceForEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                NonRootCompatibilityMode.STANDARD,
            ),
        )
    }

    @Test
    fun `enhanced mode maps content signal without changing window signals`() {
        assertEquals(
            ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
            ForegroundSignalPolicy.sourceForEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                NonRootCompatibilityMode.ENHANCED_EVENTS,
            ),
        )
        assertEquals(
            ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE,
            ForegroundSignalPolicy.sourceForEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                NonRootCompatibilityMode.ENHANCED_EVENTS,
            ),
        )
    }

    @Test
    fun `content signals are debounced for current package and short repeats`() {
        assertTrue(
            ForegroundSignalPolicy.shouldDebounce(
                source = ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                packageName = "target",
                currentPackageName = "target",
                lastAcceptedElapsedMillis = null,
                nowElapsedMillis = 1_000L,
            ),
        )
        assertTrue(
            ForegroundSignalPolicy.shouldDebounce(
                source = ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                packageName = "target",
                currentPackageName = "other",
                lastAcceptedElapsedMillis = 900L,
                nowElapsedMillis = 1_000L,
            ),
        )
        assertFalse(
            ForegroundSignalPolicy.shouldDebounce(
                source = ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT,
                packageName = "target",
                currentPackageName = "other",
                lastAcceptedElapsedMillis = 700L,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `two stable content signals or usage agreement confirm candidate`() {
        val first = ForegroundSignalPolicy.updateCompatibilityCandidate(
            previous = null,
            packageName = "target",
            nowElapsedMillis = 1_000L,
        )
        assertFalse(
            ForegroundSignalPolicy.compatibilityCandidateConfirmed(first, "other"),
        )
        assertTrue(
            ForegroundSignalPolicy.compatibilityCandidateConfirmed(first, "target"),
        )
        val second = ForegroundSignalPolicy.updateCompatibilityCandidate(
            previous = first,
            packageName = "target",
            nowElapsedMillis = 1_400L,
        )
        assertTrue(
            ForegroundSignalPolicy.compatibilityCandidateConfirmed(second, null),
        )
    }

    @Test
    fun `usage stats only overrides a different signal when it is recent enough`() {
        val current = ForegroundSignalSnapshot(
            packageName = "old",
            source = ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE,
            observedAtMillis = 10_000L,
            acceptedAtElapsedMillis = 500L,
        )
        assertTrue(
            ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                current = current,
                usagePackageName = "new",
                usageObservedAtMillis = 10_001L,
            ),
        )
        assertFalse(
            ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                current = current,
                usagePackageName = "new",
                usageObservedAtMillis = 9_999L,
            ),
        )
        assertFalse(
            ForegroundSignalPolicy.shouldOverrideWithUsageStats(
                current = current,
                usagePackageName = "old",
                usageObservedAtMillis = 11_000L,
            ),
        )
    }
}
