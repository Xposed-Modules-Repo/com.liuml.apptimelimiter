package com.liuml.apptimelimiter.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventLimiterTest {
    @Test
    fun `same state is merged inside window and accepted after window`() {
        val limiter = DiagnosticEventLimiter()
        assertTrue(limiter.shouldAccept("pkg|event|state", 1_000L, 5_000L))
        assertFalse(limiter.shouldAccept("pkg|event|state", 5_999L, 5_000L))
        assertTrue(limiter.shouldAccept("pkg|event|state", 6_000L, 5_000L))
    }

    @Test
    fun `different state remains visible`() {
        val limiter = DiagnosticEventLimiter()
        assertTrue(limiter.shouldAccept("pkg|event|old", 1_000L, 5_000L))
        assertTrue(limiter.shouldAccept("pkg|event|new", 1_001L, 5_000L))
    }
}
