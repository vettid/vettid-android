package com.vettid.feature.credential

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.policy.labelRes
import com.vettid.core.data.policy.messageRes
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.components.StrengthMeter
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the new-credential form. */
data class NewCredentialUiState(
    val pin: String = "",
    val current: String = "",
    val password: String = "",
    val confirm: String = "",
    val strength: PasswordPolicy.Strength = PasswordPolicy.Strength.TOO_SHORT,
    val problem: PasswordPolicy.Problem? = null,
    val mismatch: Boolean = false,
    /** The member read that every critical item is destroyed. */
    val acknowledged: Boolean = false,
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val done: Boolean = false,
) {
    val filled: Boolean
        get() = pin.length >= MIN_PIN && current.isNotEmpty() && password.isNotEmpty() && confirm.isNotEmpty() && acknowledged

    override fun toString(): String = "NewCredentialUiState(busy=$busy, error=$error, done=$done)"

    companion object {
        const val MIN_PIN = 6
        const val MAX_PIN = 32
    }
}

/**
 * A new credential (VAULT-MESSAGING 0.15.2 §3.5.5, `credential.reset`): the PIN, the current password and a new
 * one; the old credential and every critical item are destroyed. Wrong entries count as failed owner checks.
 */
@HiltViewModel
class NewCredentialViewModel @Inject constructor(private val repo: CredentialRepository) : ViewModel() {
    private val state = MutableStateFlow(NewCredentialUiState())
    val uiState: StateFlow<NewCredentialUiState> = state.asStateFlow()

    fun setPin(v: String) = state.update {
        it.copy(pin = v.filter { c -> c.isDigit() }.take(NewCredentialUiState.MAX_PIN), error = null)
    }

    fun setCurrent(v: String) = state.update { it.copy(current = v, error = null) }

    fun setPassword(v: String) = state.update {
        it.copy(password = v, strength = PasswordPolicy.strength(v), problem = null, mismatch = false)
    }

    fun setConfirm(v: String) = state.update { it.copy(confirm = v, mismatch = false) }

    fun setAcknowledged(v: Boolean) = state.update { it.copy(acknowledged = v) }

    /** Checks the form, then asks for the final confirmation. */
    fun submit() {
        val s = state.value
        if (!s.filled || s.busy) return
        val p = PasswordPolicy.check(s.password)
        when {
            p != null -> state.update { it.copy(problem = p) }
            s.confirm != s.password -> state.update { it.copy(mismatch = true) }
            else -> state.update { it.copy(confirming = true) }
        }
    }

    fun cancelConfirm() = state.update { it.copy(confirming = false) }

    fun confirm() {
        val s = state.value
        if (!s.confirming) return
        state.update { it.copy(confirming = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                repo.newCredential(s.pin, s.current, s.password)
                state.value = NewCredentialUiState(done = true)
            } catch (e: VaultFailure) {
                // The PIN and the current password are not kept after an answer.
                state.update { it.copy(busy = false, error = e.kind, pin = "", current = "") }
            }
        }
    }
}

/** What the new-credential form can ask for. */
data class NewCredentialActions(
    val setPin: (String) -> Unit = {},
    val setCurrent: (String) -> Unit = {},
    val setPassword: (String) -> Unit = {},
    val setConfirm: (String) -> Unit = {},
    val setAcknowledged: (Boolean) -> Unit = {},
    val submit: () -> Unit = {},
    val cancelConfirm: () -> Unit = {},
    val confirm: () -> Unit = {},
    val back: () -> Unit = {},
)

/** The new-credential form (stateless). */
@Suppress("LongMethod")
@Composable
fun NewCredentialContent(state: NewCredentialUiState, actions: NewCredentialActions) {
    FormScaffold(
        title = stringResource(if (state.done) R.string.credential_new_done else R.string.credential_new_title),
        body = if (state.done) stringResource(R.string.credential_new_done_body) else stringResource(R.string.credential_new_body),
        primaryLabel = stringResource(if (state.done) R.string.credential_done else R.string.credential_new_submit),
        onPrimary = if (state.done) actions.back else actions.submit,
        primaryEnabled = state.done || state.filled,
        busy = state.busy,
        destructive = !state.done,
        onBack = actions.back,
        modifier = Modifier.testTag("new_credential"),
    ) {
        if (state.done) return@FormScaffold
        NoticeCard(
            NoticeKind.URGENT,
            stringResource(R.string.credential_new_warning_title),
            stringResource(R.string.credential_new_warning),
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(state.pin, actions.setPin, stringResource(R.string.credential_new_pin), isPin = true, imeAction = ImeAction.Next)
        Spacer(Modifier.height(Spacing.l))
        SecretField(state.current, actions.setCurrent, stringResource(R.string.credential_password_current), imeAction = ImeAction.Next)
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.password, actions.setPassword, stringResource(R.string.credential_password_new),
            error = state.problem?.let { stringResource(it.messageRes()) },
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(Spacing.s))
        StrengthMeter(level = state.strength.ordinal, label = stringResource(state.strength.labelRes()))
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.confirm, actions.setConfirm, stringResource(R.string.credential_password_confirm),
            error = if (state.mismatch) stringResource(R.string.credential_password_mismatch) else null,
        )
        Spacer(Modifier.height(Spacing.m))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .toggleable(value = state.acknowledged, role = Role.Checkbox, onValueChange = actions.setAcknowledged)
                .testTag("new_credential_ack"),
        ) {
            Checkbox(checked = state.acknowledged, onCheckedChange = null)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.credential_new_ack), style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let {
            Spacer(Modifier.height(Spacing.l))
            val title = stringResource(R.string.credential_new_title)
            NoticeCard(NoticeKind.URGENT, title, stringResource(it.messageRes()), Modifier.testTag("error"))
        }
    }
    if (state.confirming) {
        ConfirmDialog(
            title = stringResource(R.string.credential_new_confirm_title),
            text = stringResource(R.string.credential_new_warning),
            confirmLabel = stringResource(R.string.credential_new_submit),
            onConfirm = actions.confirm,
            onDismiss = actions.cancelConfirm,
            destructive = true,
        )
    }
}
