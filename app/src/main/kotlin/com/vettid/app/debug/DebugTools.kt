package com.vettid.app.debug

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.vettid.core.ui.components.DrawerItem
import com.vettid.core.ui.theme.ThemeMode

/** Hooks for debug-only screens. The release source set provides a no-op. */
interface DebugTools {
    /** Extra drawer section (empty in release). */
    @Composable
    fun drawerItems(): List<DrawerItem>

    /** Route object for a debug drawer item, or null. */
    fun routeFor(key: String): Any?

    fun register(builder: NavGraphBuilder, host: DebugHost)

    /** Theme forced by the launch intent (screenshots), or null. */
    fun themeOverride(intent: Intent): ThemeMode?

    /** Called from onCreate; debug builds use it for screenshot launches. */
    fun onLaunch(activity: Activity, intent: Intent)

    /** Route to open first, from the launch intent (screenshots), or null. */
    fun startRoute(intent: Intent): Any?
}

data class DebugHost(
    val themeMode: ThemeMode,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onBack: () -> Unit,
)
