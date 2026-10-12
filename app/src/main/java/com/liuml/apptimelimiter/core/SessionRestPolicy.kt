package com.liuml.apptimelimiter.core

/** A rest changes the continuous-use cycle, never daily quotas or parent authorizations. */
object SessionRestPolicy {
    const val DEFAULT_REST_MILLIS = 30_000L

    fun intervalMillis(cooldownEnabled: Boolean, cooldownMillis: Long): Long =
        if (cooldownEnabled) cooldownMillis.coerceAtLeast(1L) else DEFAULT_REST_MILLIS

    fun completed(backgroundedAt: Long, now: Long, intervalMillis: Long): Boolean =
        backgroundedAt > 0L && now >= backgroundedAt &&
            now - backgroundedAt >= intervalMillis.coerceAtLeast(1L)
}
