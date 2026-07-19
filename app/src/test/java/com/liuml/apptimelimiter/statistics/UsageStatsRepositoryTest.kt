package com.liuml.apptimelimiter.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatsRepositoryTest {
    private val today = LocalDate.of(2026, 7, 20)

    @Test
    fun `explicit event day is preserved across midnight`() {
        assertEquals(
            "2026-07-19",
            normalizedUsageDayToken("2026-07-19", today),
        )
    }

    @Test
    fun `missing event day remains compatible with old hook`() {
        assertEquals("2026-07-20", normalizedUsageDayToken(null, today))
    }

    @Test
    fun `invalid or unreasonable event days are rejected`() {
        assertNull(normalizedUsageDayToken("not-a-date", today))
        assertNull(normalizedUsageDayToken("2026-06-18", today))
        assertNull(normalizedUsageDayToken("2026-07-22", today))
    }
}
