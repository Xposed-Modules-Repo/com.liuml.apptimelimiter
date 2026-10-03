package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class ExtensionPreviewTest {
    @Test fun previewShowsAvailableCountWithoutConsumingIt() {
        val state = ExtensionQuotaState("today", 4, 4, "session", 1)
        val preview = ExtensionQuotaPolicy.preview(state, "today", "session", 10, 3)
        val claim = ExtensionQuotaPolicy.claimFree(state, "today", "session", 10, 3, 10)
        assertEquals(6, preview.remainingCount)
        assertEquals(2, preview.remainingSessionCount)
        assertEquals(preview.remainingCount - 1, claim.remainingCount)
        assertEquals(4, state.dailyUsedCount)
    }
    @Test fun dayAndSessionResetFollowConsumptionPolicy() {
        val state = ExtensionQuotaState("yesterday", 10, 10, "old", 3)
        assertEquals(10, ExtensionQuotaPolicy.preview(state, "today", "new", 10, 3).remainingCount)
        assertEquals(3, ExtensionQuotaPolicy.preview(state, "today", "new", 10, 3).remainingSessionCount)
        assertFalse(ExtensionQuotaPolicy.preview(state, "yesterday", "old", 10, 3).allowed)
    }
}
