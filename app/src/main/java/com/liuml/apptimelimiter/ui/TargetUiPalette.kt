package com.liuml.apptimelimiter.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode

data class TargetUiColors(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceContainer: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val outline: Int,
    val scrim: Int,
)

object TargetUiPalette {
    fun resolve(
        context: Context,
        mode: AppThemeMode = AppThemeMode.SYSTEM,
        color: AppThemeColor = AppThemeColor.GREEN,
    ): TargetUiColors = resolve(
        mode = mode,
        color = color,
        systemDark = systemDarkMode(context),
    )

    internal fun resolve(
        mode: AppThemeMode,
        color: AppThemeColor,
        systemDark: Boolean,
    ): TargetUiColors {
        val dark = when (mode) {
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
            AppThemeMode.SYSTEM -> systemDark
        }
        return when (color) {
            AppThemeColor.GREEN -> if (dark) GREEN_DARK else GREEN_LIGHT
            AppThemeColor.BLUE -> if (dark) BLUE_DARK else BLUE_LIGHT
            AppThemeColor.PURPLE -> if (dark) PURPLE_DARK else PURPLE_LIGHT
        }
    }

    private fun systemDarkMode(context: Context): Boolean {
        val systemNightMode = Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        if (systemNightMode != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return systemNightMode == Configuration.UI_MODE_NIGHT_YES
        }
        return (
            context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
    }

    private val GREEN_LIGHT = TargetUiColors(
        isDark = false,
        background = 0xFFF5FAF6.toInt(),
        surface = 0xFFFCFDF9.toInt(),
        surfaceContainer = 0xFFE7EDE8.toInt(),
        primary = 0xFF276B4E.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFC9F0DA.toInt(),
        onPrimaryContainer = 0xFF073823.toInt(),
        textPrimary = 0xFF171D19.toInt(),
        textSecondary = 0xFF414943.toInt(),
        outline = 0xFFC1C9C2.toInt(),
        scrim = 0x660F1511,
    )

    private val GREEN_DARK = TargetUiColors(
        isDark = true,
        background = 0xFF0F1511.toInt(),
        surface = 0xFF141A16.toInt(),
        surfaceContainer = 0xFF252B27.toInt(),
        primary = 0xFF91D7AE.toInt(),
        onPrimary = 0xFF003824.toInt(),
        primaryContainer = 0xFF0D5138.toInt(),
        onPrimaryContainer = 0xFFACF3C9.toInt(),
        textPrimary = 0xFFDEE4DE.toInt(),
        textSecondary = 0xFFC1C9C2.toInt(),
        outline = 0xFF414943.toInt(),
        scrim = 0x99070B08.toInt(),
    )

    private val BLUE_LIGHT = TargetUiColors(
        isDark = false,
        background = 0xFFF7F9FC.toInt(),
        surface = 0xFFFDFBFF.toInt(),
        surfaceContainer = 0xFFE9EDF3.toInt(),
        primary = 0xFF315F91.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFD2E4FF.toInt(),
        onPrimaryContainer = 0xFF001C38.toInt(),
        textPrimary = 0xFF191C20.toInt(),
        textSecondary = 0xFF43474E.toInt(),
        outline = 0xFFC3C7CF.toInt(),
        scrim = 0x66111418,
    )

    private val BLUE_DARK = TargetUiColors(
        isDark = true,
        background = 0xFF111418.toInt(),
        surface = 0xFF16191D.toInt(),
        surfaceContainer = 0xFF272A2F.toInt(),
        primary = 0xFFA1C9FF.toInt(),
        onPrimary = 0xFF00325B.toInt(),
        primaryContainer = 0xFF174875.toInt(),
        onPrimaryContainer = 0xFFD2E4FF.toInt(),
        textPrimary = 0xFFE2E2E8.toInt(),
        textSecondary = 0xFFC3C7CF.toInt(),
        outline = 0xFF43474E.toInt(),
        scrim = 0x99070A0D.toInt(),
    )

    private val PURPLE_LIGHT = TargetUiColors(
        isDark = false,
        background = 0xFFFAF8FC.toInt(),
        surface = 0xFFFFFBFE.toInt(),
        surfaceContainer = 0xFFEEE9F1.toInt(),
        primary = 0xFF6B4EA0.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFEADDFF.toInt(),
        onPrimaryContainer = 0xFF25005A.toInt(),
        textPrimary = 0xFF1D1B20.toInt(),
        textSecondary = 0xFF49454E.toInt(),
        outline = 0xFFCBC4CF.toInt(),
        scrim = 0x66151218,
    )

    private val PURPLE_DARK = TargetUiColors(
        isDark = true,
        background = 0xFF151218.toInt(),
        surface = 0xFF1A171C.toInt(),
        surfaceContainer = 0xFF2B282D.toInt(),
        primary = 0xFFD4BBFF.toInt(),
        onPrimary = 0xFF3B176D.toInt(),
        primaryContainer = 0xFF523586.toInt(),
        onPrimaryContainer = 0xFFEADDFF.toInt(),
        textPrimary = 0xFFE7E1E9.toInt(),
        textSecondary = 0xFFCBC4CF.toInt(),
        outline = 0xFF49454E.toInt(),
        scrim = 0x9909070B.toInt(),
    )
}
