package com.liuml.apptimelimiter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumedActivityRegistryTest {
    @Test
    fun `activity handoff remains foreground while another activity is resumed`() {
        val registry = ResumedActivityRegistry<Any>()
        val first = Any()
        val second = Any()

        assertTrue(registry.markResumed(first))
        assertTrue(registry.markResumed(second))
        assertTrue(registry.markPaused(first))

        assertFalse(registry.isEmpty)
        assertEquals(1, registry.size)
        assertSame(second, registry.anyOrNull())
    }

    @Test
    fun `duplicate lifecycle callbacks are ignored`() {
        val registry = ResumedActivityRegistry<Any>()
        val activity = Any()

        assertTrue(registry.markResumed(activity))
        assertFalse(registry.markResumed(activity))
        assertTrue(registry.markPaused(activity))
        assertFalse(registry.markPaused(activity))
        assertTrue(registry.isEmpty)
        assertNull(registry.anyOrNull())
    }

    @Test
    fun `equal objects are tracked by identity`() {
        data class Host(val value: Int)

        val registry = ResumedActivityRegistry<Host>()
        val first = Host(1)
        val second = Host(1)

        assertTrue(registry.markResumed(first))
        assertTrue(registry.markResumed(second))
        assertEquals(2, registry.size)
        assertTrue(registry.contains(first))
        assertTrue(registry.contains(second))
    }
}
