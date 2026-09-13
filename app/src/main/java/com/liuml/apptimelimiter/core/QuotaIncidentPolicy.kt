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
        val activeRemaining = remainingMillisDual(existingRecord, nowMillis, nowElapsedMillis)
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
                startedAtElapsedMillis = nowElapsedMillis,
                endsAtElapsedMillis = if (nowElapsedMillis > 0L) {
                    nowElapsedMillis + durationMillis
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
    ): Long {
        val wallRemaining = (record.endsAtMillis - nowWallMillis).coerceAtLeast(0L)
        val elapsedRemaining = if (
            record.startedAtElapsedMillis > 0L &&
            record.endsAtElapsedMillis > record.startedAtElapsedMillis &&
            nowElapsedMillis >= record.startedAtElapsedMillis
        ) {
            (record.endsAtElapsedMillis - nowElapsedMillis).coerceAtLeast(0L)
        } else {
            wallRemaining
        }
        // A manually advanced wall clock must not extend a cooldown; a manually moved-back
        // clock must not make it longer than the monotonic deadline either.
        return minOf(wallRemaining, elapsedRemaining)
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
