package com.vettid.app.debug

import android.app.Activity
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.app.ui.TopLevelDestination
import com.vettid.core.ui.components.DrawerItem
import com.vettid.core.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

@Serializable
data object GalleryRoute

private const val GALLERY_KEY = "debug.gallery"

/** Launch extras for screenshots: `--es vettid.theme light|dark --es vettid.start gallery|messages|…`. */
private const val EXTRA_THEME = "vettid.theme"
private const val EXTRA_START = "vettid.start"
private const val EXTRA_SCREENSHOT = "vettid.screenshot"
private const val CATALOG_PREFIX = "screen:"

internal val debugTools: DebugTools = object : DebugTools {
    @Composable
    override fun drawerItems(): List<DrawerItem> =
        listOf(DrawerItem(key = GALLERY_KEY, label = "Component gallery", icon = Icons.Outlined.Palette))

    override fun routeFor(key: String): Any? = if (key == GALLERY_KEY) GalleryRoute else null

    override fun register(builder: NavGraphBuilder, host: DebugHost) {
        builder.composable<GalleryRoute> {
            GalleryScreen(themeMode = host.themeMode, onThemeModeChange = host.onThemeModeChange, onBack = host.onBack)
        }
    }

    override fun themeOverride(intent: Intent): ThemeMode? = when (intent.getStringExtra(EXTRA_THEME)) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> null
    }

    /**
     * `--ez vettid.screenshot true` lets the debug app draw over the keyguard so a
     * locked test phone can be screenshotted over adb, and keeps the screen on while
     * it is in front. The device stays locked.
     */
    override fun onLaunch(activity: Activity, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SCREENSHOT, false)) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            // Long device tests (A4: waits on another vault) must not lose the screen to the timeout.
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun catalogScreen(intent: Intent): (@Composable () -> Unit)? =
        intent.getStringExtra(EXTRA_START)
            ?.takeIf { it.startsWith(CATALOG_PREFIX) }
            ?.let { ScreenCatalog.screens[it.removePrefix(CATALOG_PREFIX)] }

    override fun startRoute(intent: Intent): Any? {
        val start = intent.getStringExtra(EXTRA_START)
        return when {
            start == null -> null
            start.startsWith(CATALOG_PREFIX) -> null
            start == "gallery" -> GalleryRoute
            // No longer in the drawer (Settings → Security → Credential), still a launch extra.
            start == "credential" -> com.vettid.feature.credential.CredentialRoute
            else -> TopLevelDestination.entries.firstOrNull { it.name.equals(start, ignoreCase = true) }?.route
        }
    }
}
