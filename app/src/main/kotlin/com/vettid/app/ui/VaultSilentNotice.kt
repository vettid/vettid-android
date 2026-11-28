package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.CallAvailability
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.ui.components.UrgentBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * (#19721 step 4; owner decision a, GC #19767; texts approved there, the same as the iPhone app's) "Your vault isn't
 * answering": a vault the service still counts as running that answered none of two checks in a row
 * ([VaultRepository.vaultSilent]). An [UrgentBanner], as the relay notice: the vault can't be used until it answers. Title and
 * body are two lines of its text. Not dismissible: it goes when anything arrives from the vault. Announced politely: it
 * comes while the member may be reading. **Unlock again** ([onUnlockAgain]) opens the unlock screen; nothing unlocks by
 * itself. [first] says whether it is the topmost banner (which pads for the status bar).
 */
@Composable
fun VaultSilentNotice(first: Boolean, onUnlockAgain: () -> Unit) {
    UrgentBanner(
        text = stringResource(R.string.vault_silent_title) + "\n" + stringResource(R.string.vault_silent_body),
        actionLabel = stringResource(R.string.vault_silent_action),
        onClick = onUnlockAgain,
        modifier = Modifier.testTag("vault_silent_notice"),
        statusBarPadding = first,
        liveRegion = LiveRegionMode.Polite,
    )
}

/** Whether the shell shows the notice: while the vault is [silent], and never during a call (ringing included). */
internal fun vaultSilentShown(silent: Boolean, calls: CallAvailability): Boolean = silent && calls != CallAvailability.IN_CALL

/** The notice's state and its action, for the shell. */
@HiltViewModel
class VaultSilentViewModel @Inject constructor(
    private val vault: VaultRepository,
    calls: CallAccess,
) : ViewModel() {
    /** [vaultSilentShown] of the vault's state and the call's. */
    val shown: StateFlow<Boolean> = combine(vault.vaultSilent, calls.availability, ::vaultSilentShown)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "Unlock again": only while the notice shows. */
    fun unlockAgain() {
        if (shown.value) vault.unlockAgain()
    }
}
