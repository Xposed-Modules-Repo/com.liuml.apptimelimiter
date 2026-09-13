package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.ProtectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRuntimeMachineTest {
    private val token = ControlSessionToken("demo.app", "", "session", 1, 1, ProtectionMode.XPOSED, 1, 1)

    @Test fun `stale callback cannot transition incident`() {
        val machine = ControlRuntimeMachine("incident", token)
        val stale = token.copy(foregroundGeneration = 2)
        assertFalse(machine.transition(ControlRuntimeState.LIMIT_CLAIMED, stale))
        assertTrue(machine.transition(ControlRuntimeState.LIMIT_CLAIMED, token))
    }

    @Test fun `incident claims are idempotent and bounded`() {
        val registry = IncidentClaimRegistry(2)
        assertTrue(registry.claim("a"))
        assertFalse(registry.claim("a"))
        assertTrue(registry.claim("b"))
        assertTrue(registry.claim("c"))
        assertTrue(registry.claim("a"))
    }
}
