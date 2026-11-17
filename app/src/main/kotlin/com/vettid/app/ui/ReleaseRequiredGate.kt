package com.vettid.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vettid.app.R
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FullScreenProgress
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.ui.format.failureText
import com.vettid.core.ui.theme.Spacing
import com.vettid.feature.credential.CredentialAlarmRoute
import com.vettid.feature.credential.CredentialRotateRoute
import com.vettid.feature.credential.alarmDestination
import com.vettid.feature.credential.rotateDestination
import com.vettid.feature.onboarding.ReleaseUpdateContent
import com.vettid.feature.onboarding.ReleaseUpdateViewModel
import com.vettid.feature.settings.DeleteVaultRoute
import com.vettid.feature.settings.HelpRoute
import com.vettid.feature.settings.deleteVaultDestination
import com.vettid.feature.settings.helpDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** What the open vault shows (Rule 3: the app requires the vault's current release). */
enum class OpenAppScreen {
    /** The vault is behind its release: the update, with no way back. */
    RELEASE_GATE,

    /** The vault's release is read (again, until that succeeds): "Checking your vault's software". */
    RELEASE_CHECK,

    /** The app shell. */
    SHELL,
    ;

    companion object {
        /**
         * From the release gate's state ([ReleaseUpdateRepository]): the update when the vault is [behind] (also while its
         * release is read again), the check while anything else is [required], and the shell only when neither holds.
         */
        fun of(required: Boolean, behind: Boolean): OpenAppScreen = when {
            behind -> RELEASE_GATE
            required -> RELEASE_CHECK
            else -> SHELL
        }
    }
}

/**
 * The failed check's text (Rule 3): the manifest could not be verified, or could not be read (every other failure: the
 * read had no answer, or the server answered without the document).
 */
@StringRes
internal fun checkFailureText(kind: FailureKind): Int =
    if (kind == FailureKind.MANIFEST) FailureKind.MANIFEST.messageRes() else FailureKind.MANIFEST_UNAVAILABLE.messageRes()

/** The release gate's state, read straight from the repository so that its first frame is the one published with the phase. */
@HiltViewModel
class ReleaseGateViewModel @Inject constructor(private val updates: ReleaseUpdateRepository) : ViewModel() {
    val required: StateFlow<Boolean> = updates.required
    val behind: StateFlow<Boolean> = updates.behind
    val failure: StateFlow<FailureKind?> = updates.checkFailure

    /** "Try again": the manifest at once (the automatic retries go on). */
    fun tryAgain() = updates.checkNow()
}

@Serializable
internal data object GateStartRoute

/**
 * (Rule 3, the release gate) Instead of the shell, while the open vault must first move to its current release
 * ([OpenAppScreen.RELEASE_GATE]: the update screen, "Approve and update" with no way back) or while its release is read
 * ([OpenAppScreen.RELEASE_CHECK]: "Checking your vault's software", with the failure and "Try again" once a read failed).
 * The only ways on: Account (the sheet with Lock vault, [onAccount]), Help, and Delete vault (to enroll anew when the
 * update cannot complete). The clone alarm, the pending start-over and the relay's refusal of the vault's key stay above
 * it ([banners]), with the alarm's and the key replacement's screens. Drawn inside the owner check's layer: the update asks
 * for a due check first, and the check covers this as it covers the shell. Every text is an existing one.
 */
@Suppress("LongParameterList", "LongMethod") // the gate's screens and its banners
@Composable
fun ReleaseRequiredGate(
    screen: OpenAppScreen,
    failure: FailureKind?,
    onTryAgain: () -> Unit,
    onAccount: () -> Unit,
    alarm: CredentialAlarm?,
    relayRefused: Boolean,
    deletionBanner: @Composable (first: Boolean) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination
    fun on(route: kotlin.reflect.KClass<*>) = current?.hierarchy?.any { it.hasRoute(route) } == true
    val back: () -> Unit = { nav.popBackStack() }
    Column(modifier.fillMaxSize().testTag("release_required")) {
        // The clone alarm (§3.5.9), the pending start-over and the relay's refusal (§3.4): as in the shell.
        val alarmShown = alarm != null && !on(CredentialAlarmRoute::class)
        if (alarmShown) {
            UrgentBanner(
                text = stringResource(com.vettid.feature.credential.R.string.credential_alarm_banner),
                actionLabel = stringResource(com.vettid.feature.credential.R.string.credential_alarm_review),
                onClick = { nav.navigate(CredentialAlarmRoute) { launchSingleTop = true } },
                modifier = Modifier.testTag("alarm_banner"),
            )
        }
        val deletionShown = deletionBanner(!alarmShown)
        val relayShown = relayNoticeShown(relayRefused) { on(it) }
        if (relayShown) {
            RelayRefusedNotice(
                first = !alarmShown && !deletionShown,
                onReplaceKeys = { nav.navigate(CredentialRotateRoute) { launchSingleTop = true } },
            )
        }
        val bannerShown = alarmShown || deletionShown || relayShown
        Box(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .then(if (bannerShown) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
        ) {
            NavHost(nav, startDestination = GateStartRoute) {
                composable<GateStartRoute> { GateStart(screen, failure, onTryAgain) }
                helpDestination(onBack = back)
                deleteVaultDestination(onBack = back)
                alarmDestination(onBack = back)
                rotateDestination(onBack = back)
            }
        }
        // The ways to leave the vault, under the gate's own screen only (the pushed screens have their own Back).
        if (current == null || on(GateStartRoute::class)) {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onAccount, modifier = Modifier.testTag("release_required_account")) {
                        Text(stringResource(com.vettid.feature.settings.R.string.settings_section_account))
                    }
                    TextButton(
                        onClick = { nav.navigate(HelpRoute) { launchSingleTop = true } },
                        modifier = Modifier.testTag("release_required_help"),
                    ) {
                        Text(stringResource(R.string.nav_help))
                    }
                    TextButton(
                        onClick = { nav.navigate(DeleteVaultRoute) { launchSingleTop = true } },
                        modifier = Modifier.testTag("release_required_delete"),
                    ) {
                        Text(
                            stringResource(com.vettid.feature.settings.R.string.settings_delete_vault),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** The gate's own screen: the update (no way back), or the check with its failure and "Try again". */
@Composable
private fun GateStart(screen: OpenAppScreen, failure: FailureKind?, onTryAgain: () -> Unit) {
    if (screen == OpenAppScreen.RELEASE_GATE) {
        val vm: ReleaseUpdateViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ReleaseUpdateContent(state, vm, onBack = null)
        return
    }
    Column(Modifier.fillMaxSize().testTag("release_checking")) {
        Box(Modifier.weight(1f)) {
            FullScreenProgress(stringResource(com.vettid.feature.onboarding.R.string.unlock_checking))
        }
        failure?.let { kind ->
            NoticeCard(
                NoticeKind.WARNING,
                title = "",
                body = failureText(checkFailureText(kind)),
                modifier = Modifier.padding(Spacing.m).testTag("release_check_failed"),
                announce = LiveRegionMode.Polite,
            ) {
                TextButton(onClick = onTryAgain, modifier = Modifier.testTag("release_check_retry")) {
                    Text(stringResource(com.vettid.feature.onboarding.R.string.release_update_retry))
                }
            }
        }
    }
}
