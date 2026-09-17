package com.liuml.apptimelimiter.statistics

/** Event IDs are supplied by different packages and are only unique within one natural day. */
object StatisticsEventIdentity {
    fun transport(value: String): String = if (value.length <= 160) value else {
        "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun isValid(value: String): Boolean = value.trim().let {
        it.isNotEmpty() && it.length <= 160 && it.none { char -> char.isISOControl() }
    }

    fun scoped(day: String, packageName: String, eventId: String): String =
        "v2:$day:${packageName.length}:$packageName:$eventId"
}
