package com.offline.audiobookplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class AppThemeMode {
    DARK,
    OLED,
    LIGHT,
    SYSTEM
}

private val DarkColorScheme = darkColorScheme(
    primary = SkyAccent,
    onPrimary = OledBlack,
    secondary = MintSecondary,
    onSecondary = OledBlack,
    background = DarkSurface,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = DarkTextSecondary
)

private val OledColorScheme = darkColorScheme(
    primary = SkyAccent,
    onPrimary = OledBlack,
    secondary = MintSecondary,
    onSecondary = OledBlack,
    background = OledBlack,
    onBackground = DarkTextPrimary,
    surface = OledBlack,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkTextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = SkyAccentDark,
    onPrimary = LightSurface,
    secondary = MintSecondary,
    onSecondary = LightTextPrimary,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary
)

@Composable
fun TruyenCastTheme(
    themeMode: AppThemeMode = AppThemeMode.OLED,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.OLED -> OledColorScheme
        AppThemeMode.DARK -> DarkColorScheme
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TruyenCastTypography,
        content = content
    )
}
