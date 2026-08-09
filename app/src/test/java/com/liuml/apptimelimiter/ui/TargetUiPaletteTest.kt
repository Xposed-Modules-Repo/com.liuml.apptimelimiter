package com.liuml.apptimelimiter.ui

import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetUiPaletteTest {
    @Test
    fun `explicit dark mode always returns dark target surfaces`() {
        AppThemeColor.entries.forEach { color ->
            val palette = TargetUiPalette.resolve(
                mode = AppThemeMode.DARK,
                color = color,
                systemDark = false,
            )

            assertTrue(palette.isDark)
            assertTrue(luminance(palette.background) < luminance(0xFFFFFFFF.toInt()))
            assertNotEquals(0xFFFFFFFF.toInt(), palette.surface)
        }
    }

    @Test
    fun `system mode follows system rather than target app appearance`() {
        val dark = TargetUiPalette.resolve(
            mode = AppThemeMode.SYSTEM,
            color = AppThemeColor.BLUE,
            systemDark = true,
        )
        val light = TargetUiPalette.resolve(
            mode = AppThemeMode.SYSTEM,
            color = AppThemeColor.BLUE,
            systemDark = false,
        )

        assertTrue(dark.isDark)
        assertFalse(light.isDark)
        assertNotEquals(dark.background, light.background)
    }

    @Test
    fun `all color families remain distinct on target pages`() {
        val palettes = AppThemeColor.entries.map {
            TargetUiPalette.resolve(AppThemeMode.DARK, it, systemDark = true)
        }

        assertNotEquals(palettes[0].primary, palettes[1].primary)
        assertNotEquals(palettes[1].primary, palettes[2].primary)
        assertNotEquals(palettes[0].primaryContainer, palettes[2].primaryContainer)
    }

    private fun luminance(color: Int): Int =
        ((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)
}
