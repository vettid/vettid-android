package com.vettid.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.UnlockAttempt
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import com.vettid.core.data.vault.messageRes
import com.vettid.core.ui.components.NoticeCard
import com.vettid.core.ui.components.NoticeKind
import com.vettid.core.ui.components.excludeFromAutofill
import com.vettid.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Finish the move" on the new phone (VAULT-MESSAGING 0.33.0 §11.14.10 step 4). The PIN is in memory only. */
data class FinishMoveUiState(
    val pin: String = "",
    val busy: Boolean = false,
    val done: Boolean = false,
    val failure: FailureKind? = null,
) {
    override fun toString(): String = "FinishMoveUiState(busy=$busy, done=$done, failure=$failure)"
}

/**
 * Right after the transfer's `device.paired` and the credential: `vault.lock`, then `vault.unlock` with this phone's
 * own secret, which reads the stored header and state and so proves that both writes of the transfer are durable.
 * `transfer_incomplete`: "The move to this phone did not finish. Approve it again on your old phone." It can wait
 * ("Later"): the app asks again at every start, in a banner of the open app, until an unlock from storage succeeds
 * ([com.vettid.core.data.vault.VaultRepository.finishMovePending]); an unlock on the unlock screen does the same.
 */
@HiltViewModel
class FinishMoveViewModel @Inject constructor(private val vault: VaultRepository) : ViewModel() {
    private val state = MutableStateFlow(FinishMoveUiState())
    val uiState: StateFlow<FinishMoveUiState> = state.asStateFlow()

    fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), failure = null) }

    fun finish() {
        val pin = state.value.pin
        if (pin.length < MIN_PIN || state.value.busy) return
        state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            val failure = try {
                when (val r = vault.finishMove(pin)) {
                    UnlockAttempt.Success -> null
                    is UnlockAttempt.Failed -> r.kind
                    is UnlockAttempt.BadPin -> FailureKind.BAD_PIN
                    else -> FailureKind.OTHER
                }
            } catch (e: VaultFailure) {
                e.kind
            }
            state.update { it.copy(busy = false, done = failure == null, failure = failure, pin = if (failure == null) "" else it.pin) }
        }
    }

    private companion object {
        const val MIN_PIN = 6
        const val MAX_PIN = 32
    }
}

@Composable
fun FinishMovePane(state: FinishMoveUiState, vm: FinishMoveViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Spacing.gutter).testTag("finish_move"), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.onboarding_finish_move_title), style = MaterialTheme.typography.titleLarge)
        if (state.done) {
            Text(stringResource(R.string.onboarding_finish_move_done), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onDone) { Text(stringResource(R.string.onboarding_open_continue)) }
            return@Column
        }
        Text(stringResource(R.string.onboarding_finish_move_body), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = state.pin,
            onValueChange = vm::setPin,
            label = { Text(stringResource(R.string.onboarding_open_pin)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("finish_move_pin"),
        )
        state.failure?.let {
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.onboarding_finish_move_title),
                body = stringResource(it.messageRes()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_finish_move_later)) }
            Button(onClick = vm::finish, enabled = state.pin.length >= 6 && !state.busy, modifier = Modifier.testTag("finish_move_go")) {
                Text(stringResource(R.string.onboarding_finish_move_title))
            }
        }
    }
}
