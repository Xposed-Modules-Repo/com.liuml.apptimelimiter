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
        assertFalse(isVersionNewer("12-0.8.0", "0.9.1"))
        assertFalse(isVersionNewer("16-0.9.1", "0.9.1"))
        assertTrue(isVersionNewer("17-0.9.2", "0.9.1"))
    }

    @Test
    fun `draft and prerelease versions are excluded`() {
        assertTrue(isStableRelease(draft = false, prerelease = false))
        assertFalse(isStableRelease(draft = true, prerelease = false))
        assertFalse(isStableRelease(draft = false, prerelease = true))
    }

    @Test
    fun `only signed non-debug apk assets are accepted`() {
        assertTrue(isPublishableApkAsset("time-stop-v0.9.1.apk"))
        assertFalse(isPublishableApkAsset("time-stop-debug.apk"))
        assertFalse(isPublishableApkAsset("time-stop-release-unsigned.apk"))
        assertFalse(isPublishableApkAsset("checksums.txt"))
    }
}
