package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionAnnouncementPolicyTest {
    @Test
    fun `each announcement appears once per version`() {
        assertTrue(VersionAnnouncementPolicy.shouldShow(32, 31))
        assertFalse(VersionAnnouncementPolicy.shouldShow(32, 32))
        assertTrue(VersionAnnouncementPolicy.shouldShow(33, 32))
    }

    @Test
    fun `invalid current version is ignored`() {
        assertFalse(VersionAnnouncementPolicy.shouldShow(0, -1))
    }
}
