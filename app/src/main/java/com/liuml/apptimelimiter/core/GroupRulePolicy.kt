package com.liuml.apptimelimiter.core

object GroupRulePolicy {
    fun hasTimedQuota(
        appDailyEnabled: Boolean,
        appPerLaunchEnabled: Boolean,
        groupDailyEnabled: Boolean,
        groupPerLaunchEnabled: Boolean,
    ): Boolean = appDailyEnabled || appPerLaunchEnabled ||
        groupDailyEnabled || groupPerLaunchEnabled

    fun effectiveCooldownMillis(
        appEnabled: Boolean,
        appDurationMillis: Long,
        groupEnabled: Boolean,
        groupDurationMillis: Long,
    ): Long = maxOf(
        appDurationMillis.takeIf { appEnabled }?.coerceAtLeast(0L) ?: 0L,
        groupDurationMillis.takeIf { groupEnabled }?.coerceAtLeast(0L) ?: 0L,
    )
}
