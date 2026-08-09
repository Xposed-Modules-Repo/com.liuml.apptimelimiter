package com.liuml.apptimelimiter.update

object AutomaticUpdatePolicy {
    const val CHECK_INTERVAL_MILLIS = 12L * 60L * 60L * 1_000L
    const val REMINDER_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

    fun shouldCheck(
        enabled: Boolean,
        nowMillis: Long,
        lastCheckAtMillis: Long,
    ): Boolean {
        if (!enabled || nowMillis <= 0L) return false
        if (lastCheckAtMillis <= 0L || nowMillis < lastCheckAtMillis) return true
        return nowMillis - lastCheckAtMillis >= CHECK_INTERVAL_MILLIS
    }

    fun shouldPrompt(
        releaseVersion: String,
        nowMillis: Long,
        lastPromptedVersion: String?,
        lastPromptedAtMillis: Long,
    ): Boolean {
        if (releaseVersion.isBlank() || nowMillis <= 0L) return false
        if (releaseVersion != lastPromptedVersion) return true
        if (lastPromptedAtMillis <= 0L || nowMillis < lastPromptedAtMillis) return true
        return nowMillis - lastPromptedAtMillis >= REMINDER_INTERVAL_MILLIS
    }
}
