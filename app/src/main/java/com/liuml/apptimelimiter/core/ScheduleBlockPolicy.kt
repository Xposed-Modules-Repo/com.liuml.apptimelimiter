package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode

/** Stable identity for one continuous blocked schedule interval. */
object ScheduleBlockPolicy {
    fun token(
        ruleVersion: Long,
        mode: ScheduleMode,
        nextTransitionEpochMillis: Long?,
    ): String = "$ruleVersion:$mode:${nextTransitionEpochMillis ?: "none"}"

    fun shouldRecord(previousToken: String?, currentToken: String): Boolean =
        previousToken != currentToken
}
