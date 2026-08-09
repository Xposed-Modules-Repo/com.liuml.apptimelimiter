package com.liuml.apptimelimiter.nonroot

object NonRootSessionResetPolicy {
    fun resetAtMillis(
        perSessionReached: Boolean,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
        graceEndsAtElapsedMillis: Long,
        cooldownEndsAtMillis: Long,
    ): Long {
        if (!perSessionReached) return 0L
        val graceRemaining = (graceEndsAtElapsedMillis - nowElapsedMillis)
            .coerceAtLeast(0L)
        val graceResetAt = safeAdd(nowWallMillis, graceRemaining)
        return maxOf(cooldownEndsAtMillis, graceResetAt)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
