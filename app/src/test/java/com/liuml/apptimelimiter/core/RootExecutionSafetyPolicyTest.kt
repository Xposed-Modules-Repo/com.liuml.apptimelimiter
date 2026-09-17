package com.liuml.apptimelimiter.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RootExecutionSafetyPolicyTest {
    @After fun clearInterrupt() { Thread.interrupted() }

    @Test fun `validation denial stays rejected but service and storage exceptions fall back`() {
        assertNull(RootExecutionSafetyPolicy.validate { true })
        assertEquals(RestrictionExecutionResult.REJECTED, RootExecutionSafetyPolicy.validate { false })
        for (failure in listOf(SecurityException("resolveActivity"), IllegalStateException("RuleRepository"))) {
            assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, RootExecutionSafetyPolicy.validate { throw failure })
        }
    }

    @Test fun `validation interruption remains visible to caller`() {
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, RootExecutionSafetyPolicy.validate { throw InterruptedException() })
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test fun `failed claimed execution cannot become already executed`() {
        val ledger = RestrictionExecutionLedger()
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, RootExecutionSafetyPolicy.execute(ledger, "failed") {
            throw SecurityException("verification failed")
        })
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, ledger.begin("failed"))
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, RootExecutionSafetyPolicy.execute(ledger, "failed") {
            fail("Duplicate must not execute")
            RestrictionExecutionResult.EXECUTED
        })
    }

    @Test fun `success completes ledger and suppresses duplicate command`() {
        val ledger = RestrictionExecutionLedger()
        assertEquals(RestrictionExecutionResult.EXECUTED, RootExecutionSafetyPolicy.execute(ledger, "ok") {
            RestrictionExecutionResult.EXECUTED
        })
        assertEquals(RestrictionExecutionResult.ALREADY_EXECUTED, ledger.begin("ok"))
    }

    @Test fun `interrupted claimed execution completes as fallback`() {
        val ledger = RestrictionExecutionLedger()
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, RootExecutionSafetyPolicy.execute(ledger, "interrupted") {
            throw InterruptedException()
        })
        assertTrue(Thread.currentThread().isInterrupted)
        assertEquals(RestrictionExecutionResult.FALLBACK_REQUIRED, ledger.begin("interrupted"))
    }

    @Test fun `command success and nonzero exit both clean up`() {
        for (code in listOf(0, 7)) {
            val child = FakeProcess(code = code)
            assertEquals(code, RootExecutionSafetyPolicy.runCommand(123) { child })
            assertTrue(child.destroyed)
            assertEquals(123L, child.timeout)
        }
    }

    @Test fun `timeout destroys child without unbounded wait`() {
        val child = FakeProcess(completed = false)
        assertEquals(-1, RootExecutionSafetyPolicy.runCommand(123) { child })
        assertTrue(child.destroyed)
    }

    @Test fun `interrupted wait destroys child and restores interrupt`() {
        val child = FakeProcess(failure = InterruptedException())
        assertEquals(-1, RootExecutionSafetyPolicy.runCommand(123) { child })
        assertTrue(child.destroyed)
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test fun `wait and spawn failures are fallback`() {
        val child = FakeProcess(failure = IllegalStateException())
        assertEquals(-1, RootExecutionSafetyPolicy.runCommand(123) { child })
        assertTrue(child.destroyed)
        assertEquals(-1, RootExecutionSafetyPolicy.runCommand(123) { throw java.io.IOException() })
    }

    @Test fun `already interrupted thread never spawns a command`() {
        Thread.currentThread().interrupt()
        assertEquals(-1, RootExecutionSafetyPolicy.runCommand(123) { error("Must not start") })
        assertTrue(Thread.currentThread().isInterrupted)
    }

    private class FakeProcess(
        val completed: Boolean = true,
        val code: Int = 0,
        val failure: Exception? = null,
    ) : Process() {
        var destroyed = false
        var timeout = 0L
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("Unbounded wait is forbidden")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            this.timeout = unit.toMillis(timeout)
            failure?.let { throw it }
            return completed
        }
        override fun exitValue(): Int = code
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }
}
