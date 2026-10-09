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
import com.vettid.core.ui.components.rememberProfilePhoto
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
import com.vettid.feature.history.ConnectionHistoryRoute
import com.vettid.feature.history.HistoryHost
import com.vettid.feature.history.historyDestination
import com.vettid.feature.items.ItemsHost
import com.vettid.feature.items.ItemDetailRoute
import com.vettid.feature.items.ConnectionSharingRoute
import com.vettid.feature.items.RuleEditRoute
import com.vettid.feature.items.SharedWithYouRoute
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
import com.vettid.feature.settings.ChangeNameRoute
import com.vettid.feature.settings.SettingsHost
import com.vettid.feature.settings.SharedProfileRoute
import com.vettid.feature.settings.helpDestination
import com.vettid.feature.settings.settingsDestination
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vettid.feature.onboarding.OwnerCheckMode
import com.vettid.feature.onboarding.OwnerCheckRoute
import com.vettid.feature.onboarding.ReleaseUpdateRoute
import com.vettid.feature.onboarding.releaseUpdateDestination
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * App shell: navigation drawer (the only top-level navigation, no bottom tabs),
 * the type-safe NavHost, and the account sheet.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // the shell: drawer, banners, NavHost, owner-check gate
@Composable
fun AppShell(
    launchRoute: Any?,
    accountName: String,
    account: AccountInfo?,
    portalUrl: String,
    alarm: CredentialAlarm?,
    onLockVault: () -> Unit,
    onEnableAppLock: (com.vettid.core.data.prefs.AppLockMethod) -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val theme = LocalThemeController.current
    val uri = LocalUriHandler.current
    val portal = portalUrl
    val shell: ShellViewModel = hiltViewModel()
    val ownPhoto by shell.photo.collectAsStateWithLifecycle()
    val photo = rememberProfilePhoto(ownPhoto)
    val chrome = remember(accountName, photo) {
        ShellChrome(
            accountName = accountName,
            onMenuClick = { scope.launch { drawerState.open() } },
            onAvatarClick = { showAccount = true },
            accountPhoto = photo,
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val badges by shell.badges.collectAsStateWithLifecycle()
    val debugItems = debugTools.drawerItems()
    val sections = drawerSections(badges) + listOfNotNull(debugItems.takeIf { it.isNotEmpty() })
    val navigate: (Any) -> Unit = { navController.navigate(it) }
    val back: () -> Unit = { navController.popBackStack() }

    // The daily owner check (VAULT-MESSAGING §3.6.5): past the deadline the check comes before any other use of the
    // app, at the first open or return to the foreground, or when the member leaves the screen they are on; never
    // over an action in progress (a detail screen, a draft), whose refused requests keep their input.
    val deletionVm: PendingDeletionViewModel = hiltViewModel()
    val deletion by deletionVm.deletion.collectAsStateWithLifecycle()
    val deletionPath = stringResource(R.string.deletion_path)
    val gateVm: OwnerCheckGateViewModel = hiltViewModel()
    val gate by gateVm.gate.collectAsStateWithLifecycle()
    val gated by rememberUpdatedState(gate.gated)
    var armed by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf<OwnerCheckMode?>(null) }
    LaunchedEffect(gate.gated) { if (!gate.gated) armed = false }
    LaunchedEffect(backStackEntry?.id) { if (gated) armed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        gateVm.onForeground()
        if (gated) armed = true
    }
    // The proactive release notice (owner decision 2026-10-09): the banner, the notification's tap, and the one
    // request for the notification permission (API 33+), made when the first notice shows and never again.
    val releaseVm: ReleaseBannerViewModel = hiltViewModel()
    val release by releaseVm.banner.collectAsStateWithLifecycle()
    val openUpdate by releaseVm.openRequested.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        releaseVm.permissionAnswered(granted)
    }
    LaunchedEffect(release.visible, release.notificationsAsked) {
        if (release.visible && !release.notificationsAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) releaseVm.markAsked() else askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onList = current?.let { d -> TopLevelDestination.entries.any { t -> d.hierarchy.any { it.hasRoute(t.routeClass) } } } ?: true
    val checkMode = if (gate.gated && (armed || onList)) OwnerCheckMode.GATED else asked
    LaunchedEffect(checkMode) { if (checkMode == OwnerCheckMode.GATED) showAccount = false }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // While the check is shown, nothing of the vault under it is visible or reachable (§3.6.5).
            modifier = if (checkMode != null) Modifier.clearAndSetSemantics {} else Modifier,
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
                val alarmShown = alarm != null && current?.hierarchy?.any { it.hasRoute(CredentialAlarmRoute::class) } != true
                if (alarmShown) {
                    UrgentBanner(
                        text = stringResource(com.vettid.feature.credential.R.string.credential_alarm_banner),
                        actionLabel = stringResource(com.vettid.feature.credential.R.string.credential_alarm_review),
                        onClick = { navController.navigate(CredentialAlarmRoute) { launchSingleTop = true } },
                        modifier = Modifier.testTag("alarm_banner"),
                    )
                }
                // A start-over requested on the portal (VAULT-MESSAGING 0.16.0 §11.11.9): until it runs or is cancelled.
                val deletionShown = deletion != null
                deletion?.let { d ->
                    PendingDeletionBanner(
                        d,
                        first = !alarmShown,
                        onCancel = deletionVm::cancel,
                        onOpenSite = { uri.openUri(portal.trimEnd('/') + deletionPath) },
                    )
                }
                OwnerCheckBanners(
                    gate,
                    first = !alarmShown && !deletionShown,
                    onCheckNow = { asked = OwnerCheckMode.VOLUNTARY },
                    onHoldOn = gateVm::turnHoldOn,
                    onDismissNotices = gateVm::dismissNotices,
                )
                ReleaseUpdateBanner(
                    release,
                    first = !alarmShown && !deletionShown && !gate.bannerShown(),
                    onUpdate = { navController.navigate(ReleaseUpdateRoute) { launchSingleTop = true } },
                    onDismiss = releaseVm::dismiss,
                )
                val bannerShown = alarmShown || deletionShown || gate.bannerShown() || release.visible
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
                                onOpenHistory = { id -> navController.navigate(ConnectionHistoryRoute(id)) { launchSingleTop = true } },
                                onOpenSharing = { id -> navController.navigate(ConnectionSharingRoute(id)) { launchSingleTop = true } },
                                onOpenShared = { id -> navController.navigate(SharedWithYouRoute(id)) { launchSingleTop = true } },
                                onNewShareRule = { id -> navController.navigate(RuleEditRoute(id)) { launchSingleTop = true } },
                                onOpenShareRule = { id, rule ->
                                    navController.navigate(RuleEditRoute(id, rule)) { launchSingleTop = true }
                                },
                                onAskFor = { id -> navController.navigate(SharedWithYouRoute(id, ask = true)) { launchSingleTop = true } },
                            ),
                        )
                        approvalsDestination(chrome, navigate = navigate, onBack = back)
                        itemsDestination(
                            chrome,
                            ItemsHost(
                                navigate = navigate,
                                onBack = back,
                                replace = { route ->
                                    navController.popBackStack()
                                    navController.navigate(route)
                                },
                            ),
                        )
                        historyDestination(
                            chrome,
                            HistoryHost(
                                navigate = navigate,
                                onBack = back,
                                onOpenConnection = { navController.navigate(ConnectionDetailRoute(it)) { launchSingleTop = true } },
                                onOpenItem = { navController.navigate(ItemDetailRoute(it)) { launchSingleTop = true } },
                                onOpenAlarm = { navController.navigate(CredentialAlarmRoute) { launchSingleTop = true } },
                                onOwnerCheck = { asked = OwnerCheckMode.VOLUNTARY },
                            ),
                        )
                        credentialDestination(
                            chrome,
                            navigate = { navController.navigate(it) },
                            onBack = { navController.popBackStack() },
                            onOwnerCheck = { asked = OwnerCheckMode.VOLUNTARY },
                        )
                        settingsDestination(
                            SettingsHost(
                                onBack = { navController.popBackStack() },
                                navigate = { navController.navigate(it) },
                                // Not a drawer destination any more: Settings → Security → Credential, and back to Settings.
                                onOpenCredential = { navController.navigate(CredentialRoute) { launchSingleTop = true } },
                                onEnableAppLock = onEnableAppLock,
                                onAccountClick = { showAccount = true },
                                onOpenAccountSite = { uri.openUri(portal) },
                                onOwnerCheck = { holdOff -> asked = if (holdOff) OwnerCheckMode.HOLD_OFF else OwnerCheckMode.VOLUNTARY },
                                onOpenItem = { id -> navController.navigate(ItemDetailRoute(id)) { launchSingleTop = true } },
                                onReleaseUpdate = { navController.navigate(ReleaseUpdateRoute) { launchSingleTop = true } },
                            ),
                        )
                        releaseUpdateDestination(onBack = { navController.popBackStack() })
                        helpDestination(onBack = { navController.popBackStack() })
                        debugTools.register(
                            this,
                            DebugHost(theme.mode, theme.set, onBack = { navController.popBackStack() }),
                        )
                    }
                }
            }
        }

        checkMode?.let { mode ->
            OwnerCheckRoute(
                mode = mode,
                onDone = {
                    asked = null
                    armed = false
                },
                onCancel = { asked = null },
            )
        }
    }

    LaunchedEffect(launchRoute) {
        if (launchRoute != null) navController.navigateTopLevel(launchRoute)
    }

    // A tap on the "Vault updates" notification: the update screen (the owner check, if due, still comes first).
    LaunchedEffect(openUpdate) {
        if (!openUpdate) return@LaunchedEffect
        releaseVm.openTaken()
        navController.navigate(ReleaseUpdateRoute) { launchSingleTop = true }
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
            photo = photo,
            onDismiss = { showAccount = false },
            onLockVault = {
                showAccount = false
                onLockVault()
            },
            onChangeName = {
                showAccount = false
                navController.navigate(ChangeNameRoute) { launchSingleTop = true }
            },
            onSharedProfile = {
                showAccount = false
                navController.navigate(SharedProfileRoute) { launchSingleTop = true }
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
