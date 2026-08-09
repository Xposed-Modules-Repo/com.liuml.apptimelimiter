package com.liuml.apptimelimiter.nonroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemCompatibilityNavigatorTest {
    @Test
    fun `xiaomi family is recognized`() {
        assertEquals("XIAOMI", OemCompatibilityNavigator.vendorFamily("Xiaomi"))
        assertEquals("XIAOMI", OemCompatibilityNavigator.vendorFamily("POCO"))
    }

    @Test
    fun `unknown manufacturer has no unverified explicit candidate`() {
        assertEquals("OTHER", OemCompatibilityNavigator.vendorFamily("Unknown"))
    }
}
