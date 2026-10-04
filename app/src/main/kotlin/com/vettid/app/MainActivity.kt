package com.vettid.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vettid.app.debug.debugTools
import com.vettid.app.ui.AppShell
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        debugTools.onLaunch(this, intent)
        val launchTheme = debugTools.themeOverride(intent) ?: ThemeMode.System
        val launchRoute = debugTools.startRoute(intent)
        setContent {
            // A0: the theme choice lives in memory; DataStore persistence comes with settings (A3).
            var themeMode by rememberSaveable { mutableStateOf(launchTheme) }
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            VettIdTheme(themeMode = themeMode) {
                AppShell(
                    themeMode = themeMode,
                    onThemeModeChange = { themeMode = it },
                    launchRoute = launchRoute,
                )
            }
        }
    }
}
