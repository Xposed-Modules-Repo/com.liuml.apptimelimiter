package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningBannerLayoutPolicyTest {
    @Test
    fun `compact banner stacks multiple actions and narrow layouts`() {
        assertTrue(WarningBannerLayoutPolicy.shouldStackActions(false, 700, 2, 1f))
        assertTrue(WarningBannerLayoutPolicy.shouldStackActions(false, 360, 1, 1f))
        assertTrue(WarningBannerLayoutPolicy.shouldStackActions(false, 700, 1, 1.3f))
        assertFalse(WarningBannerLayoutPolicy.shouldStackActions(false, 700, 1, 1f))
        assertFalse(WarningBannerLayoutPolicy.shouldStackActions(true, 360, 3, 2f))
    }
}
