package com.liuml.apptimelimiter.security

import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import com.liuml.apptimelimiter.core.TemporaryParentOverride
import com.liuml.apptimelimiter.core.TemporaryParentOverridePolicy

enum class ParentAuthStatus {
    WAITING,
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
    private const val MAX_RECORDS = 64
    private val challenges = linkedMapOf<String, ParentAuthChallenge>()
    private val overrides = linkedMapOf<String, TemporaryParentOverride>()

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
        if (!existing.uiConsumed || existing.status != ParentAuthStatus.WAITING) return null
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
            created
        } else {
            null
        }
        return ParentAuthCompletion(completed, parentOverride)
    }

    @Synchronized
    fun timeout(token: String, nowMillis: Long): ParentAuthChallenge? {
        val existing = challenges[token] ?: return null
        if (existing.status != ParentAuthStatus.WAITING) return existing
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
            return null
        }
        return granted
    }

    @Synchronized
    fun revoke(packageName: String, sessionId: String): Boolean =
        overrides.remove("$packageName|$sessionId") != null

    @Synchronized
    fun revokePackage(packageName: String) {
        overrides.keys.filter { it.startsWith("$packageName|") }.forEach(overrides::remove)
    }

    @Synchronized
    fun clear() {
        challenges.clear()
        overrides.clear()
    }

    private fun prune(nowMillis: Long) {
        val expiredWaiting = challenges.values.filter {
            it.expiresAtMillis <= nowMillis && it.status == ParentAuthStatus.WAITING
        }
        expiredWaiting.forEach { challenges[it.token] = it.copy(status = ParentAuthStatus.TIMED_OUT) }
        challenges.entries.removeAll { (_, value) ->
            value.status != ParentAuthStatus.WAITING &&
                nowMillis - value.expiresAtMillis > CHALLENGE_LIFETIME_MILLIS
        }
    }

    private fun overrideKey(identity: TemporaryOverrideIdentity): String =
        "${identity.packageName}|${identity.processSessionId}"

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
