package com.liuml.apptimelimiter.statistics

import org.junit.Assert.*
import org.junit.Test

class StatisticsEventIdentityTest {
    @Test fun `prefixing a maximum length incident preserves a valid unique transport ID`() {
        val first = StatisticsEventIdentity.transport("limit:" + "a".repeat(160))
        assertTrue(StatisticsEventIdentity.isValid(first))
        assertEquals(first, StatisticsEventIdentity.transport("limit:" + "a".repeat(160)))
        assertNotEquals(first, StatisticsEventIdentity.transport("limit:" + "a".repeat(159) + "b"))
        assertEquals("limit:short", StatisticsEventIdentity.transport("limit:short"))
    }
    @Test fun `same ID in different packages or dates must not suppress statistics`() {
        val key = StatisticsEventIdentity.scoped("2026-09-17", "app.a", "event")
        assertEquals(key, StatisticsEventIdentity.scoped("2026-09-17", "app.a", "event"))
        assertNotEquals(key, StatisticsEventIdentity.scoped("2026-09-17", "app.b", "event"))
        assertNotEquals(key, StatisticsEventIdentity.scoped("2026-09-18", "app.a", "event"))
    }

    @Test fun `invalid event IDs cannot silently become non idempotent writes`() {
        assertFalse(StatisticsEventIdentity.isValid(" "))
        assertFalse(StatisticsEventIdentity.isValid("x".repeat(161)))
        assertFalse(StatisticsEventIdentity.isValid("a\nb"))
        assertTrue(StatisticsEventIdentity.isValid("x".repeat(160)))
    }
}
