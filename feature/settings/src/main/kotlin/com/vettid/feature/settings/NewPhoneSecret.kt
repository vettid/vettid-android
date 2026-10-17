package com.vettid.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.policy.PinPolicy
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.FormScaffold
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.SecretField
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of Settings → Security → New phone secret. Both entries live here only until the answer. */
data class NewPhoneSecretUiState(
    val pin: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean get() = !busy && pin.length >= MIN_PIN && password.isNotEmpty()

    override fun toString(): String = "NewPhoneSecretUiState(busy=$busy, error=$error, done=$done)"

    private companion object {
        const val MIN_PIN = 6
    }
}

/**
 * `vault.secret.renew` (VAULT-MESSAGING 0.33.0 §11.14.9; ANDROID-PLAN "Other screens"): the PIN and the password; the
 * vault gives this phone a new secret with the PIN unchanged and replaces its DEK, so the old secret opens no later
 * stored state. For a member who fears that something ran as the app on this phone. Release R only.
 */
@HiltViewModel
class NewPhoneSecretViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(NewPhoneSecretUiState())
    val uiState: StateFlow<NewPhoneSecretUiState> = state.asStateFlow()

    fun setPin(v: String) = state.update { it.copy(pin = v.filter(Char::isDigit).take(PinPolicy.MAX_LENGTH), error = null) }

    fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    fun submit() {
        val s = state.value
        if (!s.canSubmit) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                vault.renewPhoneSecret(s.pin, s.password)
                state.value = NewPhoneSecretUiState(done = true)
            } catch (e: VaultFailure) {
                // Neither entry is kept after an answer.
                state.update { it.copy(busy = false, error = e.kind, pin = "", password = "") }
            }
        }
    }
}

/** Settings → Security → New phone secret (stateless). */
@Composable
fun NewPhoneSecretContent(
    state: NewPhoneSecretUiState,
    onPin: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    FormScaffold(
        title = stringResource(R.string.settings_security_new_secret),
        body = stringResource(if (state.done) R.string.settings_new_secret_done else R.string.settings_new_secret_body),
        primaryLabel = stringResource(if (state.done) R.string.settings_done else R.string.settings_new_secret_submit),
        onPrimary = if (state.done) onBack else onSubmit,
        primaryEnabled = state.done || state.canSubmit,
        busy = state.busy,
        onBack = onBack,
        modifier = Modifier.testTag("new_phone_secret"),
    ) {
        if (state.done) return@FormScaffold
        // ANDROID-PLAN 0.1.43: a new secret does not end what code that ran as the app could reach; said before the PIN.
        NoticeCard(
            NoticeKind.WARNING,
            stringResource(R.string.settings_security_new_secret),
            stringResource(R.string.settings_new_secret_not_enough),
            Modifier.testTag("new_secret_not_enough"),
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.pin, onPin, stringResource(R.string.settings_delete_pin), isPin = true, imeAction = ImeAction.Next,
            modifier = Modifier.testTag("new_secret_pin"),
        )
        Spacer(Modifier.height(Spacing.l))
        SecretField(
            state.password, onPassword, stringResource(R.string.settings_pin_password), onImeAction = onSubmit,
            modifier = Modifier.testTag("new_secret_password"),
        )
        state.error?.let {
            Spacer(Modifier.height(Spacing.l))
            NoticeCard(
                NoticeKind.URGENT,
                stringResource(R.string.settings_security_new_secret),
                stringResource(it.messageRes()),
                Modifier.testTag("error"),
            )
        }
    }
}
