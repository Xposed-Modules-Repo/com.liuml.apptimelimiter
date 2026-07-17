package com.liuml.apptimelimiter.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `newer semantic version is detected`() {
        assertTrue(isVersionNewer("v0.5.0", "0.4.1"))
        assertTrue(isVersionNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `same or older version is not an update`() {
        assertFalse(isVersionNewer("v0.4.1", "0.4.1"))
        assertFalse(isVersionNewer("0.4.0", "0.4.1"))
    }
}
