package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppThemeColor

object ThemeColorPolicy {
    fun parse(raw: String?): AppThemeColor =
        raw?.let { runCatching { AppThemeColor.valueOf(it) }.getOrNull() }
            ?: AppThemeColor.GREEN
}
