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
    /** A "Check again" read is under way. */
    val checking: Boolean = false,
    /** What the last "Check again" found, when the vault was not gone: still listed, or not reachable. */
    val lastCheck: DeletionCheck? = null,
)

/** What the screen can ask for. */
interface VaultDeletingActions {
    /** "Check again": one read of the status; a second tap waits for the first. */
    fun checkAgain()
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

    override fun checkAgain() {
        if (state.value.checking) return
        state.update { it.copy(checking = true, lastCheck = null) }
        viewModelScope.launch {
            val r = account.checkDeletion()
            state.update { it.copy(checking = false, lastCheck = r.takeIf { c -> c != DeletionCheck.GONE }) }
        }
    }
}

@Composable
fun VaultDeletingRoute(slow: Boolean, onOpenAccountSite: () -> Unit, viewModel: VaultDeletingViewModel = hiltViewModel()) {
    LaunchedEffect(slow) { viewModel.setSlow(slow) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VaultDeletingContent(state, viewModel, onOpenAccountSite)
}

/** The screen for [state] (stateless). The progress has no percentage; the title is a polite live region. */
@Composable
fun VaultDeletingContent(state: VaultDeletingUiState, actions: VaultDeletingActions, onOpenAccountSite: () -> Unit) {
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
                    stringResource(if (state.slow) R.string.vault_deleting_slow_title else R.string.vault_deleting_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    stringResource(if (state.slow) R.string.vault_deleting_slow_body else R.string.vault_deleting_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.slow || state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.lastCheck?.let { c ->
                    Text(
                        stringResource(
                            if (c == DeletionCheck.LISTED) R.string.vault_deleting_still else R.string.vault_deleting_unreachable,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("vault_deleting_check").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (state.slow) {
                    PrimaryButton(
                        stringResource(R.string.vault_deleting_check_again),
                        actions::checkAgain,
                        enabled = !state.checking,
                        busy = state.checking,
                    )
                }
                TextButton(onClick = onOpenAccountSite, modifier = Modifier.fillMaxWidth().testTag("vault_deleting_account_site")) {
                    Text(stringResource(R.string.vault_deleting_account_site))
                }
            }
        }
    }
}
