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
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.feature.credential.CredentialAlarmRoute
import com.vettid.feature.credential.CredentialRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import com.vettid.feature.approvals.approvalsDestination
import com.vettid.feature.connections.connectionsDestination
import com.vettid.feature.credential.credentialDestination
import com.vettid.feature.items.itemsDestination
import com.vettid.feature.connections.AcceptRoute
import com.vettid.feature.connections.ConnectionDetailRoute
import com.vettid.feature.connections.ConnectionsHost
import com.vettid.feature.connections.InviteRoute
import com.vettid.feature.messages.ConversationRoute
import com.vettid.feature.messages.MessagesHost
import com.vettid.feature.messages.MessagesRoute
import com.vettid.feature.messages.messagesDestination
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    launchRoute: Any?,
    accountName: String,
    account: AccountInfo?,
    portalUrl: String,
    alarm: CredentialAlarm?,
    onLockVault: () -> Unit,
    onEnableAppLock: () -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val theme = LocalThemeController.current
    val uri = LocalUriHandler.current
    val portal = portalUrl
    val chrome = remember(accountName) {
        ShellChrome(
            accountName = accountName,
            onMenuClick = { scope.launch { drawerState.open() } },
            onAvatarClick = { showAccount = true },
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val shell: ShellViewModel = hiltViewModel()
    val badges by shell.badges.collectAsStateWithLifecycle()
    val debugItems = debugTools.drawerItems()
    val sections = drawerSections(badges) + listOfNotNull(debugItems.takeIf { it.isNotEmpty() })
    val navigate: (Any) -> Unit = { navController.navigate(it) }
    val back: () -> Unit = { navController.popBackStack() }

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
        Column(Modifier.fillMaxSize()) {
            // The clone alarm (§3.5.9): an urgent banner above every screen until it is resolved.
            val bannerShown = alarm != null && current?.hierarchy?.any { it.hasRoute(CredentialAlarmRoute::class) } != true
            if (bannerShown) {
                UrgentBanner(
                    text = stringResource(com.vettid.feature.credential.R.string.credential_alarm_banner),
                    actionLabel = stringResource(com.vettid.feature.credential.R.string.credential_alarm_review),
                    onClick = { navController.navigate(CredentialAlarmRoute) { launchSingleTop = true } },
                    modifier = Modifier.testTag("alarm_banner"),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(if (bannerShown) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                NavHost(navController = navController, startDestination = MessagesRoute) {
                    messagesDestination(
                        chrome,
                        MessagesHost(
                            navigate = navigate,
                            onBack = back,
                            onOpenConnection = { navController.navigate(ConnectionDetailRoute(it)) },
                            onInvite = { navController.navigate(InviteRoute) },
                        ),
                    )
                    connectionsDestination(
                        chrome,
                        ConnectionsHost(
                            navigate = navigate,
                            onBack = back,
                            replace = { route ->
                                navController.popBackStack()
                                navController.navigate(route)
                            },
                            onOpenConversation = { id ->
                                navController.navigate(ConversationRoute(id)) { launchSingleTop = true }
                            },
                        ),
                    )
                    approvalsDestination(chrome, navigate = navigate, onBack = back)
                    itemsDestination(chrome)
                    credentialDestination(chrome, navigate = { navController.navigate(it) }, onBack = { navController.popBackStack() })
                    settingsDestination(
                        SettingsHost(
                            onBack = { navController.popBackStack() },
                            navigate = { navController.navigate(it) },
                            onOpenCredential = { navController.navigateTopLevel(CredentialRoute) },
                            onEnableAppLock = onEnableAppLock,
                            onAccountClick = { showAccount = true },
                            onOpenAccountSite = { uri.openUri(portal) },
                        ),
                    )
                    helpDestination(onBack = { navController.popBackStack() })
                    debugTools.register(
                        this,
                        DebugHost(theme.mode, theme.set, onBack = { navController.popBackStack() }),
                    )
                }
            }
        }
    }

    LaunchedEffect(launchRoute) {
        if (launchRoute != null) navController.navigateTopLevel(launchRoute)
    }

    // An invitation link the app was opened with (§6.4): the accept screen, where the member confirms.
    val inviteLink by shell.inviteLink.collectAsStateWithLifecycle()
    LaunchedEffect(inviteLink) {
        val link = inviteLink ?: return@LaunchedEffect
        shell.inviteLinkTaken()
        navController.navigate(AcceptRoute(link, opened = true)) { launchSingleTop = true }
    }

    if (showAccount) {
        AccountSheet(
            account = account,
            portalUrl = portal,
            onDismiss = { showAccount = false },
            onLockVault = {
                showAccount = false
                onLockVault()
            },
        )
    }
}

@Composable
private fun drawerSections(badges: ShellBadges): List<List<DrawerItem>> =
    TopLevelDestination.entries
        .groupBy { it.group }
        .toSortedMap()
        .values
        .map { group ->
            group.map {
                val badge = when (it) {
                    TopLevelDestination.Approvals -> badges.approvals
                    TopLevelDestination.Messages -> badges.unread
                    else -> 0
                }
                DrawerItem(key = it.key, label = stringResource(it.label), icon = it.icon, badge = badge)
            }
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
