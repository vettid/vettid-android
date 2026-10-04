package com.vettid.app.debug

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import com.vettid.core.ui.components.DrawerItem
import com.vettid.core.ui.theme.ThemeMode

/** Release builds have no debug screens and ignore launch extras. */
internal val debugTools: DebugTools = object : DebugTools {
    @Composable
    override fun drawerItems(): List<DrawerItem> = emptyList()
    override fun routeFor(key: String): Any? = null
    override fun register(builder: NavGraphBuilder, host: DebugHost) = Unit
    override fun themeOverride(intent: Intent): ThemeMode? = null
    override fun onLaunch(activity: Activity, intent: Intent) = Unit
    override fun startRoute(intent: Intent): Any? = null
    override fun catalogScreen(intent: Intent): (@Composable () -> Unit)? = null
}
