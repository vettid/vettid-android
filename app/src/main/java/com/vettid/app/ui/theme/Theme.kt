package com.vettid.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// VettID Brand Colors
val VettidGold = Color(0xFFF4B942)
val VettidGoldDark = Color(0xFFD9A137)
val VettidGoldLight = Color(0xFFFFD980)
val VettidBlack = Color(0xFF000000)
val VettidDarkGray = Color(0xFF121212)
val VettidMediumGray = Color(0xFF2C2C2C)
val VettidLightGray = Color(0xFF9E9E9E)
val VettidOffWhite = Color(0xFFF5F5F5)
val VettidWhite = Color(0xFFFFFFFF)

// Dark theme - Black background with gold accents
private val DarkColorScheme = darkColorScheme(
    primary = VettidGold,
    onPrimary = VettidBlack,
    primaryContainer = VettidGoldDark,
    onPrimaryContainer = VettidWhite,
    secondary = VettidGold,
    onSecondary = VettidBlack,
    secondaryContainer = VettidMediumGray,
    onSecondaryContainer = VettidGold,
    tertiary = VettidGoldLight,
    onTertiary = VettidBlack,
    tertiaryContainer = VettidDarkGray,
    onTertiaryContainer = VettidGoldLight,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = VettidBlack,
    onBackground = VettidWhite,
    surface = VettidDarkGray,
    onSurface = VettidWhite,
    surfaceVariant = VettidMediumGray,
    onSurfaceVariant = VettidLightGray,
    outline = VettidLightGray,
    outlineVariant = VettidMediumGray,
    inverseSurface = VettidWhite,
    inverseOnSurface = VettidBlack,
    inversePrimary = VettidGoldDark,
)

// Light theme - White/off-white background with black and gold accents
private val LightColorScheme = lightColorScheme(
    primary = VettidBlack,
    onPrimary = VettidWhite,
    primaryContainer = VettidGold,
    onPrimaryContainer = VettidBlack,
    secondary = VettidGold,
    onSecondary = VettidBlack,
    secondaryContainer = VettidGoldLight,
    onSecondaryContainer = VettidBlack,
    tertiary = VettidDarkGray,
    onTertiary = VettidWhite,
    tertiaryContainer = VettidLightGray,
    onTertiaryContainer = VettidBlack,
    error = Color(0xFFBA1A1A),
    onError = VettidWhite,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = VettidWhite,
    onBackground = VettidBlack,
    surface = VettidOffWhite,
    onSurface = VettidBlack,
    surfaceVariant = VettidOffWhite,
    onSurfaceVariant = VettidDarkGray,
    outline = VettidLightGray,
    outlineVariant = VettidOffWhite,
    inverseSurface = VettidBlack,
    inverseOnSurface = VettidWhite,
    inversePrimary = VettidGold,
)

@Composable
fun VettIDTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Always use VettID brand colors, no dynamic colors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar matches the theme
            window.statusBarColor = if (darkTheme) VettidBlack.toArgb() else VettidBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
