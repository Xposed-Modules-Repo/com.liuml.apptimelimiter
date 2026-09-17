package com.liuml.apptimelimiter.core

data class BreakSessionRecord(
    val token: String,
    val targetPackage: String,
    val expiresAtMillis: Long,
    val expiresAtElapsedMillis: Long,
)

data class BreakSessionConsumeResult(
    val accepted: Boolean,
    val records: List<BreakSessionRecord>,
)

object BreakSessionPolicy {
    const val TOKEN_TTL_MILLIS = 30_000L
    const val MAX_RECORDS = 64
    const val MAX_RULE_READ_FAILURES = 3

    fun mayRestoreAuthorizedActivity(
        savedAuthorized: Boolean,
        targetPackage: String,
    ): Boolean = savedAuthorized && PackageNamePolicy.isValid(targetPackage)

    fun shouldFailClosedAfterRuleReadFailure(failureCount: Int): Boolean =
        failureCount >= MAX_RULE_READ_FAILURES

    fun issue(
        existing: Collection<BreakSessionRecord>,
        token: String,
        targetPackage: String,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): List<BreakSessionRecord> {
        if (token.isBlank() || targetPackage.isBlank()) return active(existing, nowMillis, nowElapsedMillis)
        return (
            active(existing, nowMillis, nowElapsedMillis)
                .filterNot { it.token == token } +
                BreakSessionRecord(
                    token = token,
                    targetPackage = targetPackage,
                    expiresAtMillis = safeAdd(nowMillis, TOKEN_TTL_MILLIS),
                    expiresAtElapsedMillis = safeAdd(nowElapsedMillis, TOKEN_TTL_MILLIS),
                )
            ).takeLast(MAX_RECORDS)
    }

    fun consume(
        existing: Collection<BreakSessionRecord>,
        token: String,
        targetPackage: String,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): BreakSessionConsumeResult {
        val current = active(existing, nowMillis, nowElapsedMillis)
        val accepted = current.any {
            it.token == token &&
                it.targetPackage == targetPackage &&
                isActive(it, nowMillis, nowElapsedMillis)
        }
        return BreakSessionConsumeResult(
            accepted = accepted,
            records = if (accepted) current.filterNot { it.token == token } else current,
        )
    }

    fun encode(records: Collection<BreakSessionRecord>): String = records.joinToString("\n") {
        "${it.token}\t${it.targetPackage}\t${it.expiresAtMillis}\t${it.expiresAtElapsedMillis}"
    }

    fun decode(raw: String?): List<BreakSessionRecord> = raw
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val expiresAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            val expiresAtElapsed = parts[3].toLongOrNull() ?: return@mapNotNull null
            BreakSessionRecord(
                token = parts[0],
                targetPackage = parts[1],
                expiresAtMillis = expiresAt,
                expiresAtElapsedMillis = expiresAtElapsed,
            ).takeIf {
                it.token.isNotBlank() &&
                    it.targetPackage.isNotBlank() &&
                    '\n' !in it.token &&
                    '\r' !in it.token &&
                    '\t' !in it.token &&
                    '\n' !in it.targetPackage &&
                    '\r' !in it.targetPackage &&
                    '\t' !in it.targetPackage
            }
        }
        .distinctBy(BreakSessionRecord::token)
        .toList()
        .takeLast(MAX_RECORDS)

    private fun active(
        records: Collection<BreakSessionRecord>,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): List<BreakSessionRecord> = records
        .asSequence()
        .filter { isActive(it, nowMillis, nowElapsedMillis) }
        .distinctBy(BreakSessionRecord::token)
        .toList()
        .takeLast(MAX_RECORDS)

    /** A wall-clock rollback or elapsed deadline expiry must never extend an authorization. */
    private fun isActive(
        record: BreakSessionRecord,
        nowMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean = record.expiresAtMillis > nowMillis && record.expiresAtElapsedMillis > nowElapsedMillis

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
