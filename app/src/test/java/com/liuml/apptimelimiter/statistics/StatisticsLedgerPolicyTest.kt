package com.liuml.apptimelimiter.statistics

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StatisticsLedgerPolicyTest {
    private val today = LocalDate.of(2026, 9, 17)

    @Test fun retentionIncludesEntireRetryWindowAndTomorrow() {
        val floor = StatisticsLedgerPolicy.floor(Long.MIN_VALUE, today)
        assertTrue(StatisticsLedgerPolicy.accepts(today.minusDays(31), today, floor))
        assertFalse(StatisticsLedgerPolicy.accepts(today.minusDays(32), today, floor))
        assertTrue(StatisticsLedgerPolicy.accepts(today.plusDays(1), today, floor))
        assertFalse(StatisticsLedgerPolicy.accepts(today.plusDays(2), today, floor))
    }

    @Test fun wallClockRollbackCannotReopenPrunedDays() {
        val floor = StatisticsLedgerPolicy.floor(Long.MIN_VALUE, today)
        assertEquals(floor, StatisticsLedgerPolicy.floor(floor, today.minusDays(10)))
        assertFalse(StatisticsLedgerPolicy.accepts(today.minusDays(32), today.minusDays(10), floor))
    }

    @Test fun capacityRejectsNewIdentityButKeepsDuplicates() {
        assertTrue(StatisticsLedgerPolicy.canInsert(4095, false, 4096))
        assertFalse(StatisticsLedgerPolicy.canInsert(4096, false, 4096))
        assertTrue(StatisticsLedgerPolicy.canInsert(4096, true, 4096))
    }

    @Test fun upgradeDoesNotCloseTodayOrValidDelayedRequests() {
        val floor = today.minusDays(31).toEpochDay()
        assertTrue(StatisticsLedgerPolicy.accepts(today, today, floor))
        assertTrue(StatisticsLedgerPolicy.accepts(today.minusDays(1), today, floor))
    }

    @Test fun digestHasFixedSizeAndScopesPackagesAndDays() {
        val first = StatisticsLedgerPolicy.identity(today.toString(), "app.a", "event")
        assertEquals(64, first.length)
        assertEquals(first, StatisticsLedgerPolicy.identity(today.toString(), "app.a", "event"))
        assertNotEquals(first, StatisticsLedgerPolicy.identity(today.toString(), "app.b", "event"))
        assertNotEquals(first, StatisticsLedgerPolicy.identity(today.minusDays(1).toString(), "app.a", "event"))
    }
}
