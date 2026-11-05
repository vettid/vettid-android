package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.vault.DeletionView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.data.vault.messageRes
import com.vettid.core.data.vault.vaultCode
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.format.failureText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** A start-over requested on the account portal (VAULT-MESSAGING 0.16.0 §11.11.9) and its cancel from the app. */
@HiltViewModel
class PendingDeletionViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    val deletion: StateFlow<DeletionView?> = vault.pendingDeletion
    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()
    private val failureFlow = MutableStateFlow<DeletionCancelFailure?>(null)

    /** Why the last "Cancel deletion" did not go through (A6 audit E4); gone with the next try. */
    val failure: StateFlow<DeletionCancelFailure?> = failureFlow.asStateFlow()

    init {
        // A failure belongs to the deletion it was about: a changed or gone deletion clears it.
        viewModelScope.launch { vault.pendingDeletion.collect { failureFlow.value = null } }
    }

    fun cancel() {
        if (busyFlow.value) return
        busyFlow.value = true
        failureFlow.value = null
        viewModelScope.launch {
            try {
                vault.cancelDeletion()
            } catch (e: VaultFailure) {
                // The banner stays, and says that it did not go through; the account site can cancel too.
                failureFlow.value = DeletionCancelFailure(e.kind, e.shownCode)
            } finally {
                busyFlow.value = false
            }
        }
    }
}

/** A failed "Cancel deletion": the kind, and the vault's code when the app does not map it (`FailureKind.vaultCode`). */
data class DeletionCancelFailure(val kind: FailureKind, val shownCode: String?)

/**
 * The urgent banner of a pending start-over (ANDROID-PLAN 0.1.7 "Pending deletion"): the time left and **Cancel
 * deletion** (signed by the app key) when the status names the deletion; otherwise the account site's start-over
 * page, where it can be cancelled.
 */
@Composable
fun PendingDeletionBanner(
    d: DeletionView,
    first: Boolean,
    onCancel: () -> Unit,
    onOpenSite: () -> Unit,
    now: Instant = Instant.now(),
    failure: DeletionCancelFailure? = null,
) {
    val hours = Duration.between(now, d.deletesAt).toHours().coerceAtLeast(0).toInt()
    val text = if (d.executing) {
        stringResource(R.string.deletion_executing)
    } else {
        pluralStringResource(R.plurals.deletion_pending, hours, Times.full(d.deletesAt), hours)
    }
    // The banner is an assertive live region: a failed cancel is spoken when it is added to the text.
    val why = failure?.let { failureText(it.kind.messageRes(), it.kind.vaultCode(it.shownCode)) }
    val shown = if (why != null) "$text $why" else text
    UrgentBanner(
        text = shown,
        actionLabel = stringResource(if (d.cancellable) R.string.deletion_cancel else R.string.deletion_open_site),
        onClick = if (d.cancellable) onCancel else onOpenSite,
        modifier = Modifier.testTag("deletion_banner"),
        statusBarPadding = first,
    )
}
