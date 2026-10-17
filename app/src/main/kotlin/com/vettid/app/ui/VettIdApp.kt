package com.vettid.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.vettid.app.BuildConfig
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vettid.app.R
import com.vettid.core.data.lock.AppLockState
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.core.ui.components.InfoBanner
import com.vettid.feature.onboarding.AppLockScreen
import com.vettid.feature.onboarding.FinishMovePane
import com.vettid.feature.onboarding.FinishMoveViewModel
import com.vettid.feature.onboarding.OnboardingFlow
import com.vettid.feature.onboarding.ReleaseUpdateFlowRoute
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

/** The one-step release update (owner decision 2026-10-09): it locks and unlocks the vault, so it is above the phases. */
@Serializable
private data object UpdateDest

private fun destinationOf(phase: AppPhase): Any = when (phase) {
    AppPhase.Starting -> StartingDest
    is AppPhase.Unreachable -> UnreachableDest
    AppPhase.SignedOut, is AppPhase.Setup -> OnboardingDest
    AppPhase.Locked -> UnlockDest
    AppPhase.Unlocked -> MainDest
}

/**
 * Makes [target] the only root destination, when it is not the one showing already. The effect runs again in every
 * new composition (rotation, fold, theme or font change: the activity is recreated) and each time the app lock opens;
 * the destination of the phase is then still on the back stack (restored, or kept by the controller while the lock
 * showed), and navigating to it again would destroy it with its ViewModels: the screen the member was on, what they
 * typed, a running enrolment or the wait for a transfer (A6 audit R1). While the app lock shows, nothing of the app
 * (the NavHost included) is composed; a phase that changed under the lock is followed on unlock. While [hold] ("Finish
 * the move" is running) the destination showing stays, and the phase is followed when it ends.
 */
@Composable
internal fun RootDestinationEffect(nav: NavHostController, target: Any, locked: Boolean, hold: Boolean = false) {
    LaunchedEffect(nav, target, locked, hold) {
        if (locked || hold) return@LaunchedEffect
        if (nav.currentBackStackEntry?.destination?.hasRoute(target::class) == true) return@LaunchedEffect
        nav.navigate(target) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * The root of the UI: one destination per vault phase (onboarding, unlock,
 * the app), each with its own ViewModels, so that leaving a phase forgets its
 * state (PINs and passwords included). The app lock (D6) replaces
 * everything while locked ([AppLockGate]): nothing under it is composed, so
 * nothing there keeps focus or receives keys.
 */
@Composable
fun VettIdApp(
    /** Asks for the app lock's prompt; true when automatic (shown, resumed), false from the "Unlock" button. */
    onUnlockApp: (auto: Boolean) -> Unit,
    onEnableAppLock: (com.vettid.core.data.prefs.AppLockMethod) -> Unit,
    launchRoute: Any?,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val lock by viewModel.lock.collectAsStateWithLifecycle()
    val alarm by viewModel.alarm.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val paused by viewModel.servicePaused.collectAsStateWithLifecycle()
    val prefsReset by viewModel.preferencesReset.collectAsStateWithLifecycle()
    val retrying by viewModel.retrying.collectAsStateWithLifecycle()
    val canaryVm: CanaryManifestViewModel = hiltViewModel()
    val canaryPrompt by canaryVm.prompt.collectAsStateWithLifecycle()
    // A shared document is taken only while this activity is started (CanaryManifestViewModel.receive).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(canaryVm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { canaryVm.receive() }
    }
    val uri = LocalUriHandler.current
    val portal = viewModel.portalUrl
    val nav = rememberNavController()
    val update by viewModel.updateProgress.collectAsStateWithLifecycle()
    val target = if (update != null) UpdateDest else destinationOf(phase)

    val locked = lock == AppLockState.LOCKED
    // "Finish the move" locks and then unlocks the vault (§11.14.10): the screen it runs on stays until the unlock has
    // answered, instead of the unlock screen in between (the phone is locked all the same; a failure then shows it).
    val finishingMove by viewModel.finishingMove.collectAsStateWithLifecycle()
    RootDestinationEffect(nav, target, locked, hold = finishingMove)

    // Debug builds expose test tags as resource ids, so that adb (uiautomator) can drive two phones at once.
    val root = if (BuildConfig.DEBUG) Modifier.fillMaxSize().semantics { testTagsAsResourceId = true } else Modifier.fillMaxSize()
    Box(root) {
        AppLockGate(
            locked = locked,
            onAutoPrompt = { onUnlockApp(true) },
            lockScreen = {
                val method by viewModel.lockMethod.collectAsStateWithLifecycle()
                AppLockScreen(onUnlock = { onUnlockApp(false) }, method = method)
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                // MEMBER-API 1.2.0: the vault service is paused for maintenance. Non-blocking: every screen stays usable
                // below it, and only the generic text is shown (never the operator's reason).
                if (paused) InfoBanner(stringResource(R.string.root_service_paused), Modifier.testTag("service_paused_banner"))
                // A6 audit P4: a corrupt preferences file was replaced; said once, here, until dismissed.
                if (prefsReset) {
                    InfoBanner(
                        stringResource(R.string.root_preferences_reset),
                        Modifier.testTag("preferences_reset_banner"),
                        actionLabel = stringResource(R.string.root_preferences_reset_ok),
                        onAction = viewModel::acknowledgePreferencesReset,
                        statusBarPadding = !paused,
                    )
                }
                // Release R (§11.14.10): "Finish the move" and "This phone is your vault's phone again".
                val topBanner = paused || prefsReset
                val moveNotice = MoveNotices(viewModel, phase == AppPhase.Unlocked, appLocked = locked, statusBarPadding = !topBanner)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .then(if (topBanner || moveNotice) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
                ) {
                    NavHost(nav, startDestination = StartingDest) {
                        composable<StartingDest> { FullScreenProgress(stringResource(R.string.root_starting)) }
                        composable<UnreachableDest> {
                            val kind = (phase as? AppPhase.Unreachable)?.failure
                            UnreachableScreen(kind, retrying, viewModel::retry, viewModel::startOver)
                        }
                        composable<OnboardingDest> { OnboardingFlow(onOpenAccountSite = { uri.openUri(portal) }) }
                        composable<UnlockDest> { UnlockRoute() }
                        composable<UpdateDest> { ReleaseUpdateFlowRoute() }
                        composable<MainDest> {
                            // The tags' colours from the vault's registry, for every screen of the open app (ANDROID-PLAN 0.1.25).
                            TagColorsHost {
                                AppShell(
                                    launchRoute = launchRoute,
                                    accountName = account?.fullName ?: account?.displayEmail
                                        ?: stringResource(R.string.account_placeholder_name),
                                    account = account,
                                    portalUrl = portal,
                                    alarm = alarm,
                                    onLockVault = viewModel::lockVault,
                                    onEnableAppLock = onEnableAppLock,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (lock == AppLockState.PENDING) FullScreenProgress(stringResource(R.string.root_starting))

        // A canary manifest shared to the app (VAULT-RELEASES §10.1 step 9): asked about only while the app is open.
        if (!locked && lock != AppLockState.PENDING) {
            canaryPrompt?.let { CanaryManifestDialog(it, canaryVm::confirm, canaryVm::dismiss) }
        }
    }
}

/**
 * Release R (§11.14.10) banners; true when one is shown. A move to this phone that is not finished is asked about at
 * every start in the open app ([unlocked]; on the unlock screen the unlock itself finishes it), with "Finish the move" in
 * a dialog. The old phone that took the vault back ("The move didn't finish" succeeded) is told once.
 */
@Suppress("CyclomaticComplexMethod") // one banner per notice, and the dialog
@Composable
private fun MoveNotices(viewModel: RootViewModel, unlocked: Boolean, appLocked: Boolean, statusBarPadding: Boolean): Boolean {
    val finishMove by viewModel.finishMovePending.collectAsStateWithLifecycle()
    val movedBack by viewModel.movedBack.collectAsStateWithLifecycle()
    val moveNotDone by viewModel.moveNotDone.collectAsStateWithLifecycle()
    var finishMoveOpen by rememberSaveable { mutableStateOf(false) }
    val askFinishMove = finishMove && unlocked
    if (askFinishMove) {
        InfoBanner(
            stringResource(R.string.root_finish_move),
            Modifier.testTag("finish_move_banner"),
            actionLabel = stringResource(R.string.root_finish_move_action),
            onAction = { finishMoveOpen = true },
            statusBarPadding = statusBarPadding,
        )
    }
    if (movedBack) {
        InfoBanner(
            stringResource(R.string.root_moved_back),
            Modifier.testTag("moved_back_banner"),
            actionLabel = stringResource(R.string.root_preferences_reset_ok),
            onAction = viewModel::acknowledgeMovedBack,
            statusBarPadding = statusBarPadding && !askFinishMove,
        )
    }
    // The vault confirmed at the end of the 7 days that this phone is still its app: the move did not happen.
    if (moveNotDone) {
        InfoBanner(
            stringResource(R.string.root_move_not_done),
            Modifier.testTag("move_not_done_banner"),
            actionLabel = stringResource(R.string.root_preferences_reset_ok),
            onAction = viewModel::acknowledgeMoveNotDone,
            statusBarPadding = statusBarPadding && !askFinishMove && !movedBack,
        )
    }
    // Kept open through the lock and unlock it sends (and never dismissed while they run); never over the app lock. Once
    // the vault is locked and the hold is over (its bound, or the outcome), the unlock screen takes over and says why.
    val finishing by viewModel.finishingMove.collectAsStateWithLifecycle()
    val finishVm: FinishMoveViewModel = hiltViewModel()
    val finishState by finishVm.uiState.collectAsStateWithLifecycle()
    val showFinishMove = finishMove && !appLocked && (unlocked || finishing)
    if (finishMoveOpen && showFinishMove) {
        val busy = finishState.busy
        Dialog(
            onDismissRequest = { if (!busy) finishMoveOpen = false },
            properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        ) {
            Surface(shape = MaterialTheme.shapes.large) {
                FinishMovePane(finishState, finishVm, onDone = { if (!busy) finishMoveOpen = false })
            }
        }
    }
    return askFinishMove || movedBack || moveNotDone
}
