package com.vettid.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.vettid.core.ui.theme.ThemeMode

/** The theme in force and how to change it (persisted in DataStore by the activity). */
data class ThemeController(val mode: ThemeMode, val set: (ThemeMode) -> Unit)

val LocalThemeController = staticCompositionLocalOf { ThemeController(ThemeMode.System) {} }
