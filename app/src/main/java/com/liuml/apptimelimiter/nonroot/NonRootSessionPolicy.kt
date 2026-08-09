package com.liuml.apptimelimiter.nonroot

import java.time.LocalDate
import java.util.UUID

data class NonRootSessionState(
    val packageName: String,
    val sessionId: String,
    val protectionModeGeneration: Long = 0L,
    val accumulatedForegroundMillis: Long = 0L,
    val foregroundStartedAtElapsedMillis: Long = 0L,
    val foregroundDayToken: String = "",
    val backgroundedAtElapsedMillis: Long = 0L,
    val graceEndsAtElapsedMillis: Long = 0L,
    val launchRecorded: Boolean = false,
    val planPromptHandled: Boolean = false,
    val planActive: Boolean = false,
    val planRemainingMillis: Long = 0L,
)

data class DayBoundaryUsageSplit(
    val previousDayMillis: Long,
    val currentDayMillis: Long,
)

object NonRootSessionPolicy {
    const val SESSION_GRACE_MILLIS = 30_000L

    fun newSession(
        packageName: String,
        protectionModeGeneration: Long = 0L,
    ): NonRootSessionState = NonRootSessionState(
        packageName = packageName,
        sessionId = UUID.randomUUID().toString(),
        protectionModeGeneration = protectionModeGeneration.coerceAtLeast(0L),
    )

    fun shouldResume(
        state: NonRootSessionState?,
        nowElapsedMillis: Long,
    ): Boolean = state != null &&
        state.graceEndsAtElapsedMillis > 0L &&
        nowElapsedMillis <= state.graceEndsAtElapsedMillis

    fun foreground(
        state: NonRootSessionState?,
        packageName: String,
        nowElapsedMillis: Long,
        dayToken: String = LocalDate.now().toString(),
        protectionModeGeneration: Long = state?.protectionModeGeneration ?: 0L,
    ): NonRootSessionState {
        val base = state
            ?.takeIf {
                it.packageName == packageName &&
                    it.protectionModeGeneration == protectionModeGeneration &&
                    (
                        it.foregroundStartedAtElapsedMillis > 0L ||
                            shouldResume(it, nowElapsedMillis)
                        )
            }
            ?: newSession(packageName, protectionModeGeneration)
        if (base.foregroundStartedAtElapsedMillis > 0L) {
            return if (base.foregroundDayToken.isBlank()) {
                base.copy(foregroundDayToken = dayToken)
            } else {
                base
            }
        }
        return base.copy(
            foregroundStartedAtElapsedMillis = nowElapsedMillis,
            foregroundDayToken = dayToken,
            backgroundedAtElapsedMillis = 0L,
            graceEndsAtElapsedMillis = 0L,
        )
    }

    fun background(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
    ): NonRootSessionState {
        val segment = activeSegmentMillis(state, nowElapsedMillis)
        return state.copy(
            accumulatedForegroundMillis = safeAdd(
                state.accumulatedForegroundMillis,
                segment,
            ),
            foregroundStartedAtElapsedMillis = 0L,
            foregroundDayToken = "",
            backgroundedAtElapsedMillis = nowElapsedMillis,
            graceEndsAtElapsedMillis = safeAdd(nowElapsedMillis, SESSION_GRACE_MILLIS),
            planRemainingMillis = (state.planRemainingMillis - segment).coerceAtLeast(0L),
        )
    }

    /**
     * A persisted active segment cannot be trusted after the accessibility process has died:
     * elapsedRealtime() kept advancing while no foreground events were observed. Preserve all
     * previously committed usage and plan time, but pause the unknown segment instead of charging
     * the whole process-death gap to the user.
     */
    fun recoverAfterProcessLoss(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
    ): NonRootSessionState {
        if (state.foregroundStartedAtElapsedMillis <= 0L) return state
        val recoveredAt = nowElapsedMillis.coerceAtLeast(0L)
        return state.copy(
            foregroundStartedAtElapsedMillis = 0L,
            foregroundDayToken = "",
            backgroundedAtElapsedMillis = recoveredAt,
            graceEndsAtElapsedMillis = safeAdd(recoveredAt, SESSION_GRACE_MILLIS),
        )
    }

    fun splitActiveSegmentAtDayBoundary(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
        elapsedSinceCurrentDayStartMillis: Long,
    ): DayBoundaryUsageSplit {
        val activeSegment = activeSegmentMillis(state, nowElapsedMillis)
        val currentDay = elapsedSinceCurrentDayStartMillis.coerceIn(0L, activeSegment)
        return DayBoundaryUsageSplit(
            previousDayMillis = activeSegment - currentDay,
            currentDayMillis = currentDay,
        )
    }

    fun foregroundUsedMillis(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
    ): Long = safeAdd(
        state.accumulatedForegroundMillis,
        activeSegmentMillis(state, nowElapsedMillis),
    )

    fun planRemainingMillis(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
    ): Long = (
        state.planRemainingMillis - activeSegmentMillis(state, nowElapsedMillis)
        ).coerceAtLeast(0L)

    fun withPlan(
        state: NonRootSessionState,
        durationMillis: Long,
    ): NonRootSessionState = state.copy(
        planPromptHandled = true,
        planActive = true,
        planRemainingMillis = durationMillis.coerceAtLeast(0L),
    )

    fun skipPlan(state: NonRootSessionState): NonRootSessionState =
        state.copy(
            planPromptHandled = true,
            planActive = false,
            planRemainingMillis = 0L,
        )

    private fun activeSegmentMillis(
        state: NonRootSessionState,
        nowElapsedMillis: Long,
    ): Long = if (state.foregroundStartedAtElapsedMillis > 0L) {
        (nowElapsedMillis - state.foregroundStartedAtElapsedMillis).coerceAtLeast(0L)
    } else {
        0L
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
