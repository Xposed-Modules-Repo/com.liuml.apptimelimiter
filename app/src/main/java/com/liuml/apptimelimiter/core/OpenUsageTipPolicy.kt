package com.liuml.apptimelimiter.core

object OpenUsageTipPolicy {
    const val DEDUP_MILLIS = 30_000L
    fun mayShow(now: Long, last: Long?): Boolean =
        last == null || (now >= last && now - last >= DEDUP_MILLIS)

    fun message(label: String, todayMillis: Long?, remainingMillis: Long?, pin: Boolean, english: Boolean): String {
        fun duration(value: Long): String {
            val minutes = value.coerceIn(0L, 86_400_000L) / 60_000L
            if (minutes == 0L) return if (english) "less than 1 min" else "不足 1 分钟"
            val hours = minutes / 60
            val rest = minutes % 60
            return if (english) {
                if (hours == 0L) "$rest min" else if (rest == 0L) "$hours h" else "$hours h $rest min"
            } else {
                if (hours == 0L) "$rest 分钟" else if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分钟"
            }
        }
        val parts = mutableListOf<String>()
        todayMillis?.takeIf { it >= 0L }?.let {
            parts += if (english) "$label: ${duration(it)} used today" else "今天已使用 $label ${duration(it)}"
        }
        remainingMillis?.takeIf { it > 0L && it < Long.MAX_VALUE }?.let {
            parts += if (english) "${if (pin) "PIN allowance" else "Available time"}: ${duration(it)} left"
                else "${if (pin) "临时放行" else "可用时间"}剩余 ${duration(it)}"
        }
        return parts.joinToString(" · ")
    }
}
