package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import com.liuml.apptimelimiter.data.ForceStopEnhancement

enum class RestrictionExecutorType {
    XPOSED,
    ACCESSIBILITY,
    SHIZUKU,
    ROOT,
}

enum class RestrictionExecutionResult {
    EXECUTED,
    FALLBACK_REQUIRED,
    REJECTED,
    ALREADY_EXECUTED,
    FAILED,
}

data class RestrictionRequest(
    val packageName: String,
    val groupId: String = "",
    val userId: Int,
    val reason: String,
    val incidentId: String,
    val ruleVersion: Long,
    val groupVersion: Long,
    val modeGeneration: Long,
    val foregroundPackage: String?,
    val foregroundGeneration: Long,
    val sessionId: String,
    val allowDelay: Boolean,
    val allowPin: Boolean,
    val allowAd: Boolean,
)

interface RestrictionExecutor {
    fun execute(request: RestrictionRequest): RestrictionExecutionResult
    fun cancel(requestId: String)
    fun isAvailable(): Boolean
}

object RestrictionExecutorSelectionPolicy {
    fun select(
        mode: ProtectionMode,
        accessibilityEnhancement: ForceStopEnhancement,
    ): RestrictionExecutorType = when (mode) {
        ProtectionMode.XPOSED -> RestrictionExecutorType.XPOSED
        ProtectionMode.ACCESSIBILITY -> when (accessibilityEnhancement) {
            ForceStopEnhancement.NONE -> RestrictionExecutorType.ACCESSIBILITY
            ForceStopEnhancement.ROOT -> RestrictionExecutorType.ROOT
            ForceStopEnhancement.SHIZUKU -> RestrictionExecutorType.SHIZUKU
        }
    }

    fun fallbackFor(type: RestrictionExecutorType): RestrictionExecutorType? = when (type) {
        RestrictionExecutorType.ROOT,
        RestrictionExecutorType.SHIZUKU,
        -> RestrictionExecutorType.ACCESSIBILITY
        else -> null
    }
}

class RestrictionIncidentDeduplicator(private val maxEntries: Int = 128) {
    private val executed = LinkedHashSet<String>()

    @Synchronized
    fun claim(incidentId: String): Boolean {
        if (incidentId.isBlank() || executed.contains(incidentId)) return false
        executed += incidentId
        while (executed.size > maxEntries.coerceAtLeast(1)) {
            executed.remove(executed.first())
        }
        return true
    }
}
