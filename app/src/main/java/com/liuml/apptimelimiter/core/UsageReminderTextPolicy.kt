package com.liuml.apptimelimiter.core

data class UsageReminderDisplay(
    val appLabel: String,
    val todayMillis: Long?,
    val snapshot: RuleDecisionSnapshot,
    val pinRemainingMillis: Long? = null,
)

/** Display only: consumes the same immutable balances used by enforcement. */
object UsageReminderTextPolicy {
    fun message(data: UsageReminderDisplay, english: Boolean): String = buildList {
        data.todayMillis?.takeIf { it >= 0L }?.let {
            add(if (english) "${data.appLabel} · Used today: ${duration(it, true)}"
                else "${data.appLabel} · 今日累计使用 ${duration(it, false)}")
        }
        fun balance(zh: String, en: String, value: Long?) {
            value?.takeIf { it >= 0L && it < Long.MAX_VALUE / 2 }?.let {
                add(if (it == 0L) { if (english) "$en: exhausted" else "$zh：已用完" }
                    else if (english) "$en: ${duration(it, true)} left" else "$zh：还剩 ${duration(it, false)}")
            }
        }
        if (data.pinRemainingMillis != null) {
            balance("临时放行有效时间", "Temporary access", data.pinRemainingMillis)
        } else with(data.snapshot) {
            if (grouped) {
                balance("分组本轮共享额度", "Shared group session allowance", groupPerLaunchRemainingMillis)
                balance("分组今日共享额度", "Shared group daily allowance", groupDailyRemainingMillis)
            } else {
                fun tighter(app: Long?, group: Long?): Pair<Long?, Boolean> =
                    if (group != null && (app == null || group < app)) group to true else app to false
                val session = tighter(appPerLaunchRemainingMillis, groupPerLaunchRemainingMillis)
                val daily = tighter(appDailyRemainingMillis, groupDailyRemainingMillis)
                balance(if (session.second) "分组本轮共享额度" else "本轮可用时间", if (session.second) "Shared group session allowance" else "Session allowance", session.first)
                balance(if (daily.second) "分组今日共享额度" else "今日可用额度", if (daily.second) "Shared group daily allowance" else "Daily allowance", daily.first)
            }
            balance("本次计划时间", "Current plan", planRemainingMillis)
        }
    }.joinToString("\n")

    private fun duration(value: Long, english: Boolean): String {
        val minutes = value.coerceIn(0L, 86_400_000L) / 60_000L
        val hours = minutes / 60
        val rest = minutes % 60
        return if (english) when {
            minutes == 0L -> "less than 1 min"
            hours == 0L -> "$rest min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        } else when {
            minutes == 0L -> "不足 1 分钟"
            hours == 0L -> "$rest 分钟"
            rest == 0L -> "$hours 小时"
            else -> "$hours 小时 $rest 分钟"
        }
    }
}
