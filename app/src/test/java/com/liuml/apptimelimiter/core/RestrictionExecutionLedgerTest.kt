package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RestrictionExecutionLedgerTest {
    @Test fun `failure and pending duplicates always require fallback`() {
        val ledger = RestrictionExecutionLedger()
        assertNull(ledger.begin("event"))
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, ledger.begin("event"))
        ledger.complete("event", RestrictionExecutionResult.FAILED)
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, ledger.begin("event"))
    }

    @Test fun `only verified success is reported as executed`() {
        val ledger = RestrictionExecutionLedger()
        assertNull(ledger.begin("event"))
        ledger.complete("event", RestrictionExecutionResult.EXECUTED)
        assertEquals(RestrictionExecutionResult.ALREADY_EXECUTED, ledger.begin("event"))
        assertNull(ledger.begin("other-app:event"))
    }

    @Test fun `concurrent attempts have exactly one executor`() {
        val ledger = RestrictionExecutionLedger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val attempts = pool.invokeAll((1..20).map { Callable { ledger.begin("event") } })
            assertEquals(1, attempts.count { it.get() == null })
        } finally { pool.shutdownNow() }
    }
}
