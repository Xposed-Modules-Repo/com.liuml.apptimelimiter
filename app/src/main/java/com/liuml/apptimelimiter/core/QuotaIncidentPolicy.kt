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
            "group-launch|$groupId|$groupVersion|$packageName|$processSessionId"
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
        if (existingRecord.endsAtMillis > nowMillis) {
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

    private fun appendBounded(target: MutableList<String>, incidentId: String) {
        target.remove(incidentId)
        target.add(incidentId)
        while (target.size > MAX_HANDLED_INCIDENTS) target.removeAt(0)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
