package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookProcessOwnershipPolicyTest {
    @Test
    fun `host package owns main and private secondary processes`() {
        assertTrue(HookProcessOwnershipPolicy.ownsProcess("app.target", "app.target"))
        assertTrue(HookProcessOwnershipPolicy.ownsProcess("app.target", "app.target:player"))
    }

    @Test
    fun `webview package loaded inside target process cannot install limiter`() {
        assertFalse(
            HookProcessOwnershipPolicy.ownsProcess(
                packageName = "com.google.android.webview",
                processName = "app.target",
            ),
        )
        assertFalse(HookProcessOwnershipPolicy.ownsProcess("app.target", "other.process"))
        assertFalse(HookProcessOwnershipPolicy.ownsProcess("", ""))
    }
}
