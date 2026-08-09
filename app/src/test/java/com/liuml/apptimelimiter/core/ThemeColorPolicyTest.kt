package com.liuml.apptimelimiter.core

import com.liuml.apptimelimiter.data.AppThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorPolicyTest {
    @Test
    fun `known colors parse and missing fields fall back to green`() {
        assertEquals(AppThemeColor.BLUE, ThemeColorPolicy.parse("BLUE"))
        assertEquals(AppThemeColor.PURPLE, ThemeColorPolicy.parse("PURPLE"))
        assertEquals(AppThemeColor.GREEN, ThemeColorPolicy.parse(null))
        assertEquals(AppThemeColor.GREEN, ThemeColorPolicy.parse("UNKNOWN"))
    }
}
