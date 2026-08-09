package com.liuml.apptimelimiter.ui.theme

import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeStopThemeTest {
    @Test
    fun `system mode follows system dark state`() {
        assertTrue(shouldUseDarkTheme(AppThemeMode.SYSTEM, systemDark = true))
        assertFalse(shouldUseDarkTheme(AppThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `explicit theme overrides system state`() {
        assertTrue(shouldUseDarkTheme(AppThemeMode.DARK, systemDark = false))
        assertFalse(shouldUseDarkTheme(AppThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `status surfaces inherit selected color family`() {
        val green = resolveColorScheme(AppThemeColor.GREEN, dark = false)
        val blue = resolveColorScheme(AppThemeColor.BLUE, dark = false)
        val purpleDark = resolveColorScheme(AppThemeColor.PURPLE, dark = true)

        assertEquals(blue.primaryContainer, extendedColors(blue, dark = false).successContainer)
        assertEquals(blue.secondaryContainer, extendedColors(blue, dark = false).infoContainer)
        assertEquals(
            purpleDark.primaryContainer,
            extendedColors(purpleDark, dark = true).successContainer,
        )
        assertNotEquals(
            extendedColors(green, dark = false).infoContainer,
            extendedColors(blue, dark = false).infoContainer,
        )
    }
}
