package com.liuml.apptimelimiter.diagnostics

import java.security.MessageDigest

data class DiagnosticTimelineEvent(
    val id: Long,
    val packageName: String,
    val incident: String,
    val stage: String,
    val result: String,
    val reason: String,
    val wallMillis: Long,
)

data class DiagnosticIncident(
    val packageName: String,
    val incident: String,
    val lastId: Long,
    val wallMillis: Long,
    val eventCount: Int,
)

/** Only fixed identifiers enter the structured log. Free-form SDK messages never do. */
object DiagnosticTimelinePolicy {
    const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MAX_EVENTS = 10_000
    const val PAGE_SIZE = 50
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_.]{0,95}")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun validPackage(value: String): Boolean = value.length <= 256 && packagePattern.matches(value)
    fun code(value: String): String = value.takeIf { identifier.matches(it) } ?: "UNKNOWN"
    fun incidentKey(packageName: String, incident: String): String? {
        if (!validPackage(packageName) || incident.isBlank() || incident.length > 240) return null
        return digest("${packageName.length}:$packageName:$incident")
    }
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
