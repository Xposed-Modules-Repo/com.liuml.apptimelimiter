package com.liuml.apptimelimiter.nonroot

import android.content.Context
import android.provider.Settings
import com.liuml.apptimelimiter.core.SharedCooldownClaim
import com.liuml.apptimelimiter.core.SharedCooldownPolicy
import com.liuml.apptimelimiter.core.SharedCooldownRecord
import java.time.LocalDate

class NonRootRuntimeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSession(packageName: String): NonRootSessionState? {
        if (packageName.isBlank()) return null
        val prefix = sessionPrefix(packageName)
        val sessionId = prefs.getString("${prefix}id", null).orEmpty()
        if (sessionId.isBlank()) return null
        val storedBootCount = prefs.getInt("${prefix}boot_count", Int.MIN_VALUE)
        if (storedBootCount != bootCount()) {
            clearSession(packageName)
            return null
        }
        return NonRootSessionState(
            packageName = packageName,
            sessionId = sessionId,
            protectionModeGeneration = prefs.getLong(
                "${prefix}protection_mode_generation",
                -1L,
            ),
            accumulatedForegroundMillis = prefs.getLong(
                "${prefix}accumulated_ms",
                0L,
            ).coerceAtLeast(0L),
            foregroundStartedAtElapsedMillis = prefs.getLong(
                "${prefix}foreground_started_elapsed",
                0L,
            ).coerceAtLeast(0L),
            foregroundDayToken = prefs.getString(
                "${prefix}foreground_day_token",
                null,
            ).orEmpty(),
            backgroundedAtElapsedMillis = prefs.getLong(
                "${prefix}backgrounded_elapsed",
                0L,
            ).coerceAtLeast(0L),
            graceEndsAtElapsedMillis = prefs.getLong(
                "${prefix}grace_ends_elapsed",
                0L,
            ).coerceAtLeast(0L),
            launchRecorded = prefs.getBoolean("${prefix}launch_recorded", false),
            planPromptHandled = prefs.getBoolean("${prefix}plan_prompt_handled", false),
            planActive = prefs.getBoolean("${prefix}plan_active", false),
            planRemainingMillis = prefs.getLong(
                "${prefix}plan_remaining_ms",
                0L,
            ).coerceAtLeast(0L),
        )
    }

    fun saveSession(state: NonRootSessionState): Boolean {
        if (state.packageName.isBlank() || state.sessionId.isBlank()) return false
        val prefix = sessionPrefix(state.packageName)
        return prefs.edit()
            .putString("${prefix}id", state.sessionId)
            .putInt("${prefix}boot_count", bootCount())
            .putLong(
                "${prefix}protection_mode_generation",
                state.protectionModeGeneration,
            )
            .putLong(
                "${prefix}accumulated_ms",
                state.accumulatedForegroundMillis.coerceAtLeast(0L),
            )
            .putLong(
                "${prefix}foreground_started_elapsed",
                state.foregroundStartedAtElapsedMillis.coerceAtLeast(0L),
            )
            .putString("${prefix}foreground_day_token", state.foregroundDayToken)
            .putLong(
                "${prefix}backgrounded_elapsed",
                state.backgroundedAtElapsedMillis.coerceAtLeast(0L),
            )
            .putLong(
                "${prefix}grace_ends_elapsed",
                state.graceEndsAtElapsedMillis.coerceAtLeast(0L),
            )
            .putBoolean("${prefix}launch_recorded", state.launchRecorded)
            .putBoolean("${prefix}plan_prompt_handled", state.planPromptHandled)
            .putBoolean("${prefix}plan_active", state.planActive)
            .putLong(
                "${prefix}plan_remaining_ms",
                state.planRemainingMillis.coerceAtLeast(0L),
            )
            .commit()
    }

    /**
     * Repairs sessions that were persisted as foreground when this process was killed without a
     * normal accessibility-service shutdown callback. Returns only sessions successfully repaired.
     */
    fun recoverInterruptedSessions(nowElapsedMillis: Long): List<String> {
        val packages = prefs.all.keys.asSequence()
            .filter { it.startsWith(SESSION_PREFIX) && it.endsWith(SESSION_ID_SUFFIX) }
            .map { key ->
                key.removePrefix(SESSION_PREFIX).removeSuffix(SESSION_ID_SUFFIX)
            }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        return packages.mapNotNull { packageName ->
            val state = loadSession(packageName) ?: return@mapNotNull null
            if (state.foregroundStartedAtElapsedMillis <= 0L) return@mapNotNull null
            val recovered = NonRootSessionPolicy.recoverAfterProcessLoss(
                state,
                nowElapsedMillis,
            )
            packageName.takeIf { saveSession(recovered) }
        }
    }

    fun clearSession(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val prefix = sessionPrefix(packageName)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        return editor.commit()
    }

    fun clearAllSessions(): Boolean {
        val editor = prefs.edit()
        prefs.all.keys.filter {
            it.startsWith(SESSION_PREFIX) || it.startsWith("restriction.")
        }.forEach(editor::remove)
        return editor.commit()
    }

    fun markRestrictionActive(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val prefix = restrictionPrefix(packageName)
        return prefs.edit()
            .putBoolean("${prefix}active", true)
            .putInt("${prefix}boot_count", bootCount())
            .commit()
    }

    fun hasActiveRestriction(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val prefix = restrictionPrefix(packageName)
        if (!prefs.getBoolean("${prefix}active", false)) return false
        if (prefs.getInt("${prefix}boot_count", Int.MIN_VALUE) == bootCount()) {
            return true
        }
        clearActiveRestriction(packageName)
        return false
    }

    fun clearActiveRestriction(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val prefix = restrictionPrefix(packageName)
        return prefs.edit()
            .remove("${prefix}active")
            .remove("${prefix}boot_count")
            .commit()
    }

    fun getAppCooldown(packageName: String): SharedCooldownRecord {
        if (packageName.isBlank()) return SharedCooldownRecord()
        val prefix = cooldownPrefix(packageName)
        return SharedCooldownRecord(
            startedAtMillis = prefs.getLong("${prefix}started_at", 0L),
            endsAtMillis = prefs.getLong("${prefix}ends_at", 0L),
            incidentId = prefs.getString("${prefix}incident_id", null).orEmpty(),
            sourcePackage = packageName,
        )
    }

    fun consumeExpiredAppCooldown(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): SharedCooldownRecord? = synchronized(COOLDOWN_LOCK) {
        val record = getAppCooldown(packageName)
        if (record.endsAtMillis <= 0L || record.endsAtMillis > nowMillis) {
            return@synchronized null
        }
        val prefix = cooldownPrefix(packageName)
        val persisted = prefs.edit()
            .remove("${prefix}started_at")
            .remove("${prefix}ends_at")
            .remove("${prefix}incident_id")
            .commit()
        if (persisted) record else null
    }

    fun claimAppCooldown(
        packageName: String,
        incidentId: String,
        occurredAtMillis: Long,
        durationMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): SharedCooldownClaim = synchronized(COOLDOWN_LOCK) {
        val prefix = cooldownPrefix(packageName)
        val handled = prefs.getString("${prefix}handled", null)
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val claim = SharedCooldownPolicy.claim(
            existingRecord = getAppCooldown(packageName),
            handledIncidentIds = handled,
            incidentId = incidentId,
            sourcePackage = packageName,
            occurredAtMillis = occurredAtMillis,
            durationMillis = durationMillis,
            nowMillis = nowMillis,
        )
        val editor = prefs.edit()
            .putString("${prefix}handled", claim.handledIncidentIds.joinToString("\n"))
        if (claim.record.endsAtMillis > nowMillis) {
            editor
                .putLong("${prefix}started_at", claim.record.startedAtMillis)
                .putLong("${prefix}ends_at", claim.record.endsAtMillis)
                .putString("${prefix}incident_id", claim.record.incidentId)
        } else {
            editor
                .remove("${prefix}started_at")
                .remove("${prefix}ends_at")
                .remove("${prefix}incident_id")
        }
        check(editor.commit()) { "Failed to persist non-root cooldown" }
        claim
    }

    fun claimLimitIncident(incidentId: String): Boolean = synchronized(INCIDENT_LOCK) {
        val existing = prefs.getString(KEY_HANDLED_LIMIT_INCIDENTS, null)
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val claim = PersistentIncidentPolicy.claim(
            existingIncidentIds = existing,
            incidentId = incidentId,
            maxEntries = MAX_HANDLED_LIMIT_INCIDENTS,
        )
        if (!claim.isNewIncident) return@synchronized false
        check(
            prefs.edit()
                .putString(
                    KEY_HANDLED_LIMIT_INCIDENTS,
                    claim.handledIncidentIds.joinToString("\n"),
                )
                .commit(),
        ) {
            "Failed to persist non-root limit incident"
        }
        true
    }

    fun dayToken(): String = LocalDate.now().toString()

    private fun bootCount(): Int = Settings.Global.getInt(
        appContext.contentResolver,
        Settings.Global.BOOT_COUNT,
        -1,
    )

    private fun sessionPrefix(packageName: String) = "$SESSION_PREFIX$packageName."

    private fun cooldownPrefix(packageName: String) = "cooldown.$packageName."

    private fun restrictionPrefix(packageName: String) = "restriction.$packageName."

    private companion object {
        const val PREFS_NAME = "non_root_runtime"
        const val SESSION_PREFIX = "session."
        const val SESSION_ID_SUFFIX = ".id"
        const val KEY_HANDLED_LIMIT_INCIDENTS = "handled_limit_incidents"
        const val MAX_HANDLED_LIMIT_INCIDENTS = 256
        val COOLDOWN_LOCK = Any()
        val INCIDENT_LOCK = Any()
    }
}
