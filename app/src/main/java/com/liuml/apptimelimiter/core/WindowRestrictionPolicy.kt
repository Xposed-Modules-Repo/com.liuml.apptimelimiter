package com.liuml.apptimelimiter.core

object WindowRestrictionPolicy {
    fun configurationIsWindowed(description: String): Boolean = Regex(
        "mWindowingMode=(freeform|multi-window|pinned|zoom)(?=[ ,}])"
    ).containsMatchIn(description)
    fun useEmbeddedPanel(mode: TargetWindowMode): Boolean = mode == TargetWindowMode.WINDOWED
    fun canExtend(enabled: Boolean, scheduleBlocked: Boolean, cooldownRemainingMillis: Long,
        remainingCount: Int): Boolean = enabled && !scheduleBlocked && cooldownRemainingMillis <= 0L && remainingCount > 0
}
