package com.liuml.apptimelimiter.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticUpdatePolicyTest {
    @Test
    fun `automatic check is enabled immediately then rate limited`() {
        val now = 1_000_000_000L
        assertTrue(AutomaticUpdatePolicy.shouldCheck(true, now, 0L))
        assertFalse(AutomaticUpdatePolicy.shouldCheck(false, now, 0L))
        assertFalse(
            AutomaticUpdatePolicy.shouldCheck(
                true,
                now,
                now - AutomaticUpdatePolicy.CHECK_INTERVAL_MILLIS + 1L,
            ),
        )
        assertTrue(
            AutomaticUpdatePolicy.shouldCheck(
                true,
                now,
                now - AutomaticUpdatePolicy.CHECK_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun `new release prompts immediately and same release reminds daily`() {
        val now = 2_000_000_000L
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("22-0.9.7", now, null, 0L))
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("22-0.9.7", now, "21-0.9.6", now))
        assertFalse(
            AutomaticUpdatePolicy.shouldPrompt(
                "22-0.9.7",
                now,
                "22-0.9.7",
                now - AutomaticUpdatePolicy.REMINDER_INTERVAL_MILLIS + 1L,
            ),
        )
        assertTrue(
            AutomaticUpdatePolicy.shouldPrompt(
                "22-0.9.7",
                now,
                "22-0.9.7",
                now - AutomaticUpdatePolicy.REMINDER_INTERVAL_MILLIS,
            ),
        )
    }

    @Test
    fun `clock rollback allows a safe recheck and reprompt`() {
        assertTrue(AutomaticUpdatePolicy.shouldCheck(true, 1_000L, 2_000L))
        assertTrue(AutomaticUpdatePolicy.shouldPrompt("v1.0.0", 1_000L, "v1.0.0", 2_000L))
    }
}
