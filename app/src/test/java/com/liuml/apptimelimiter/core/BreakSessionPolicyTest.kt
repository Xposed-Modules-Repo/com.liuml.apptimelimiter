package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakSessionPolicyTest {
    @Test
    fun `fresh token is accepted exactly once`() {
        val issued = BreakSessionPolicy.issue(
            existing = emptyList(),
            token = "token-a",
            targetPackage = "app.a",
            nowMillis = 1_000L,
        )
        val first = BreakSessionPolicy.consume(
            issued,
            "token-a",
            "app.a",
            2_000L,
        )
        val second = BreakSessionPolicy.consume(
            first.records,
            "token-a",
            "app.a",
            2_001L,
        )

        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertTrue(first.records.isEmpty())
    }

    @Test
    fun `expired or wrong-package token is rejected`() {
        val issued = BreakSessionPolicy.issue(
            existing = emptyList(),
            token = "token-a",
            targetPackage = "app.a",
            nowMillis = 1_000L,
        )

        assertFalse(
            BreakSessionPolicy.consume(
                issued,
                "token-a",
                "app.b",
                2_000L,
            ).accepted,
        )
        assertFalse(
            BreakSessionPolicy.consume(
                issued,
                "token-a",
                "app.a",
                31_000L,
            ).accepted,
        )
    }

    @Test
    fun `records are encoded safely and bounded`() {
        var records = emptyList<BreakSessionRecord>()
        repeat(BreakSessionPolicy.MAX_RECORDS + 10) { index ->
            records = BreakSessionPolicy.issue(
                existing = records,
                token = "token-$index",
                targetPackage = "app.$index",
                nowMillis = index.toLong(),
            )
        }
        val decoded = BreakSessionPolicy.decode(BreakSessionPolicy.encode(records))

        assertEquals(BreakSessionPolicy.MAX_RECORDS, decoded.size)
        assertEquals("token-10", decoded.first().token)
        assertEquals("token-73", decoded.last().token)
    }
}
