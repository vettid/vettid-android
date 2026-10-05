package com.vettid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.vettid.app.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vettid.app.R
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.feature.onboarding.AppLockScreen
import com.vettid.feature.onboarding.OnboardingFlow
import com.vettid.feature.onboarding.UnlockRoute
import kotlinx.serialization.Serializable

@Serializable
private data object StartingDest

@Serializable
private data object UnreachableDest

@Serializable
private data object OnboardingDest

@Serializable
private data object UnlockDest

@Serializable
private data object MainDest

private fun destinationOf(phase: AppPhase): Any = when (phase) {
    AppPhase.Starting -> StartingDest
    is AppPhase.Unreachable -> UnreachableDest
    AppPhase.SignedOut, is AppPhase.TermsRequired, is AppPhase.Setup, is AppPhase.Replaced -> OnboardingDest
    AppPhase.Locked -> UnlockDest
    AppPhase.Unlocked -> MainDest
}

/**
 * The root of the UI: one destination per vault phase (onboarding, unlock,
 * the app), each with its own ViewModels, so that leaving a phase forgets its
 * state (PINs and passwords included). The biometric app lock (D6) covers
 * everything while locked; what is under it stays composed but hidden from
 * accessibility services.
 */
@Composable
fun VettIdApp(
    onUnlockApp: () -> Unit,
    onEnableAppLock: () -> Unit,
    launchRoute: Any?,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val lock by viewModel.lock.collectAsStateWithLifecycle()
    val alarm by viewModel.alarm.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val portal = stringResource(R.string.account_portal_url)
    val nav = rememberNavController()
    val target = destinationOf(phase)

    LaunchedEffect(target) {
        nav.navigate(target) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    val locked = lock == AppLockState.LOCKED
    // Debug builds expose test tags as resource ids, so that adb (uiautomator) can drive two phones at once.
    val root = if (BuildConfig.DEBUG) Modifier.fillMaxSize().semantics { testTagsAsResourceId = true } else Modifier.fillMaxSize()
    Box(root) {
        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
            NavHost(nav, startDestination = StartingDest) {
                composable<StartingDest> { FullScreenProgress(stringResource(R.string.root_starting)) }
                composable<UnreachableDest> {
                    val kind = (phase as? AppPhase.Unreachable)?.failure
                    FormScaffold(
                        title = stringResource(R.string.root_unreachable_title),
                        body = kind?.let { stringResource(it.messageRes()) },
                        primaryLabel = stringResource(R.string.root_retry),
                        onPrimary = viewModel::retry,
                    ) {}
                }
                composable<OnboardingDest> { OnboardingFlow(onOpenAccountSite = { uri.openUri(portal) }) }
                composable<UnlockDest> { UnlockRoute() }
                composable<MainDest> {
                    AppShell(
                        launchRoute = launchRoute,
                        accountName = account?.displayName ?: stringResource(R.string.account_placeholder_name),
                        accountDetail = account?.email ?: "",
                        alarm = alarm,
                        onLockVault = viewModel::lockVault,
                        onSignOut = viewModel::signOut,
                        onEnableAppLock = onEnableAppLock,
                    )
                }
            }
        }
        if (lock == AppLockState.PENDING) FullScreenProgress(stringResource(R.string.root_starting))
        if (locked) {
            AppLockScreen(onUnlock = onUnlockApp)
            LaunchedEffect(Unit) { onUnlockApp() }
        }
    }
}
