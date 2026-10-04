package com.vettid.app.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vettid.app.BuildConfig
import com.vettid.app.R
import com.vettid.app.debug.DebugHost
import com.vettid.app.debug.debugTools
import com.vettid.core.ui.components.DrawerItem
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.VettIdDrawerSheet
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.feature.approvals.approvalsDestination
import com.vettid.feature.connections.connectionsDestination
import com.vettid.feature.credential.credentialDestination
import com.vettid.feature.items.itemsDestination
import com.vettid.feature.messages.MessagesRoute
import com.vettid.feature.messages.messagesDestination
import com.vettid.feature.settings.SettingsHost
import com.vettid.feature.settings.helpDestination
import com.vettid.feature.settings.settingsDestination
import kotlinx.coroutines.launch

/**
 * App shell: navigation drawer (the only top-level navigation, no bottom tabs),
 * the type-safe NavHost, and the account sheet.
 */
@Composable
fun AppShell(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    launchRoute: Any?,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAccount by rememberSaveable { mutableStateOf(false) }

    val accountName = stringResource(R.string.account_placeholder_name)
    val accountDetail = stringResource(R.string.account_placeholder_detail)
    val chrome = remember(accountName) {
        ShellChrome(
            accountName = accountName,
            onMenuClick = { scope.launch { drawerState.open() } },
            onAvatarClick = { showAccount = true },
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val debugItems = debugTools.drawerItems()
    val sections = drawerSections() + listOfNotNull(debugItems.takeIf { it.isNotEmpty() })

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            VettIdDrawerSheet(
                sections = sections,
                selectedKey = selectedKey(current, debugItems),
                onItemClick = { item ->
                    scope.launch { drawerState.close() }
                    val route = TopLevelDestination.fromKey(item.key)?.route ?: debugTools.routeFor(item.key)
                    if (route != null) navController.navigateTopLevel(route)
                },
                versionLabel = stringResource(R.string.drawer_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            )
        },
    ) {
        NavHost(navController = navController, startDestination = MessagesRoute) {
            messagesDestination(chrome)
            connectionsDestination(chrome)
            approvalsDestination(chrome)
            itemsDestination(chrome)
            credentialDestination(chrome)
            settingsDestination(
                SettingsHost(
                    accountName = accountName,
                    accountDetail = accountDetail,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onBack = { navController.popBackStack() },
                    onAccountClick = { showAccount = true },
                ),
            )
            helpDestination(onBack = { navController.popBackStack() })
            debugTools.register(
                this,
                DebugHost(themeMode, onThemeModeChange, onBack = { navController.popBackStack() }),
            )
        }
    }

    LaunchedEffect(launchRoute) {
        if (launchRoute != null) navController.navigateTopLevel(launchRoute)
    }

    if (showAccount) {
        AccountSheet(name = accountName, detail = accountDetail, onDismiss = { showAccount = false })
    }
}

@Composable
private fun drawerSections(): List<List<DrawerItem>> =
    TopLevelDestination.entries
        .groupBy { it.group }
        .toSortedMap()
        .values
        .map { group ->
            group.map { DrawerItem(key = it.key, label = stringResource(it.label), icon = it.icon) }
        }

private fun selectedKey(current: NavDestination?, debugItems: List<DrawerItem>): String? {
    if (current == null) return null
    fun isCurrent(routeClass: kotlin.reflect.KClass<*>) = current.hierarchy.any { it.hasRoute(routeClass) }
    val topLevel = TopLevelDestination.entries.firstOrNull { isCurrent(it.routeClass) }?.key
    return topLevel ?: debugItems.firstOrNull { item ->
        debugTools.routeFor(item.key)?.let { isCurrent(it::class) } ?: false
    }?.key
}

/** Drawer navigation: one copy of each top-level screen, state saved per destination. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
