package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamePolicyTest {
    @Test
    fun `accepts normal Android package names`() {
        assertTrue(PackageNamePolicy.isValid("com.example.app_2"))
        assertTrue(PackageNamePolicy.isValid("android"))
    }

    @Test
    fun `rejects storage key injection and malformed segments`() {
        assertFalse(PackageNamePolicy.isValid(""))
        assertFalse(PackageNamePolicy.isValid("com.example\nother"))
        assertFalse(PackageNamePolicy.isValid("com..example"))
        assertFalse(PackageNamePolicy.isValid("com.2example"))
        assertFalse(PackageNamePolicy.isValid("a".repeat(PackageNamePolicy.MAX_LENGTH + 1)))
    }
}
