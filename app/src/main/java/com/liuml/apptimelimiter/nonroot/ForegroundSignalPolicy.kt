package com.liuml.apptimelimiter.nonroot

import android.view.accessibility.AccessibilityEvent
import com.liuml.apptimelimiter.data.NonRootCompatibilityMode

enum class ForegroundSignalSource {
    ACCESSIBILITY_WINDOW_STATE,
    ACCESSIBILITY_WINDOWS_CHANGED,
    ACCESSIBILITY_CONTENT_COMPAT,
    USAGE_STATS_RECOVERY,
    USAGE_STATS_RECONCILE,
}

data class ForegroundSignalSnapshot(
    val packageName: String,
    val source: ForegroundSignalSource,
    val observedAtMillis: Long,
    val acceptedAtElapsedMillis: Long,
)

data class CompatibilitySignalState(
    val packageName: String = "",
    val firstSeenAtElapsedMillis: Long = 0L,
    val lastSeenAtElapsedMillis: Long = 0L,
    val count: Int = 0,
)

object ForegroundSignalPolicy {
    fun sourceForEvent(
        eventType: Int,
        mode: NonRootCompatibilityMode,
    ): ForegroundSignalSource? = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
            ForegroundSignalSource.ACCESSIBILITY_WINDOW_STATE
        AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
            ForegroundSignalSource.ACCESSIBILITY_WINDOWS_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
            ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT
                .takeIf { mode == NonRootCompatibilityMode.ENHANCED_EVENTS }
        else -> null
    }

    fun shouldDebounce(
        source: ForegroundSignalSource,
        packageName: String,
        currentPackageName: String?,
        lastAcceptedElapsedMillis: Long?,
        nowElapsedMillis: Long,
        debounceMillis: Long = CONTENT_EVENT_DEBOUNCE_MILLIS,
    ): Boolean {
        if (source != ForegroundSignalSource.ACCESSIBILITY_CONTENT_COMPAT) return false
        if (packageName == currentPackageName) return true
        val previous = lastAcceptedElapsedMillis ?: return false
        return nowElapsedMillis - previous in 0L until debounceMillis
    }

    fun updateCompatibilityCandidate(
        previous: CompatibilitySignalState?,
        packageName: String,
        nowElapsedMillis: Long,
        candidateWindowMillis: Long = COMPATIBILITY_CANDIDATE_WINDOW_MILLIS,
    ): CompatibilitySignalState {
        if (
            previous == null ||
            previous.packageName != packageName ||
            nowElapsedMillis - previous.lastSeenAtElapsedMillis !in 0L..candidateWindowMillis
        ) {
            return CompatibilitySignalState(
                packageName = packageName,
                firstSeenAtElapsedMillis = nowElapsedMillis,
                lastSeenAtElapsedMillis = nowElapsedMillis,
                count = 1,
            )
        }
        return previous.copy(
            lastSeenAtElapsedMillis = nowElapsedMillis,
            count = previous.count + 1,
        )
    }

    fun compatibilityCandidateConfirmed(
        candidate: CompatibilitySignalState?,
        usagePackageName: String?,
        requiredSignals: Int = 2,
    ): Boolean = candidate != null &&
        (
            candidate.count >= requiredSignals ||
                usagePackageName == candidate.packageName
            )

    fun shouldOverrideWithUsageStats(
        current: ForegroundSignalSnapshot?,
        usagePackageName: String,
        usageObservedAtMillis: Long,
        orderingToleranceMillis: Long = 0L,
    ): Boolean {
        if (usagePackageName.isBlank()) return false
        if (current == null) return true
        if (usagePackageName == current.packageName) return false
        return usageObservedAtMillis + orderingToleranceMillis >= current.observedAtMillis
    }

    const val CONTENT_EVENT_DEBOUNCE_MILLIS = 250L
    const val COMPATIBILITY_CONFIRM_DELAY_MILLIS = 250L
    const val COMPATIBILITY_CANDIDATE_WINDOW_MILLIS = 1_000L
}
