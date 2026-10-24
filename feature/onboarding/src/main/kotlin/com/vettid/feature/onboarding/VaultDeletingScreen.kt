package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.AccountRepository
import com.vettid.core.data.vault.DeletionCheck
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.PrimaryButton
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of "Your vault is being deleted" (ANDROID-PLAN 0.1.54). */
data class VaultDeletingUiState(
    /** Past the bound and still listed: "Still finishing", with "Check again". */
    val slow: Boolean = false,
    /** (0.1.78) The member API's `410` alone: "Your vault was deleted"; nothing is erased without the member (or the vault's word). */
    val apiOnly: Boolean = false,
    /** A "Check again" read is under way. */
    val checking: Boolean = false,
    /** What the last "Check again" found, when the vault was not gone: still listed, or not reachable. */
    val lastCheck: DeletionCheck? = null,
    /** The member asked to remove VettID from this phone and is asked to confirm (offered only past the bound). */
    val removeConfirm: Boolean = false,
    /** The confirmed removal runs. */
    val removing: Boolean = false,
)

/** What the screen can ask for. */
interface VaultDeletingActions {
    /** "Check again": one read of the status; a second tap waits for the first. */
    fun checkAgain()

    /** "Remove from this phone", past the bound: asks for the confirmation. */
    fun askRemove()

    fun dismissRemove()

    /** The confirmed removal: the existing local erase (`eraseThisPhone`). */
    fun confirmRemove()
}

/**
 * A vault being deleted (ANDROID-PLAN 0.1.54): nothing here changes the vault or this phone's keys (no unlock, no PIN, no
 * update, no daily check, no cancel). The status is re-read by the data layer on its timer; after the bound the member
 * may "Check again". The vault gone moves the app to "no vault" by itself.
 */
@HiltViewModel
class VaultDeletingViewModel @Inject constructor(private val account: AccountRepository) : ViewModel(), VaultDeletingActions {
    private val state = MutableStateFlow(VaultDeletingUiState())
    val uiState: StateFlow<VaultDeletingUiState> = state.asStateFlow()

    /** The phase's own "still finishing" (the root passes it on). */
    fun setSlow(slow: Boolean) = state.update { it.copy(slow = slow) }

    /** The phase's `apiOnly` (the root passes it on). */
    fun setApiOnly(apiOnly: Boolean) = state.update { it.copy(apiOnly = apiOnly) }

    override fun checkAgain() {
        if (state.value.checking) return
        state.update { it.copy(checking = true, lastCheck = null) }
        viewModelScope.launch {
            val r = account.checkDeletion()
            state.update { it.copy(checking = false, lastCheck = r.takeIf { c -> c != DeletionCheck.GONE }) }
        }
    }

    // Nothing here erases by itself: a vault is left only by a definite signal (none yet) or by this confirmed removal.
    // The vault's own `vault_deleted` word says it will never run again, so a removal the member confirms is safe.
    override fun askRemove() {
        if (state.value.slow) state.update { it.copy(removeConfirm = true) }
    }

    override fun dismissRemove() = state.update { it.copy(removeConfirm = false) }

    override fun confirmRemove() {
        if (state.value.removing || !state.value.slow) return
        state.update { it.copy(removeConfirm = false, removing = true) }
        viewModelScope.launch {
            try {
                account.eraseThisPhone()
            } finally {
                state.update { it.copy(removing = false) }
            }
        }
    }
}

@Composable
fun VaultDeletingRoute(
    slow: Boolean,
    onOpenAccountSite: () -> Unit,
    apiOnly: Boolean = false,
    viewModel: VaultDeletingViewModel = hiltViewModel(),
) {
    LaunchedEffect(slow) { viewModel.setSlow(slow) }
    LaunchedEffect(apiOnly) { viewModel.setApiOnly(apiOnly) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VaultDeletingContent(state, viewModel, onOpenAccountSite)
}

private fun titleRes(state: VaultDeletingUiState): Int = when {
    state.apiOnly -> R.string.vault_deleted_api_title
    state.slow -> R.string.vault_deleting_slow_title
    else -> R.string.vault_deleting_title
}

private fun checkRes(c: DeletionCheck): Int = when (c) {
    DeletionCheck.LISTED -> R.string.vault_deleting_still
    DeletionCheck.API_ONLY -> R.string.vault_deleted_api_again
    DeletionCheck.UNCONFIRMED, DeletionCheck.UNCONFIRMED_CLOCK -> R.string.vault_deleting_cannot_confirm
    else -> R.string.vault_deleting_unreachable
}

private fun bodyRes(state: VaultDeletingUiState): Int = when {
    state.apiOnly && state.slow -> R.string.vault_deleted_api_slow_body
    state.apiOnly -> R.string.vault_deleted_api_body
    state.slow -> R.string.vault_deleting_slow_body
    else -> R.string.vault_deleting_body
}

/** The screen for [state] (stateless). The progress has no percentage; the title is a polite live region. */
@Composable
fun VaultDeletingContent(state: VaultDeletingUiState, actions: VaultDeletingActions, onOpenAccountSite: () -> Unit) {
    RemoveConfirm(state, actions)
    Surface(Modifier.fillMaxSize().testTag("vault_deleting"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Spacer(Modifier.height(Spacing.xl))
                Text(
                    stringResource(titleRes(state)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    stringResource(bodyRes(state)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.slow && !state.apiOnly && state.lastCheck == null) {
                    Text(
                        stringResource(R.string.vault_deleting_unconfirmed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("vault_deleting_unconfirmed"),
                    )
                }
                if (!state.slow || state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.lastCheck?.let { c ->
                    Text(
                        stringResource(checkRes(c)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("vault_deleting_check").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (c == DeletionCheck.UNCONFIRMED_CLOCK) {
                        Text(stringResource(R.string.vault_deleting_clock_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (state.slow || state.apiOnly) {
                    PrimaryButton(
                        stringResource(R.string.vault_deleting_check_again),
                        actions::checkAgain,
                        enabled = !state.checking,
                        busy = state.checking,
                    )
                }
                if (state.slow) {
                    TextButton(
                        onClick = actions::askRemove,
                        enabled = !state.removing,
                        modifier = Modifier.fillMaxWidth().testTag("vault_deleting_remove"),
                    ) {
                        Text(stringResource(R.string.vault_deleting_remove))
                    }
                }
                TextButton(onClick = onOpenAccountSite, modifier = Modifier.fillMaxWidth().testTag("vault_deleting_account_site")) {
                    Text(stringResource(R.string.vault_deleting_account_site))
                }
            }
        }
    }
}

@Composable
private fun RemoveConfirm(state: VaultDeletingUiState, actions: VaultDeletingActions) {
    if (!state.removeConfirm) return
    ConfirmDialog(
        title = stringResource(R.string.vault_deleting_remove_confirm_title),
        text = stringResource(R.string.vault_deleting_remove_confirm_body),
        confirmLabel = stringResource(R.string.vault_deleting_remove_confirm),
        onConfirm = actions::confirmRemove,
        onDismiss = actions::dismissRemove,
        modifier = Modifier.testTag("vault_deleting_remove_confirm"),
        destructive = true,
    )
}
