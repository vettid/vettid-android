package com.vettid.app.debug

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

    override fun startRoute(intent: Intent): Any? {
        val start = intent.getStringExtra(EXTRA_START)
        return when {
            start == null -> null
            start == "gallery" -> GalleryRoute
            else -> TopLevelDestination.entries.firstOrNull { it.name.equals(start, ignoreCase = true) }?.route
        }
    }
}
