package com.liuml.apptimelimiter.security

import com.liuml.apptimelimiter.core.TemporaryOverrideIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentAuthStoreTest {
    private val identity = TemporaryOverrideIdentity("app.a", "session-a", 1L, 2L, 3L)

    @After
    fun clear() = ParentAuthStore.clear()

    @Test
    fun `challenge is package bound and consumed once`() {
        ParentAuthStore.issue("token", identity, "incident", "QUOTA", 1_000L)
        assertNotNull(ParentAuthStore.consumeForUi("token", 2_000L))
        assertNull(ParentAuthStore.consumeForUi("token", 2_001L))
        assertEquals(
            ParentAuthStatus.INVALID,
            ParentAuthStore.status("token", "app.b", "session-a", 2_002L),
        )
    }

    @Test
    fun `successful challenge creates only matching temporary override`() {
        ParentAuthStore.issue("token", identity, "incident", "QUOTA", 1_000L)
        ParentAuthStore.consumeForUi("token", 2_000L)
        ParentAuthStore.complete("token", true, 60_000L, 2_001L, 10_000L)
        assertNotNull(ParentAuthStore.getOverride(identity, true, 2_002L, 10_001L))
        assertNull(
            ParentAuthStore.getOverride(
                identity = identity.copy(ruleVersion = 9L),
                screenInteractive = true,
                nowMillis = 2_002L,
                nowElapsedMillis = 10_001L,
            ),
        )
        assertFalse(ParentAuthStore.revoke("app.a", "session-a"))
        assertNull(ParentAuthStore.getOverride(identity, true, 2_003L, 10_002L))
    }

    @Test
    fun `expired waiting challenge times out and cannot grant`() {
        ParentAuthStore.issue("token", identity, "incident", "SCHEDULE", 1_000L)
        assertEquals(
            ParentAuthStatus.TIMED_OUT,
            ParentAuthStore.status("token", "app.a", "session-a", 32_000L),
        )
        assertNull(ParentAuthStore.complete("token", true, 60_000L, 32_001L, 1_000L))
    }

    @Test
    fun `override expires on monotonic deadline and duplicate challenge invalidates old one`() {
        ParentAuthStore.issue("old", identity, "incident-1", "QUOTA", 1_000L)
        ParentAuthStore.issue("new", identity, "incident-2", "QUOTA", 1_100L)
        assertEquals(
            ParentAuthStatus.INVALID,
            ParentAuthStore.status("old", "app.a", "session-a", 1_200L),
        )
        ParentAuthStore.consumeForUi("new", 1_300L)
        ParentAuthStore.complete("new", true, 60_000L, 1_400L, 5_000L)
        assertNotNull(ParentAuthStore.getOverride(identity, true, 1_500L, 64_999L))
        assertNull(ParentAuthStore.getOverride(identity, true, 1_600L, 65_000L))
    }
}
