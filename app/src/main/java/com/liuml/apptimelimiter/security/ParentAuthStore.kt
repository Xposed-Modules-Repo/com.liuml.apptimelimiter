package com.liuml.apptimelimiter.security

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import com.liuml.apptimelimiter.core.TemporaryParentOverride
import com.liuml.apptimelimiter.core.TemporaryParentOverridePolicy
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class ParentAuthStatus {
    WAITING,
    VERIFIED_WAITING_AD,
    GRANTED,
    DENIED,
    TIMED_OUT,
    INVALID,
}

data class ParentAuthChallenge(
    val token: String,
    val identity: TemporaryOverrideIdentity,
    val incidentId: String,
    val reason: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val uiConsumed: Boolean = false,
    val status: ParentAuthStatus = ParentAuthStatus.WAITING,
)

data class ParentAuthCompletion(
    val challenge: ParentAuthChallenge,
    val parentOverride: TemporaryParentOverride?,
)

/** Process-private authority shared by RuleProvider and Time Stop's secure PIN activity. */
object ParentAuthStore {
    const val CHALLENGE_LIFETIME_MILLIS = 30_000L
    private const val VERIFIED_AD_HANDOFF_LIFETIME_MILLIS = 2 * 60_000L
    private const val MAX_RECORDS = 64
    private const val PREFS_NAME = "parent_auth_runtime"
    private const val KEY_OVERRIDES = "temporary_overrides_v1"
    private const val JSON_ITEMS = "items"
    private const val JSON_SAVED_WALL_MILLIS = "saved_wall_millis"
    private const val JSON_SAVED_ELAPSED_MILLIS = "saved_elapsed_millis"
    private const val MAX_CLOCK_DELTA_MILLIS = 15_000L
    private val challenges = linkedMapOf<String, ParentAuthChallenge>()
    private val overrides = linkedMapOf<String, TemporaryParentOverride>()
    private var persistentPrefs: SharedPreferences? = null

    /**
     * Challenges remain process-local because they are single-use and expire in 30 seconds.
     * Completed overrides are restored from Time Stop's private storage after a manager-process
     * restart, so a valid fixed-duration PIN allowance does not disappear mid-session.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (persistentPrefs != null) return
        persistentPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreOverrides()
    }

    @Synchronized
    fun issue(
        token: String,
        identity: TemporaryOverrideIdentity,
        incidentId: String,
        reason: String,
        nowMillis: Long,
    ): ParentAuthChallenge {
        prune(nowMillis)
        challenges.entries.toList().forEach { (existingToken, existing) ->
            if (
                existing.status == ParentAuthStatus.WAITING &&
                existing.identity.packageName == identity.packageName &&
                existing.identity.processSessionId == identity.processSessionId
            ) {
                challenges[existingToken] = existing.copy(status = ParentAuthStatus.INVALID)
            }
        }
        val challenge = ParentAuthChallenge(
            token = token,
            identity = identity,
            incidentId = incidentId,
            reason = reason,
            createdAtMillis = nowMillis,
            expiresAtMillis = safeAdd(nowMillis, CHALLENGE_LIFETIME_MILLIS),
        )
        challenges[token] = challenge
        while (challenges.size > MAX_RECORDS) challenges.remove(challenges.keys.first())
        return challenge
    }

    @Synchronized
    fun consumeForUi(token: String, nowMillis: Long): ParentAuthChallenge? {
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (existing.uiConsumed || existing.status != ParentAuthStatus.WAITING) return null
        val consumed = existing.copy(uiConsumed = true)
        challenges[token] = consumed
        return consumed
    }

    @Synchronized
    fun complete(
        token: String,
        granted: Boolean,
        durationMillis: Long,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): ParentAuthCompletion? {
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (!existing.uiConsumed || !existing.status.allowsCompletion(granted)) return null
        val status = if (granted) ParentAuthStatus.GRANTED else ParentAuthStatus.DENIED
        val completed = existing.copy(status = status)
        challenges[token] = completed
        val parentOverride = if (granted) {
            val created = TemporaryParentOverride(
                identity = existing.identity,
                grantedAtElapsedMillis = nowElapsedMillis,
                expiresAtElapsedMillis = safeAdd(nowElapsedMillis, durationMillis.coerceAtLeast(1L)),
            )
            overrides[overrideKey(existing.identity)] = created
            while (overrides.size > MAX_RECORDS) overrides.remove(overrides.keys.first())
            if (!persistOverrides()) {
                overrides.remove(overrideKey(existing.identity))
                return null
            }
            created
        } else {
            null
        }
        return ParentAuthCompletion(completed, parentOverride)
    }

    /** Records a successful PIN verification while the manager-owned restriction page shows an ad. */
    @Synchronized
    fun markVerifiedWaitingForAd(token: String, nowMillis: Long): ParentAuthChallenge? {
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (!existing.uiConsumed || existing.status != ParentAuthStatus.WAITING) return null
        return existing.copy(
            status = ParentAuthStatus.VERIFIED_WAITING_AD,
            expiresAtMillis = safeAdd(nowMillis, VERIFIED_AD_HANDOFF_LIFETIME_MILLIS),
        ).also { challenges[token] = it }
    }

    @Synchronized
    fun timeout(token: String, nowMillis: Long): ParentAuthChallenge? {
        val existing = challenges[token] ?: return null
        if (existing.status != ParentAuthStatus.WAITING &&
            existing.status != ParentAuthStatus.VERIFIED_WAITING_AD
        ) return existing
        val timedOut = existing.copy(status = ParentAuthStatus.TIMED_OUT)
        challenges[token] = timedOut
        prune(nowMillis)
        return timedOut
    }

    @Synchronized
    fun status(token: String, packageName: String, sessionId: String, nowMillis: Long): ParentAuthStatus {
        prune(nowMillis)
        val challenge = challenges[token] ?: return ParentAuthStatus.INVALID
        if (
            challenge.identity.packageName != packageName ||
            challenge.identity.processSessionId != sessionId
        ) return ParentAuthStatus.INVALID
        return challenge.status
    }

    @Synchronized
    fun getOverride(
        identity: TemporaryOverrideIdentity,
        screenInteractive: Boolean,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): TemporaryParentOverride? {
        prune(nowMillis)
        val granted = overrides[overrideKey(identity)] ?: return null
        val valid = TemporaryParentOverridePolicy.isValid(
            granted,
            identity,
            screenInteractive,
            nowElapsedMillis,
        )
        if (!valid) {
            overrides.remove(overrideKey(identity))
            persistOverrides()
            return null
        }
        return granted
    }

    @Synchronized
    fun revoke(packageName: String, sessionId: String): Boolean {
        val removed = overrides.entries.removeAll { (_, override) ->
            override.identity.packageName == packageName &&
                override.identity.processSessionId == sessionId
        }
        return removed && persistOverrides()
    }

    @Synchronized
    fun revokePackage(packageName: String) {
        overrides.keys.filter { it.startsWith("$packageName|") }.forEach(overrides::remove)
        persistOverrides()
    }

    @Synchronized
    fun clear() {
        challenges.clear()
        overrides.clear()
        persistentPrefs?.edit()?.remove(KEY_OVERRIDES)?.commit()
    }

    private fun prune(nowMillis: Long) {
        val expiredWaiting = challenges.values.filter {
            it.expiresAtMillis <= nowMillis &&
                (it.status == ParentAuthStatus.WAITING || it.status == ParentAuthStatus.VERIFIED_WAITING_AD)
        }
        expiredWaiting.forEach { challenges[it.token] = it.copy(status = ParentAuthStatus.TIMED_OUT) }
        challenges.entries.removeAll { (_, value) ->
            value.status != ParentAuthStatus.WAITING &&
                nowMillis - value.expiresAtMillis > CHALLENGE_LIFETIME_MILLIS
        }
    }

    private fun ParentAuthStatus.allowsCompletion(granted: Boolean): Boolean = when {
        !granted -> this == ParentAuthStatus.WAITING || this == ParentAuthStatus.VERIFIED_WAITING_AD
        else -> this == ParentAuthStatus.WAITING || this == ParentAuthStatus.VERIFIED_WAITING_AD
    }

    private fun overrideKey(identity: TemporaryOverrideIdentity): String =
        "${identity.packageName}|${identity.ruleVersion}|${identity.groupVersion}|${identity.protectionModeGeneration}"

    private fun restoreOverrides() {
        val raw = persistentPrefs?.getString(KEY_OVERRIDES, null).orEmpty()
        if (raw.isBlank()) return
        val restored: List<TemporaryParentOverride> = runCatching {
            val root = JSONObject(raw)
            val savedWallMillis = root.optLong(JSON_SAVED_WALL_MILLIS, Long.MIN_VALUE)
            val savedElapsedMillis = root.optLong(JSON_SAVED_ELAPSED_MILLIS, Long.MIN_VALUE)
            val wallElapsedDelta = (System.currentTimeMillis() - savedWallMillis) -
                (SystemClock.elapsedRealtime() - savedElapsedMillis)
            if (
                savedWallMillis <= 0L || savedElapsedMillis < 0L ||
                abs(wallElapsedDelta) > MAX_CLOCK_DELTA_MILLIS
            ) {
                return@runCatching emptyList<TemporaryParentOverride>()
            }
            root.optJSONArray(JSON_ITEMS)?.let { encoded ->
                buildList<TemporaryParentOverride> {
                    for (index in 0 until encoded.length()) {
                        val value = encoded.optJSONObject(index) ?: continue
                        val identity = TemporaryOverrideIdentity(
                            packageName = value.optString("package"),
                            processSessionId = value.optString("session"),
                            ruleVersion = value.optLong("rule", Long.MIN_VALUE),
                            groupVersion = value.optLong("group", Long.MIN_VALUE),
                            protectionModeGeneration = value.optLong("mode", Long.MIN_VALUE),
                        )
                        val grantedAt = value.optLong("granted", Long.MIN_VALUE)
                        val expiresAt = value.optLong("expires", Long.MIN_VALUE)
                        if (
                            identity.packageName.isNotBlank() &&
                            identity.processSessionId.isNotBlank() &&
                            identity.ruleVersion != Long.MIN_VALUE &&
                            identity.groupVersion != Long.MIN_VALUE &&
                            identity.protectionModeGeneration != Long.MIN_VALUE &&
                            grantedAt >= 0L && expiresAt > grantedAt
                        ) {
                            add(TemporaryParentOverride(identity, grantedAt, expiresAt))
                        }
                    }
                }
            } ?: emptyList<TemporaryParentOverride>()
        }.getOrDefault(emptyList())
        restored.takeLast(MAX_RECORDS).forEach { override ->
            overrides[overrideKey(override.identity)] = override
        }
        if (restored.isEmpty()) persistentPrefs?.edit()?.remove(KEY_OVERRIDES)?.commit()
    }

    private fun persistOverrides(): Boolean {
        val prefs = persistentPrefs ?: return true
        val encoded = JSONArray()
        overrides.values.toList().takeLast(MAX_RECORDS).forEach { override ->
            encoded.put(
                JSONObject()
                    .put("package", override.identity.packageName)
                    .put("session", override.identity.processSessionId)
                    .put("rule", override.identity.ruleVersion)
                    .put("group", override.identity.groupVersion)
                    .put("mode", override.identity.protectionModeGeneration)
                    .put("granted", override.grantedAtElapsedMillis)
                    .put("expires", override.expiresAtElapsedMillis),
            )
        }
        val root = JSONObject().apply {
            put(JSON_SAVED_WALL_MILLIS, System.currentTimeMillis())
            put(JSON_SAVED_ELAPSED_MILLIS, SystemClock.elapsedRealtime())
            put(JSON_ITEMS, encoded)
        }
        return prefs.edit().putString(KEY_OVERRIDES, root.toString()).commit()
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
