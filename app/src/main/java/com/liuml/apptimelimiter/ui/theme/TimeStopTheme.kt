package com.liuml.apptimelimiter.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.liuml.apptimelimiter.data.AppThemeColor
import com.liuml.apptimelimiter.data.AppThemeMode

@Immutable
data class TimeStopExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val managedContainer: Color,
    val subtleContainer: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
)

@Immutable
data class TimeStopThemeState(
    val mode: AppThemeMode,
    val color: AppThemeColor,
    val dark: Boolean,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF276B4E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F0DA),
    onPrimaryContainer = Color(0xFF073823),
    secondary = Color(0xFF4F6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D9),
    onSecondaryContainer = Color(0xFF102D20),
    tertiary = Color(0xFF785B2A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFA5),
    onTertiaryContainer = Color(0xFF291800),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAF6),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFFCFDF9),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDDE5DE),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC1C9C2),
    surfaceContainer = Color(0xFFEDF3EE),
    surfaceContainerHigh = Color(0xFFE7EDE8),
    surfaceContainerHighest = Color(0xFFE1E8E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91D7AE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF0D5138),
    onPrimaryContainer = Color(0xFFACF3C9),
    secondary = Color(0xFFB7CCBE),
    onSecondary = Color(0xFF23352B),
    secondaryContainer = Color(0xFF394B40),
    onSecondaryContainer = Color(0xFFD2E8D9),
    tertiary = Color(0xFFE7C17B),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF594315),
    onTertiaryContainer = Color(0xFFFFDFA5),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF141A16),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF414943),
    onSurfaceVariant = Color(0xFFC1C9C2),
    outline = Color(0xFF8B938C),
    outlineVariant = Color(0xFF414943),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
)

private val BlueLightColors = lightColorScheme(
    primary = Color(0xFF315F91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    surfaceContainer = Color(0xFFF0F2F7),
    surfaceContainerHigh = Color(0xFFE9EDF3),
    surfaceContainerHighest = Color(0xFFE3E7ED),
)

private val BlueDarkColors = darkColorScheme(
    primary = Color(0xFFA1C9FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF174875),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F60),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE2E2E8),
    surface = Color(0xFF16191D),
    onSurface = Color(0xFFE2E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
)

private val PurpleLightColors = lightColorScheme(
    primary = Color(0xFF6B4EA0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF25005A),
    secondary = Color(0xFF635B70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DEF8),
    onSecondaryContainer = Color(0xFF1F182A),
    tertiary = Color(0xFF7E525D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E1),
    onTertiaryContainer = Color(0xFF31101B),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF8FC),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCBC4CF),
    surfaceContainer = Color(0xFFF4EFF7),
    surfaceContainerHigh = Color(0xFFEEE9F1),
    surfaceContainerHighest = Color(0xFFE8E3EB),
)

private val PurpleDarkColors = darkColorScheme(
    primary = Color(0xFFD4BBFF),
    onPrimary = Color(0xFF3B176D),
    primaryContainer = Color(0xFF523586),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF342D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFE9DEF8),
    tertiary = Color(0xFFF0B8C5),
    onTertiary = Color(0xFF4A252F),
    tertiaryContainer = Color(0xFF633B46),
    onTertiaryContainer = Color(0xFFFFD9E1),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF151218),
    onBackground = Color(0xFFE7E1E9),
    surface = Color(0xFF1A171C),
    onSurface = Color(0xFFE7E1E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCBC4CF),
    outline = Color(0xFF958F99),
    outlineVariant = Color(0xFF49454E),
    surfaceContainer = Color(0xFF211E23),
    surfaceContainerHigh = Color(0xFF2B282D),
    surfaceContainerHighest = Color(0xFF363238),
)

private val LightExtendedColors = TimeStopExtendedColors(
    success = Color(0xFF1F704B),
    onSuccess = Color.White,
    successContainer = Color(0xFFD7F4E2),
    onSuccessContainer = Color(0xFF123D2B),
    warning = Color(0xFF8A5700),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFE5B5),
    onWarningContainer = Color(0xFF4A2D00),
    info = Color(0xFF42655A),
    onInfo = Color.White,
    infoContainer = Color(0xFFD9ECE4),
    onInfoContainer = Color(0xFF1C3A30),
    managedContainer = Color(0xFFE2F4E9),
    subtleContainer = Color(0xFFEDF3EE),
    dangerContainer = Color(0xFFFFDAD6),
    onDangerContainer = Color(0xFF7E1611),
)

private val DarkExtendedColors = TimeStopExtendedColors(
    success = Color(0xFF83D6A7),
    onSuccess = Color(0xFF003920),
    successContainer = Color(0xFF164B32),
    onSuccessContainer = Color(0xFFB7F0CD),
    warning = Color(0xFFFFC56C),
    onWarning = Color(0xFF442B00),
    warningContainer = Color(0xFF533A0E),
    onWarningContainer = Color(0xFFFFDEAA),
    info = Color(0xFFA7D0C1),
    onInfo = Color(0xFF0D382C),
    infoContainer = Color(0xFF294C41),
    onInfoContainer = Color(0xFFC2EBDD),
    managedContainer = Color(0xFF173D2D),
    subtleContainer = Color(0xFF252B27),
    dangerContainer = Color(0xFF5B201D),
    onDangerContainer = Color(0xFFFFDAD6),
)

val LocalTimeStopExtendedColors = staticCompositionLocalOf { LightExtendedColors }
val LocalTimeStopThemeState = staticCompositionLocalOf {
    TimeStopThemeState(
        mode = AppThemeMode.SYSTEM,
        color = AppThemeColor.GREEN,
        dark = false,
    )
}

@Composable
fun TimeStopTheme(
    mode: AppThemeMode,
    color: AppThemeColor = AppThemeColor.GREEN,
    content: @Composable () -> Unit,
) {
    val dark = shouldUseDarkTheme(mode, isSystemInDarkTheme())
    val colorScheme = resolveColorScheme(color, dark)
    val extended = extendedColors(colorScheme, dark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTimeStopExtendedColors provides extended,
        LocalTimeStopThemeState provides TimeStopThemeState(mode, color, dark),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

internal fun shouldUseDarkTheme(mode: AppThemeMode, systemDark: Boolean): Boolean = when (mode) {
    AppThemeMode.SYSTEM -> systemDark
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
}

internal fun resolveColorScheme(color: AppThemeColor, dark: Boolean): ColorScheme = when (color) {
    AppThemeColor.GREEN -> if (dark) DarkColors else LightColors
    AppThemeColor.BLUE -> if (dark) BlueDarkColors else BlueLightColors
    AppThemeColor.PURPLE -> if (dark) PurpleDarkColors else PurpleLightColors
}

/**
 * Semantic status surfaces still follow the selected color family. Warning and danger retain
 * their semantic amber/red colors so permission and destructive states remain recognizable.
 */
internal fun extendedColors(
    colorScheme: ColorScheme,
    dark: Boolean,
): TimeStopExtendedColors = (if (dark) DarkExtendedColors else LightExtendedColors).copy(
    success = colorScheme.primary,
    onSuccess = colorScheme.onPrimary,
    successContainer = colorScheme.primaryContainer,
    onSuccessContainer = colorScheme.onPrimaryContainer,
    info = colorScheme.secondary,
    onInfo = colorScheme.onSecondary,
    infoContainer = colorScheme.secondaryContainer,
    onInfoContainer = colorScheme.onSecondaryContainer,
    managedContainer = colorScheme.primaryContainer.copy(alpha = if (dark) 0.72f else 0.62f),
    subtleContainer = colorScheme.surfaceContainerHigh,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
