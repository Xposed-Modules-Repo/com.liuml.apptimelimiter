package com.liuml.apptimelimiter.update

object AutomaticUpdatePolicy {

    fun shouldCheck(
        enabled: Boolean,
        nowMillis: Long,
        lastCheckAtMillis: Long,
    ): Boolean {
        return enabled && nowMillis > 0L
    }

    fun shouldPrompt(
        releaseVersion: String,
        nowMillis: Long,
        lastPromptedVersion: String?,
        lastPromptedAtMillis: Long,
    ): Boolean {
        if (releaseVersion.isBlank() || nowMillis <= 0L) return false
        if (releaseVersion != lastPromptedVersion) return true
        if (lastPromptedAtMillis <= 0L) return true
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val promptedDay = java.time.Instant.ofEpochMilli(lastPromptedAtMillis).atZone(zone).toLocalDate()
        return today.isAfter(promptedDay)
    }
}
