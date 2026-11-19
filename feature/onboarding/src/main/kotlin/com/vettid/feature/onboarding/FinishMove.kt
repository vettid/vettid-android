package com.vettid.feature.onboarding

import androidx.activity.compose.BackHandler
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
import com.vettid.core.ui.format.failureText
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
    /**
     * The unlock found no usable user_guid on this phone (`no_user_guid`): it can never open the vault, so the
     * recover-or-erase text of the unlock screen, not "does not support this yet".
     */
    val noUserGuid: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.45.0 §11.14.9 step 2) A PIN or password change is being decided, or the PIN is uncertain: no
     * lock and unlock with a PIN typed here until it is (the gate's text); null when the move can be finished.
     */
    val held: FailureKind? = null,
) {
    /**
     * The PIN field can be used: not held, not running, not done, and not after `no_user_guid` (no PIN can open the vault
     * on this phone, so there is no PIN to type and no "Finish the move" to try again; "Later" stays).
     */
    val pinAllowed: Boolean get() = held == null && !busy && !done && !noUserGuid

    override fun toString(): String =
        "FinishMoveUiState(busy=$busy, done=$done, failure=$failure, noUserGuid=$noUserGuid, held=$held)"
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

    /**
     * (VAULT-MESSAGING §11.14.9 step 2; chosen, not in the spec) Which PIN the vault expects, read when the PIN field
     * becomes usable and handed over with the PIN: a change sent or decided while the member types it stops the move
     * with nothing sent, and the PIN is asked for again.
     */
    private val keypad = KeypadReading { vault.pinGeneration }

    /** Every change of the state, with the keypad's reading following whether the PIN field can be used. */
    private fun set(f: (FinishMoveUiState) -> FinishMoveUiState) {
        // Digits typed while held were typed before the hold's decision: they go with it.
        val wasEmpty = state.value.pin.isEmpty()
        state.update { s -> f(s).let { n -> if (!n.pinAllowed && !n.busy && n.pin.isNotEmpty()) n.copy(pin = "") else n } }
        keypad.follow(state.value.pinAllowed, wasEmpty)
    }

    init {
        opened()
        keypad.follow(state.value.pinAllowed)
        viewModelScope.launch { vault.changeHold.collect { h -> set { it.copy(held = h) } } }
        // A new move to finish (none pending before: the last one finished, or a wipe ended it) starts from the PIN form, not
        // from what the last one left (done, a failure). The open app's dialog keeps this view model across moves. An edge that
        // comes while a move is running moves `before` on without a reset, so that run's failure would stay for the new move:
        // not reachable (a transfer would have to complete into this phone while it finishes one).
        viewModelScope.launch {
            var before = vault.finishMovePending.value
            vault.finishMovePending.collect { pending ->
                if (pending && !before) freshMove()
                before = pending
            }
        }
    }

    /**
     * The PIN form for a move not tried yet. The user_guid's reading stays ([opened] reads it again), with its failure. A
     * running move is left alone (defensive: its state is the fresh one already).
     */
    private fun freshMove() = set {
        if (it.busy) it else it.copy(pin = "", done = false, failure = if (it.noUserGuid) it.failure else null)
    }

    /**
     * The move's screen or dialog opens: whether this phone has a usable user_guid, read now (owner decision a, 2026-11-19, as
     * the unlock screen). This view model can outlive a move (the open app's dialog is scoped to the activity), and a move
     * stores the user_guid when it completes, so it is read at each opening, never once: a phone that has one is not told it
     * cannot open its vault. The move's own `no_user_guid` refusal is this same check, so nothing else is kept: after a wipe
     * and a move that stored one, the next opening offers the PIN again.
     */
    fun opened() {
        // A move still pending was not finished here: "done" is a finished move's, so this one starts afresh (the flag turns
        // false before a finished move's answer, so a reopening after one keeps its "done": in practice the move screens'
        // pane composed again, since the open app's dialog hides with the flag). A backstop for "done" only: the
        // new move's own transition ([init]) is what clears an earlier move's failure.
        if (state.value.done && vault.finishMovePending.value) freshMove()
        val noGuid = !vault.userGuidUsable
        set {
            when {
                noGuid -> it.copy(noUserGuid = true, failure = FailureKind.NOT_SUPPORTED)
                it.noUserGuid -> it.copy(noUserGuid = false, failure = null)
                else -> it
            }
        }
    }

    // Typing does not clear `noUserGuid`: no PIN can change it (the field is disabled then).
    fun setPin(v: String) = set {
        if (it.noUserGuid) it else it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), failure = null)
    }

    fun finish() {
        val s = state.value
        val pin = s.pin
        if (pin.length < MIN_PIN || !s.pinAllowed) return
        val taken = keypad.take(s.pinAllowed)
        // Handed over: the keypad empties, so that a PIN refused as typed before a change is typed again.
        set { it.copy(busy = true, failure = null, pin = "") }
        viewModelScope.launch {
            var noGuid = false
            val failure = try {
                when (val r = vault.finishMove(pin, taken)) {
                    UnlockAttempt.Success -> null
                    is UnlockAttempt.Failed -> r.kind.also { noGuid = isNoUserGuid(r) }
                    is UnlockAttempt.BadPin -> FailureKind.BAD_PIN
                    else -> FailureKind.OTHER
                }
            } catch (e: VaultFailure) {
                e.kind
            }
            set { it.copy(busy = false, done = failure == null, failure = failure, noUserGuid = noGuid) }
        }
    }

    private companion object {
        const val MIN_PIN = 6
        const val MAX_PIN = 32
    }
}

@Composable
fun FinishMovePane(state: FinishMoveUiState, vm: FinishMoveViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    // Nothing leaves the move while its lock and unlock run (the back key included): the outcome decides where it goes.
    BackHandler(enabled = state.busy) {}
    // Whether this phone has a usable user_guid, as it is now (the view model may outlive a move that stored one).
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.opened() }
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
            enabled = state.pinAllowed,
            modifier = Modifier.fillMaxWidth().excludeFromAutofill().testTag("finish_move_pin"),
        )
        state.held?.let {
            NoticeCard(
                kind = NoticeKind.WARNING,
                title = stringResource(R.string.onboarding_finish_move_title),
                body = failureText(it.messageRes()),
                modifier = Modifier.testTag("finish_move_held"),
            )
        }
        state.failure?.let {
            if (state.noUserGuid) {
                NoticeCard(
                    kind = NoticeKind.URGENT,
                    title = stringResource(com.vettid.core.ui.R.string.unlock_stuck_title),
                    body = stringResource(com.vettid.core.ui.R.string.unlock_stuck_body),
                    modifier = Modifier.testTag("finish_move_no_user_guid"),
                )
            } else {
                NoticeCard(
                    kind = NoticeKind.WARNING,
                    title = stringResource(R.string.onboarding_finish_move_title),
                    body = failureText(it.messageRes()),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            TextButton(onClick = onDone, enabled = !state.busy, modifier = Modifier.testTag("finish_move_later")) {
                Text(stringResource(R.string.onboarding_finish_move_later))
            }
            Button(
                onClick = vm::finish,
                enabled = state.pin.length >= 6 && state.pinAllowed,
                modifier = Modifier.testTag("finish_move_go"),
            ) {
                Text(stringResource(R.string.onboarding_finish_move_title))
            }
        }
    }
}
