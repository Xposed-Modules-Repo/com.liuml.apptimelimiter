package com.liuml.apptimelimiter.core

/** Keeps the UI wait bounded while a user-initiated rewarded ad is loading. */
object RewardedAdLoadPolicy {
    const val POLL_INTERVAL_MILLIS = 250L
    // TopOn's configured network timeout is commonly 12 seconds. Keep a small callback
    // margin so the app does not label an in-flight SDK result as a local timeout.
    const val LOAD_TIMEOUT_MILLIS = 16_000L
    // TopOn rejects a second load of the same placement immediately after a failure.
    // Keep the retry gate in one policy so UI taps cannot create an SDK-level 2008 error.
    const val FAILED_LOAD_RETRY_DELAY_MILLIS = 2_500L

    fun shouldKeepWaiting(
        startedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean = nowElapsedMillis - startedAtElapsedMillis < LOAD_TIMEOUT_MILLIS

    fun isRetryAllowed(
        failedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        // Long.MIN_VALUE represents "no prior failure". Subtracting it from an elapsed
        // timestamp overflows and previously made the very first load look throttled.
        if (failedAtElapsedMillis == Long.MIN_VALUE) return true
        return nowElapsedMillis - failedAtElapsedMillis >= FAILED_LOAD_RETRY_DELAY_MILLIS
    }
}
