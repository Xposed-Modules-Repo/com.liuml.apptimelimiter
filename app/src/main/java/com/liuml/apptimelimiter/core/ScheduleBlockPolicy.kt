package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ScheduleMode

/** Stable identity for one continuous blocked schedule interval. */
object ScheduleBlockPolicy {
    fun token(
        ruleVersion: Long,
        groupVersion: Long,
        mode: ScheduleMode,
        nextTransitionEpochMillis: Long?,
    ): String = "$ruleVersion:$groupVersion:$mode:${nextTransitionEpochMillis ?: "none"}"

    fun shouldRecord(previousToken: String?, currentToken: String): Boolean =
        previousToken != currentToken
}
