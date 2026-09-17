package com.liuml.apptimelimiter.core

enum class QuotaKind {
    APP_DAILY,
    APP_PER_LAUNCH,
    GROUP_DAILY,
    GROUP_PER_LAUNCH,
}

data class SharedCooldownRecord(
    val startedAtMillis: Long = 0L,
    val endsAtMillis: Long = 0L,
    val incidentId: String = "",
    val sourcePackage: String = "",
    val startedAtElapsedMillis: Long = 0L,
    val endsAtElapsedMillis: Long = 0L,
    val bootCount: Int = -1,
    // In-memory proof supplied by the boot-validating read boundary; never deserialize this.
    val validatedBootCount: Int = -1,
)

enum class SharedCooldownClaimStatus {
    STARTED,
    ABSORBED_BY_ACTIVE,
    ALREADY_HANDLED,
    HANDLED_WITHOUT_COOLDOWN,
}

data class SharedCooldownClaim(
    val status: SharedCooldownClaimStatus,
    val record: SharedCooldownRecord,
    val handledIncidentIds: List<String>,
) {
    val isNewIncident: Boolean
        get() = status != SharedCooldownClaimStatus.ALREADY_HANDLED

    val cooldownStarted: Boolean
        get() = status == SharedCooldownClaimStatus.STARTED
}

object QuotaIncidentPolicy {
    fun incidentId(
        packageName: String,
        ruleVersion: Long,
        groupId: String,
        groupVersion: Long,
        dayToken: String,
        processSessionId: String,
        reachedKinds: Set<QuotaKind>,
    ): String? = when {
        QuotaKind.GROUP_DAILY in reachedKinds ->
            "group-daily|$groupId|$groupVersion|$dayToken"
        QuotaKind.GROUP_PER_LAUNCH in reachedKinds ->
            "group-launch|$groupId|$groupVersion|$processSessionId"
        QuotaKind.APP_DAILY in reachedKinds ->
            "app-daily|$packageName|$ruleVersion|$dayToken"
        QuotaKind.APP_PER_LAUNCH in reachedKinds ->
            "app-launch|$packageName|$ruleVersion|$processSessionId"
        else -> null
    }
}

object SharedCooldownPolicy {
    const val MAX_HANDLED_INCIDENTS = 64

    fun claim(
        existingRecord: SharedCooldownRecord,
        handledIncidentIds: List<String>,
        incidentId: String,
        sourcePackage: String,
        occurredAtMillis: Long,
        durationMillis: Long,
        nowMillis: Long,
        nowElapsedMillis: Long = 0L,
        nowBootCount: Int = -1,
    ): SharedCooldownClaim {
        val handled = handledIncidentIds
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
            .takeLast(MAX_HANDLED_INCIDENTS)
            .toMutableList()
        if (incidentId in handled) {
            return SharedCooldownClaim(
                SharedCooldownClaimStatus.ALREADY_HANDLED,
                existingRecord,
                handled,
            )
        }
        appendBounded(handled, incidentId)
        if (durationMillis <= 0L) {
            return SharedCooldownClaim(
                SharedCooldownClaimStatus.HANDLED_WITHOUT_COOLDOWN,
                SharedCooldownRecord(),
                handled,
            )
        }
        val activeRemaining = remainingMillisDual(existingRecord, nowMillis, nowElapsedMillis, nowBootCount)
        if (activeRemaining > 0L) {
            return SharedCooldownClaim(
                SharedCooldownClaimStatus.ABSORBED_BY_ACTIVE,
                existingRecord,
                handled,
            )
        }
        val safeStart = occurredAtMillis.coerceIn(
            (nowMillis - durationMillis).coerceAtLeast(0L),
            nowMillis,
        )
        val safeEnd = safeAdd(safeStart, durationMillis)
        val record = if (safeEnd > nowMillis) {
            SharedCooldownRecord(
                startedAtMillis = safeStart,
                endsAtMillis = safeEnd,
                incidentId = incidentId,
                sourcePackage = sourcePackage,
                bootCount = nowBootCount,
                validatedBootCount = nowBootCount,
                startedAtElapsedMillis = nowElapsedMillis,
                endsAtElapsedMillis = if (nowElapsedMillis > 0L) {
                    safeAdd(nowElapsedMillis, safeEnd - nowMillis)
                } else {
                    0L
                },
            )
        } else {
            SharedCooldownRecord()
        }
        return SharedCooldownClaim(
            if (record.endsAtMillis > nowMillis) {
                SharedCooldownClaimStatus.STARTED
            } else {
                SharedCooldownClaimStatus.HANDLED_WITHOUT_COOLDOWN
            },
            record,
            handled,
        )
    }

    fun remainingMillis(record: SharedCooldownRecord, nowMillis: Long): Long =
        (record.endsAtMillis - nowMillis).coerceAtLeast(0L)

    /** Uses the monotonic clock while it is trustworthy, and the wall clock as a bounded check. */
    fun remainingMillisDual(
        record: SharedCooldownRecord,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
        nowBootCount: Int = record.validatedBootCount,
    ): Long {
        val duration = (record.endsAtMillis - record.startedAtMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
        val wallRemaining = (record.endsAtMillis - nowWallMillis.coerceAtLeast(0L)).coerceIn(0L, duration)
        // An unavailable boot identity must never validate an old monotonic timestamp.
        if (nowBootCount < 0 && record.endsAtMillis > 0L) return duration
        val elapsedRemaining = if (
            record.bootCount >= 0 && record.bootCount == nowBootCount &&
            record.startedAtElapsedMillis >= 0L &&
            record.endsAtElapsedMillis >= record.startedAtElapsedMillis &&
            nowElapsedMillis >= record.startedAtElapsedMillis
        ) {
            (record.endsAtElapsedMillis - nowElapsedMillis).coerceAtLeast(0L)
        } else {
            wallRemaining
        }
        // Wall-clock changes cannot shorten or extend a valid monotonic deadline.
        return elapsedRemaining
    }

    /** Persist the returned value before exposing it to callers. Wall fields retain incident time. */
    fun rebase(record: SharedCooldownRecord, nowWallMillis: Long, nowElapsedMillis: Long,
        nowBootCount: Int): SharedCooldownRecord {
        if (record.endsAtMillis <= 0L) return record
        check(nowBootCount >= 0) { "Cooldown boot identity unavailable" }
        if (record.bootCount == nowBootCount && record.startedAtElapsedMillis >= 0L &&
            record.endsAtElapsedMillis >= record.startedAtElapsedMillis &&
            nowElapsedMillis >= record.startedAtElapsedMillis) return record.copy(validatedBootCount = nowBootCount)
        val remaining = remainingMillisDual(record, nowWallMillis, nowElapsedMillis, nowBootCount)
        return record.copy(startedAtElapsedMillis = nowElapsedMillis,
            endsAtElapsedMillis = safeAdd(nowElapsedMillis, remaining), bootCount = nowBootCount,
            validatedBootCount = nowBootCount)
    }

    fun wallAndElapsedAreConsistent(
        record: SharedCooldownRecord,
        nowWallMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        if (record.startedAtElapsedMillis <= 0L || record.endsAtElapsedMillis <= 0L) return true
        val elapsed = (nowElapsedMillis - record.startedAtElapsedMillis).coerceAtLeast(0L)
        val wall = (nowWallMillis - record.startedAtMillis).coerceAtLeast(0L)
        return kotlin.math.abs(elapsed - wall) <= CLOCK_SKEW_TOLERANCE_MILLIS
    }

    private fun appendBounded(target: MutableList<String>, incidentId: String) {
        target.remove(incidentId)
        target.add(incidentId)
        while (target.size > MAX_HANDLED_INCIDENTS) target.removeAt(0)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private const val CLOCK_SKEW_TOLERANCE_MILLIS = 5_000L
}
