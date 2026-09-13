package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopOnDebuggerPolicyTest {
    @Test
    fun `requests a device id only for an enabled debug build with no saved id`() {
        assertTrue(TopOnDebuggerPolicy.shouldRequestDeviceId(true, true, ""))
        assertFalse(TopOnDebuggerPolicy.shouldRequestDeviceId(false, true, ""))
        assertFalse(TopOnDebuggerPolicy.shouldRequestDeviceId(true, false, ""))
        assertFalse(TopOnDebuggerPolicy.shouldRequestDeviceId(true, true, "device"))
    }

    @Test
    fun `applies debugger only for an enabled debug build with a saved id`() {
        assertTrue(TopOnDebuggerPolicy.shouldApplyDebugger(true, true, "device"))
        assertFalse(TopOnDebuggerPolicy.shouldApplyDebugger(false, true, "device"))
        assertFalse(TopOnDebuggerPolicy.shouldApplyDebugger(true, true, ""))
    }
}
