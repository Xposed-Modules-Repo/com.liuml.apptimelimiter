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
    AD_REWARDED,
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
    val createdAtElapsedMillis: Long = 0L,
    val expiresAtElapsedMillis: Long = 0L,
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
    private var database: ManagerControlDatabase? = null
    private var storageHealthy = true
    private var dailyDay = ""
    private var dailyCount = 0
    private val quotaByDay = linkedMapOf<String, Int>()
    private var bootCount = -1
    private val completedReceipts = linkedSetOf<String>()

    private fun receipt(token: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun isAdRequired(nowMillis: Long, nowElapsedMillis: Long? = null): Boolean {
        val day = dayAt(nowMillis)
        if ((quotaByDay[day] ?: if (dailyDay == day) dailyCount else 0) >= 1) return true
        return overrides.values.any {
            it.pendingQuotaDay == day && it.pendingDurationMillis > 0L &&
                (nowElapsedMillis ?: SystemClock.elapsedRealtime()) in
                    it.grantedAtElapsedMillis until it.expiresAtElapsedMillis
        }
    }

    private fun countActivatedQuota(day: String) {
        // A reservation crossing midnight belongs to its original day, never overwrites a newer day.
        if (day.isBlank()) return
        quotaByDay[day] = ((quotaByDay[day] ?: if (dailyDay == day) dailyCount else 0).toLong() + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (quotaByDay.size > 400) quotaByDay.remove(quotaByDay.keys.minOrNull())
        if (day < dailyDay) return
        dailyCount = if (dailyDay == day)
            (dailyCount.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 1
        dailyDay = day
    }

    @Synchronized
    fun needsAdForCompletion(token: String, nowMillis: Long, nowElapsedMillis: Long): Boolean {
        val challenge = challenges[token] ?: return false
        return receipt(token) !in completedReceipts && challenge.uiConsumed &&
            challenge.status == ParentAuthStatus.WAITING && challenge.expiresAtMillis > nowMillis &&
            isAdRequired(nowMillis, nowElapsedMillis)
    }

    private fun dayAt(nowMillis: Long): String = java.time.Instant.ofEpochMilli(nowMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    /** Challenges, consumption, receipts and allowances share the runtime SQLite transaction. */
    @Synchronized
    fun initialize(context: Context) {
        if (persistentPrefs != null) return
        database = ManagerControlDatabase.get(context)
        persistentPrefs = database!!.preferences(PREFS_NAME)
        bootCount = android.provider.Settings.Global.getInt(context.contentResolver,
            android.provider.Settings.Global.BOOT_COUNT, -1)
        dailyDay = persistentPrefs!!.getString("quota_day", "").orEmpty()
        dailyCount = persistentPrefs!!.getInt("quota_count", 0).coerceAtLeast(0)
        completedReceipts.addAll(persistentPrefs!!.getStringSet("completed_receipts", emptySet()).orEmpty())
        restoreOverrides()
    }

    @Synchronized internal fun initializeForTests(db: ManagerControlDatabase, boot: Int) {
        resetForTests()
        database = db
        persistentPrefs = db.preferences(PREFS_NAME)
        bootCount = boot
        dailyDay = persistentPrefs!!.getString("quota_day", "").orEmpty()
        dailyCount = persistentPrefs!!.getInt("quota_count", 0)
        completedReceipts.addAll(persistentPrefs!!.getStringSet("completed_receipts", emptySet()).orEmpty())
        restoreOverrides()
    }

    /** Provider operation boundary: reject/exception rolls back both SQLite and working maps. */
    @Synchronized
    internal fun <T> atomicOperation(success: (T) -> Boolean, block: () -> T): T {
        val db = checkNotNull(database) { "Parent auth authority not initialized" }
        val oldChallenges = challenges.toMap()
        val oldOverrides = overrides.toMap()
        val oldReceipts = completedReceipts.toSet()
        val oldDay = dailyDay
        val oldCount = dailyCount
        val oldQuotas = quotaByDay.toMap()
        val oldHealthy = storageHealthy
        var rejected: T? = null
        try {
            return db.transaction {
                check(storageHealthy)
                val result = block()
                if (!success(result)) { rejected = result; throw RejectedOperation() }
                check(storageHealthy) { "Parent authority write failed" }
                // Keep queries transaction-consistent without rewriting the full authority JSON.
                // Values are immutable data classes; snapshots also cover pruning and quota rollover.
                if (challenges != oldChallenges || overrides != oldOverrides ||
                    completedReceipts != oldReceipts || quotaByDay != oldQuotas ||
                    dailyDay != oldDay || dailyCount != oldCount) {
                    check(persistOverrides()) { "Parent authority write failed" }
                }
                result
            }
        } catch (failure: Throwable) {
            challenges.clear(); challenges.putAll(oldChallenges)
            overrides.clear(); overrides.putAll(oldOverrides)
            completedReceipts.clear(); completedReceipts.addAll(oldReceipts)
            dailyDay = oldDay; dailyCount = oldCount; storageHealthy = oldHealthy
            quotaByDay.clear(); quotaByDay.putAll(oldQuotas)
            if (failure is RejectedOperation) {
                @Suppress("UNCHECKED_CAST")
                return rejected as T
            }
            throw failure
        }
    }

    private class RejectedOperation : RuntimeException()

    private fun event(challenge: ParentAuthChallenge, stage: RuntimeDiagnosticStage) {
        database?.emit(challenge.identity.packageName, challenge.incidentId, stage,
            if (challenge.status == ParentAuthStatus.DENIED) RuntimeDiagnosticResult.DENIED else RuntimeDiagnosticResult.ACCEPTED,
            if (stage == RuntimeDiagnosticStage.PIN_TIMEOUT) RuntimeDiagnosticReason.EXPIRED else RuntimeDiagnosticReason.NONE)
    }

    @Synchronized
    fun issue(
        token: String,
        identity: TemporaryOverrideIdentity,
        incidentId: String,
        reason: String,
        nowMillis: Long,
    ): ParentAuthChallenge {
        if (database?.hasTransaction() == false) return atomicOperation({ true }) {
            issue(token, identity, incidentId, reason, nowMillis)
        }
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
            createdAtElapsedMillis = if (database != null) SystemClock.elapsedRealtime() else 0L,
            expiresAtElapsedMillis = if (database != null) safeAdd(SystemClock.elapsedRealtime(), CHALLENGE_LIFETIME_MILLIS) else 0L,
        )
        challenges[token] = challenge
        while (challenges.size > MAX_RECORDS) challenges.remove(challenges.keys.first())
        event(challenge, RuntimeDiagnosticStage.PIN_ISSUED)
        return challenge
    }

    @Synchronized
    fun consumeForUi(token: String, nowMillis: Long): ParentAuthChallenge? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) { consumeForUi(token, nowMillis) }
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (existing.uiConsumed || existing.status != ParentAuthStatus.WAITING) return null
        val consumed = existing.copy(uiConsumed = true)
        challenges[token] = consumed
        event(consumed, RuntimeDiagnosticStage.PIN_CONSUMED)
        return consumed
    }

    @Synchronized
    fun complete(
        token: String,
        granted: Boolean,
        durationMillis: Long,
        nowMillis: Long,
        nowElapsedMillis: Long,
        enforceDailyAd: Boolean = false,
        deferActivation: Boolean = false,
        isIdentityCurrent: (TemporaryOverrideIdentity) -> Boolean = { true },
    ): ParentAuthCompletion? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) {
            complete(token, granted, durationMillis, nowMillis, nowElapsedMillis, enforceDailyAd, deferActivation, isIdentityCurrent)
        }
        if (!storageHealthy) return null
        prune(nowMillis)
        if (receipt(token) in completedReceipts) {
            val previous = challenges[token] ?: return null
            if (!granted || previous.status != ParentAuthStatus.GRANTED) return null
            val allowance = overrides[overrideKey(previous.identity)] ?: return null
            if (!TemporaryParentOverridePolicy.isValid(allowance, previous.identity, true, nowElapsedMillis)) return null
            if (!isIdentityCurrent(previous.identity)) return null
            return ParentAuthCompletion(previous, allowance)
        }
        val existing = challenges[token] ?: return null
        if (!granted && existing.uiConsumed && existing.status == ParentAuthStatus.DENIED)
            return ParentAuthCompletion(existing, null)
        if (!existing.uiConsumed || !existing.status.allowsCompletion(granted)) return null
        if (granted && enforceDailyAd && isAdRequired(nowMillis, nowElapsedMillis) &&
            existing.status != ParentAuthStatus.AD_REWARDED) return null
        if (granted && !isIdentityCurrent(existing.identity)) {
            challenges[token] = existing.copy(status = ParentAuthStatus.INVALID)
            return null
        }
        val status = if (granted) ParentAuthStatus.GRANTED else ParentAuthStatus.DENIED
        val completed = existing.copy(status = status)
        challenges[token] = completed
        val parentOverride = if (granted) {
            val previousOverrides = overrides.toMap()
            val previousDay = dailyDay
            val previousCount = dailyCount
            val previousReceipts = completedReceipts.toSet()
            completedReceipts.add(receipt(token))
            while (completedReceipts.size > MAX_RECORDS) completedReceipts.remove(completedReceipts.first())
            if (enforceDailyAd && !deferActivation && existing.status != ParentAuthStatus.AD_REWARDED) countActivatedQuota(dayAt(nowMillis))
            val created = TemporaryParentOverride(
                identity = existing.identity,
                grantedAtElapsedMillis = nowElapsedMillis,
                expiresAtElapsedMillis = safeAdd(nowElapsedMillis,
                    if (deferActivation) VERIFIED_AD_HANDOFF_LIFETIME_MILLIS else durationMillis.coerceAtLeast(1L)),
                pendingDurationMillis = if (deferActivation) durationMillis.coerceIn(1L, 3_600_000L) else 0L,
                pendingQuotaDay = if (enforceDailyAd && deferActivation && existing.status != ParentAuthStatus.AD_REWARDED) dayAt(nowMillis) else "",
            )
            overrides[overrideKey(existing.identity)] = created
            while (overrides.size > MAX_RECORDS) overrides.remove(overrides.keys.first())
            if (!persistOverrides()) {
                overrides.clear()
                overrides.putAll(previousOverrides)
                dailyDay = previousDay
                dailyCount = previousCount
                completedReceipts.clear()
                completedReceipts.addAll(previousReceipts)
                challenges[token] = existing
                return null
            }
            created
        } else {
            null
        }
        event(completed, RuntimeDiagnosticStage.PIN_COMPLETED)
        return ParentAuthCompletion(completed, parentOverride)
    }

    /** Records a successful PIN verification while the manager-owned restriction page shows an ad. */
    @Synchronized
    fun markVerifiedWaitingForAd(token: String, nowMillis: Long): ParentAuthChallenge? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) { markVerifiedWaitingForAd(token, nowMillis) }
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (existing.uiConsumed && existing.status == ParentAuthStatus.VERIFIED_WAITING_AD) return existing
        if (!existing.uiConsumed || existing.status != ParentAuthStatus.WAITING) return null
        return existing.copy(
            status = ParentAuthStatus.VERIFIED_WAITING_AD,
            expiresAtMillis = safeAdd(nowMillis, VERIFIED_AD_HANDOFF_LIFETIME_MILLIS),
            expiresAtElapsedMillis = if (database != null) safeAdd(SystemClock.elapsedRealtime(), VERIFIED_AD_HANDOFF_LIFETIME_MILLIS) else 0L,
        ).also { challenges[token] = it; event(it, RuntimeDiagnosticStage.PIN_WAITING_AD) }
    }

    /** An ad-gated challenge may only grant after the manager process records a reward. */
    @Synchronized
    fun markAdRewarded(token: String, nowMillis: Long): ParentAuthChallenge? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) { markAdRewarded(token, nowMillis) }
        prune(nowMillis)
        val existing = challenges[token] ?: return null
        if (existing.uiConsumed && existing.status == ParentAuthStatus.AD_REWARDED) return existing
        if (!existing.uiConsumed || existing.status != ParentAuthStatus.VERIFIED_WAITING_AD) return null
        return existing.copy(status = ParentAuthStatus.AD_REWARDED).also {
            challenges[token] = it; event(it, RuntimeDiagnosticStage.PIN_REWARDED)
        }
    }

    @Synchronized
    fun timeout(token: String, nowMillis: Long): ParentAuthChallenge? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) { timeout(token, nowMillis) }
        val existing = challenges[token] ?: return null
        if (existing.status != ParentAuthStatus.WAITING &&
            existing.status != ParentAuthStatus.VERIFIED_WAITING_AD &&
            existing.status != ParentAuthStatus.AD_REWARDED
        ) return existing
        val timedOut = existing.copy(status = ParentAuthStatus.TIMED_OUT)
        challenges[token] = timedOut
        event(timedOut, RuntimeDiagnosticStage.PIN_TIMEOUT)
        prune(nowMillis)
        return timedOut
    }

    @Synchronized
    fun status(token: String, packageName: String, sessionId: String, nowMillis: Long): ParentAuthStatus {
        if (database?.hasTransaction() == false) return atomicOperation({ true }) { status(token, packageName, sessionId, nowMillis) }
        prune(nowMillis)
        val challenge = challenges[token] ?: return ParentAuthStatus.INVALID
        if (
            challenge.identity.packageName != packageName ||
            challenge.identity.processSessionId != sessionId
        ) return ParentAuthStatus.INVALID
        return challenge.status
    }

    @Synchronized
    fun challenge(token: String): ParentAuthChallenge? = challenges[token]

    @Synchronized
    fun pendingOverride(identity: TemporaryOverrideIdentity, nowElapsedMillis: Long): TemporaryParentOverride? =
        overrides[overrideKey(identity)]?.takeIf {
            storageHealthy && it.pendingDurationMillis > 0L && it.identity.processSessionId == identity.processSessionId &&
                TemporaryParentOverridePolicy.isValid(it, identity, true, nowElapsedMillis)
        }

    /** Read-only: neither a query nor an old Hook's activation hint may spend a reservation. */
    @Synchronized
    fun getOverride(
        identity: TemporaryOverrideIdentity,
        screenInteractive: Boolean,
        nowMillis: Long,
        nowElapsedMillis: Long,
        activate: Boolean = false,
    ): TemporaryParentOverride? {
        if (!storageHealthy) return null
        val granted = overrides[overrideKey(identity)] ?: return null
        val valid = TemporaryParentOverridePolicy.isValid(
            granted,
            identity,
            screenInteractive,
            nowElapsedMillis,
        )
        if (!valid || granted.pendingDurationMillis > 0L) return null
        return granted
    }

    /** Called only after the Provider independently verifies foreground evidence. */
    @Synchronized
    fun activateOverride(
        identity: TemporaryOverrideIdentity,
        screenInteractive: Boolean,
        nowElapsedMillis: Long,
        foregroundVerified: Boolean,
    ): TemporaryParentOverride? {
        if (database?.hasTransaction() == false) return atomicOperation({ it != null }) {
            activateOverride(identity, screenInteractive, nowElapsedMillis, foregroundVerified)
        }
        if (!storageHealthy || !foregroundVerified || !screenInteractive) return null
        val granted = overrides[overrideKey(identity)] ?: return null
        if (!TemporaryParentOverridePolicy.isValid(granted, identity, true, nowElapsedMillis)) return null
        if (granted.pendingDurationMillis > 0L) {
            if (granted.identity.processSessionId != identity.processSessionId) return null
            val previousDay = dailyDay
            val previousCount = dailyCount
            countActivatedQuota(granted.pendingQuotaDay)
            val activated = granted.copy(grantedAtElapsedMillis = nowElapsedMillis,
                expiresAtElapsedMillis = safeAdd(nowElapsedMillis, granted.pendingDurationMillis),
                pendingDurationMillis = 0L, pendingQuotaDay = "")
            overrides[overrideKey(identity)] = activated
            if (!persistOverrides()) {
                overrides[overrideKey(identity)] = granted
                dailyDay = previousDay
                dailyCount = previousCount
                return null
            }
            database?.emit(identity.packageName, challenges.values.lastOrNull { it.identity == granted.identity }?.incidentId.orEmpty(),
                RuntimeDiagnosticStage.PIN_ACTIVATED)
            return activated
        }
        return granted
    }

    @Synchronized
    fun revoke(packageName: String, sessionId: String): Boolean {
        if (database?.hasTransaction() == false) return atomicOperation({ true }) { revoke(packageName, sessionId) }
        val removed = overrides.entries.removeAll { (_, override) ->
            override.identity.packageName == packageName &&
                override.identity.processSessionId == sessionId
        }
        if (removed) challenges.values.filter { it.identity.packageName == packageName &&
            it.identity.processSessionId == sessionId }.forEach { event(it, RuntimeDiagnosticStage.PIN_REVOKED) }
        return removed && persistOverrides()
    }

    @Synchronized
    fun hasAllowance(identity: TemporaryOverrideIdentity, nowElapsedMillis: Long): Boolean =
        overrides[overrideKey(identity)]?.let {
            storageHealthy && TemporaryParentOverridePolicy.isValid(it, identity, true, nowElapsedMillis)
        } == true

    @Synchronized
    fun revokePackage(packageName: String) {
        if (database?.hasTransaction() == false) return atomicOperation({ true }) { revokePackage(packageName) }
        overrides.keys.filter { it.startsWith("$packageName|") }.forEach(overrides::remove)
        challenges.values.filter { it.identity.packageName == packageName }.forEach { event(it, RuntimeDiagnosticStage.PIN_REVOKED) }
        persistOverrides()
    }

    @Synchronized
    fun clear() {
        if (database == null) { challenges.clear(); overrides.clear(); return }
        atomicOperation({ true }) {
            challenges.values.forEach { event(it, RuntimeDiagnosticStage.PIN_CLEARED) }
            challenges.clear()
            overrides.clear()
            database?.preferences(ControlRuntimeStore.PREFS_NAME)?.edit()?.clear()?.commit()
        }
    }

    /** Isolates the in-memory authority for local JVM tests; never called by runtime code. */
    @Synchronized
    internal fun resetForTests() {
        challenges.clear()
        overrides.clear()
        completedReceipts.clear()
        dailyDay = ""
        dailyCount = 0
        quotaByDay.clear()
        persistentPrefs = null
        database = null
        storageHealthy = true
        bootCount = -1
    }

    private fun prune(nowMillis: Long) {
        val expiredWaiting = challenges.values.filter {
            (it.expiresAtMillis <= nowMillis || nowMillis < it.createdAtMillis ||
                (it.expiresAtElapsedMillis > 0L && (SystemClock.elapsedRealtime() < it.createdAtElapsedMillis ||
                    SystemClock.elapsedRealtime() >= it.expiresAtElapsedMillis))) &&
                (it.status == ParentAuthStatus.WAITING ||
                    it.status == ParentAuthStatus.VERIFIED_WAITING_AD ||
                    it.status == ParentAuthStatus.AD_REWARDED)
        }
        expiredWaiting.forEach {
            challenges[it.token] = it.copy(status = ParentAuthStatus.TIMED_OUT)
            event(it, RuntimeDiagnosticStage.PIN_TIMEOUT)
        }
        challenges.entries.removeAll { (_, value) ->
            value.status != ParentAuthStatus.WAITING &&
                nowMillis - value.expiresAtMillis > CHALLENGE_LIFETIME_MILLIS
        }
    }

    private fun ParentAuthStatus.allowsCompletion(granted: Boolean): Boolean = when {
        !granted -> this == ParentAuthStatus.WAITING || this == ParentAuthStatus.VERIFIED_WAITING_AD
        else -> this == ParentAuthStatus.WAITING || this == ParentAuthStatus.AD_REWARDED
    }

    private fun overrideKey(identity: TemporaryOverrideIdentity): String =
        "${identity.packageName}|${identity.ruleVersion}|${identity.groupVersion}|${identity.protectionModeGeneration}"

    private fun restoreOverrides() {
        quotaByDay.clear()
        if (dailyDay.isNotBlank()) quotaByDay[dailyDay] = dailyCount
        persistentPrefs?.getString("quota_by_day", null)?.let { raw ->
            val days = JSONObject(raw)
            days.keys().forEach { day -> quotaByDay[day] = days.getInt(day).coerceAtLeast(0) }
        }
        val raw = persistentPrefs?.getString(KEY_OVERRIDES, null).orEmpty()
        if (raw.isBlank()) return
        val restored: List<TemporaryParentOverride> = runCatching {
            val root = JSONObject(raw)
            if (bootCount < 0 || root.optInt("boot_count", -2) != bootCount) return@runCatching emptyList<TemporaryParentOverride>()
            val savedWallMillis = root.optLong(JSON_SAVED_WALL_MILLIS, Long.MIN_VALUE)
            val savedElapsedMillis = root.optLong(JSON_SAVED_ELAPSED_MILLIS, Long.MIN_VALUE)
            val wallElapsedDelta = (System.currentTimeMillis() - savedWallMillis) -
                (SystemClock.elapsedRealtime() - savedElapsedMillis)
            if (
                savedWallMillis <= 0L || savedElapsedMillis < 0L ||
                SystemClock.elapsedRealtime() < savedElapsedMillis ||
                (bootCount < 0 && abs(wallElapsedDelta) > MAX_CLOCK_DELTA_MILLIS)
            ) {
                return@runCatching emptyList<TemporaryParentOverride>()
            }
            if (bootCount >= 0) root.optJSONArray("challenges")?.let { entries ->
                for (index in 0 until entries.length()) {
                    val c = entries.getJSONObject(index)
                    val identity = TemporaryOverrideIdentity(c.getString("package"), c.getString("session"),
                        c.getLong("rule"), c.getLong("group"), c.getLong("mode"))
                    val challenge = ParentAuthChallenge(c.getString("token"), identity, c.getString("incident"),
                        c.getString("reason"), c.getLong("created"), c.getLong("expires"),
                        c.getBoolean("consumed"), ParentAuthStatus.valueOf(c.getString("status")),
                        c.optLong("created_elapsed", 0L), c.optLong("expires_elapsed", 0L))
                    // Wall-clock rollback must not extend a restored challenge.
                    if (System.currentTimeMillis() >= challenge.createdAtMillis &&
                        System.currentTimeMillis() < challenge.expiresAtMillis && abs(wallElapsedDelta) <= MAX_CLOCK_DELTA_MILLIS &&
                        SystemClock.elapsedRealtime() >= challenge.createdAtElapsedMillis &&
                        SystemClock.elapsedRealtime() < challenge.expiresAtElapsedMillis)
                        challenges[challenge.token] = challenge
                }
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
                            add(TemporaryParentOverride(identity, grantedAt, expiresAt,
                                value.optLong("pending_duration", 0L).coerceIn(0L, 3_600_000L),
                                value.optString("pending_quota_day", "")))
                        }
                    }
                }
            } ?: emptyList<TemporaryParentOverride>()
        }.getOrElse {
            storageHealthy = false
            challenges.clear()
            emptyList()
        }
        restored.takeLast(MAX_RECORDS).forEach { override ->
            overrides[overrideKey(override.identity)] = override
        }
    }

    private fun persistOverrides(): Boolean = runCatching {
        if (!storageHealthy) return false
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
                    .put("expires", override.expiresAtElapsedMillis)
                    .put("pending_duration", override.pendingDurationMillis)
                    .put("pending_quota_day", override.pendingQuotaDay),
            )
        }
        val root = JSONObject().apply {
            put("boot_count", bootCount)
            put(JSON_SAVED_WALL_MILLIS, System.currentTimeMillis())
            put(JSON_SAVED_ELAPSED_MILLIS, SystemClock.elapsedRealtime())
            put(JSON_ITEMS, encoded)
            put("challenges", JSONArray().apply {
                challenges.values.forEach { c -> put(JSONObject()
                    .put("token", c.token).put("package", c.identity.packageName)
                    .put("session", c.identity.processSessionId).put("rule", c.identity.ruleVersion)
                    .put("group", c.identity.groupVersion).put("mode", c.identity.protectionModeGeneration)
                    .put("incident", c.incidentId).put("reason", c.reason)
                    .put("created", c.createdAtMillis).put("expires", c.expiresAtMillis)
                    .put("created_elapsed", c.createdAtElapsedMillis).put("expires_elapsed", c.expiresAtElapsedMillis)
                    .put("consumed", c.uiConsumed).put("status", c.status.name)) }
            })
        }
        val saved = prefs.edit().putString(KEY_OVERRIDES, root.toString())
            .putString("quota_by_day", JSONObject(quotaByDay.toMap()).toString())
            .putStringSet("completed_receipts", completedReceipts.toSet())
            .putString("quota_day", dailyDay).putInt("quota_count", dailyCount).commit()
        if (!saved) storageHealthy = false
        saved
    }.getOrElse { storageHealthy = false; false }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
