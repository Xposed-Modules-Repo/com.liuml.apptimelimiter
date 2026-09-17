package com.liuml.apptimelimiter.statistics

import java.security.MessageDigest
import java.time.LocalDate

/** Fixed-size identities, bounded daily shards, and a monotonic retention floor. */
internal object StatisticsLedgerPolicy {
    const val MAX_EVENTS_PER_DAY = 4096
    const val MAX_RESERVATIONS_PER_DAY = 4096
    fun floor(previous: Long, today: LocalDate): Long =
        maxOf(previous, today.minusDays(31).toEpochDay())

    fun accepts(day: LocalDate, today: LocalDate, floor: Long): Boolean =
        day.toEpochDay() >= floor &&
            !day.isAfter(today.plusDays(1))

    fun canInsert(size: Int, present: Boolean, capacity: Int): Boolean = present || size < capacity

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun identity(day: String, packageName: String, eventId: String): String =
        digest(StatisticsEventIdentity.scoped(day, packageName, eventId))
}
