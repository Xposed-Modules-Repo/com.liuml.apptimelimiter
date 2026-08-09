package com.liuml.apptimelimiter.core

enum class SessionPlanDurationStatus {
    VALID,
    EMPTY,
    NON_NUMERIC,
    ZERO,
    OUT_OF_RANGE,
    EXCEEDS_MAX,
}

data class SessionPlanDurationEvaluation(
    val totalMinutes: Int?,
    val status: SessionPlanDurationStatus,
)

object SessionPlanDurationPolicy {
    const val MAX_TOTAL_MINUTES = 24 * 60
    const val MAX_TOTAL_MILLIS = MAX_TOTAL_MINUTES * 60_000L

    fun evaluate(rawMinutes: String, maxAllowedMillis: Long?): SessionPlanDurationEvaluation {
        val normalized = rawMinutes.trim()
        if (normalized.isEmpty()) {
            return SessionPlanDurationEvaluation(null, SessionPlanDurationStatus.EMPTY)
        }
        if (normalized.any { !it.isDigit() }) {
            return SessionPlanDurationEvaluation(null, SessionPlanDurationStatus.NON_NUMERIC)
        }
        val minutes = normalized.toLongOrNull()
            ?: return SessionPlanDurationEvaluation(null, SessionPlanDurationStatus.OUT_OF_RANGE)
        if (minutes == 0L) {
            return SessionPlanDurationEvaluation(null, SessionPlanDurationStatus.ZERO)
        }
        if (minutes !in 1L..MAX_TOTAL_MINUTES.toLong()) {
            return SessionPlanDurationEvaluation(null, SessionPlanDurationStatus.OUT_OF_RANGE)
        }
        val durationMillis = minutes * 60_000L
        return SessionPlanDurationEvaluation(
            totalMinutes = minutes.toInt(),
            status = if (maxAllowedMillis != null && durationMillis > maxAllowedMillis) {
                SessionPlanDurationStatus.EXCEEDS_MAX
            } else {
                SessionPlanDurationStatus.VALID
            },
        )
    }

    fun durationAllowed(durationMillis: Long, maxAllowedMillis: Long?): Boolean =
        durationMillis in 1L..MAX_TOTAL_MILLIS &&
            (maxAllowedMillis == null || durationMillis <= maxAllowedMillis)

    fun maxSelectableMinutes(maxAllowedMillis: Long): Long =
        maxAllowedMillis.coerceIn(0L, MAX_TOTAL_MILLIS) / 60_000L
}
