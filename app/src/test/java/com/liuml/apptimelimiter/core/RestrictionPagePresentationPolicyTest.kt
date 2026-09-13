package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionPagePresentationPolicyTest {
    @Test
    fun `temporary access is hidden when neither PIN nor ad is available`() {
        assertFalse(RestrictionPagePresentationPolicy.showTemporaryAccess(false, false))
        assertTrue(RestrictionPagePresentationPolicy.showTemporaryAccess(true, false))
        assertTrue(RestrictionPagePresentationPolicy.showTemporaryAccess(false, true))
    }

    @Test
    fun `consented ad request does not need a second confirmation`() {
        assertTrue(RestrictionPagePresentationPolicy.shouldRequestAdImmediately(true))
        assertFalse(RestrictionPagePresentationPolicy.shouldRequestAdImmediately(false))
    }

    @Test
    fun `same incident reuses the page while a different active incident is rejected`() {
        assertTrue(RestrictionPagePresentationPolicy.mayClaimPage("incident-a", 2_000L, "incident-a", 1_000L))
        assertFalse(RestrictionPagePresentationPolicy.mayClaimPage("incident-a", 2_000L, "incident-b", 1_000L))
        assertTrue(RestrictionPagePresentationPolicy.mayClaimPage("incident-a", 1_000L, "incident-b", 1_000L))
    }
}
