package com.vettid.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** The member's theme choice. */
enum class ThemeMode { System, Light, Dark }

private val LocalVettIdColors = staticCompositionLocalOf { DarkVettIdColors }

/**
 * VettID theme: navy surfaces with a single gold accent (dark), white with the
 * same accent (light). Dynamic colour is deliberately not used.
 */
@Composable
fun VettIdTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalVettIdColors provides if (dark) DarkVettIdColors else LightVettIdColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = VettIdTypography,
            shapes = VettIdShapes,
            content = content,
        )
    }
}

/** Access to VettID tokens that are not part of [MaterialTheme]. */
object VettIdTheme {
    val colors: VettIdColors
        @Composable @ReadOnlyComposable
        get() = LocalVettIdColors.current
}

/** Exposed for contrast tests. */
object VettIdSchemes {
    val dark = DarkColors
    val light = LightColors
    val darkExtras = DarkVettIdColors
    val lightExtras = LightVettIdColors
}
