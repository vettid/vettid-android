package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** The steps of a direct transfer on the new phone (VAULT-MESSAGING §6.7.1). */
enum class TransferInStep {
    INTRO,
    SCAN,
    PASTE,

    /** `hs.init` sent; waiting for the vault's `hs.resp`. */
    CONNECTING,

    /**
     * The SAS is shown; waiting for the old phone's approval (`device.paired`) until 10 minutes after
     * `hs.init`, then fetching the credential (`credential.get`, `credential.ack`, `credential.utk.get`).
     */
    COMPARE,

    DONE,

    /** `device.pair.rejected` (0.10.5): "Rejected on your phone". */
    REJECTED,

    /** 10 minutes without approval. */
    TIMED_OUT,
}

/** Immutable UI state of the transfer on the new phone. */
data class TransferInUiState(
    val step: TransferInStep = TransferInStep.INTRO,
    val input: String = "",
    /** Why the scanned or pasted text is not used: [FailureKind.INVITE_INVALID] or [FailureKind.INVITE_EXPIRED]. */
    val inputProblem: FailureKind? = null,
    val sas: String? = null,
    /** 10 minutes after `hs.init` (§6.7, 0.10.4). */
    val deadline: Instant? = null,
    val secondsLeft: Long = 0,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
) {
    override fun toString(): String = "TransferInUiState(step=$step, busy=$busy, error=$error)"
}

/** What the transfer screens on the new phone can ask for. */
interface TransferInActions {
    fun scan()
    fun paste()
    fun back(): Boolean
    fun scanned(text: String)
    fun setInput(v: String)
    fun submitInput()
    fun cancel()
    fun again()
    fun finish()
}

/**
 * The new phone's side of a direct transfer (VAULT-MESSAGING §6.7.1), as
 * vettid-vault's `vaultctl pair`: scan the old phone's QR (or paste its link),
 * send the attested `hs.init`, show the SAS once the handshake checked out,
 * and wait for the old phone's approval, which completes the transfer
 * (`device.paired{transfer: true}`), or its rejection (`device.pair.rejected`).
 * Then the credential the vault kept is fetched and confirmed.
 */
@HiltViewModel
class TransferInViewModel @Inject constructor(
    private val move: MoveRepository,
    private val vault: VaultRepository,
) : ViewModel(), TransferInActions {
    private val state = MutableStateFlow(TransferInUiState())
    val uiState: StateFlow<TransferInUiState> = state.asStateFlow()
    private var waiter: Job? = null
    private var ticker: Job? = null

    /** Tests set a fixed clock. */
    internal var clock: Clock = Clock.systemUTC()

    override fun scan() = state.update { it.copy(step = TransferInStep.SCAN, inputProblem = null, error = null) }

    override fun paste() = state.update { it.copy(step = TransferInStep.PASTE, inputProblem = null, error = null) }

    override fun back(): Boolean {
        val s = state.value
        return when (s.step) {
            TransferInStep.SCAN, TransferInStep.PASTE -> {
                state.update { it.copy(step = TransferInStep.INTRO, inputProblem = null) }
                true
            }
            TransferInStep.CONNECTING, TransferInStep.COMPARE -> {
                cancel()
                true
            }
            else -> false
        }
    }

    override fun scanned(text: String) {
        if (state.value.step != TransferInStep.SCAN || state.value.busy) return
        start(text)
    }

    override fun setInput(v: String) = state.update { it.copy(input = v, inputProblem = null) }

    override fun submitInput() = start(state.value.input)

    /** Checks the code locally first: nothing is fetched for a code of another kind or an expired one. */
    private fun start(text: String) {
        val problem = when (InviteLinks.parseTransfer(text, Instant.now(clock))) {
            is InviteLinks.TransferParsed.Ok -> null
            InviteLinks.TransferParsed.Expired -> FailureKind.INVITE_EXPIRED
            else -> FailureKind.INVITE_INVALID
        }
        if (problem != null) {
            state.update { it.copy(inputProblem = problem) }
            return
        }
        val from = state.value.step
        state.update { it.copy(step = TransferInStep.CONNECTING, busy = true, inputProblem = null, error = null, sas = null) }
        waiter?.cancel()
        waiter = viewModelScope.launch {
            val deadline = Instant.now(clock).plus(WINDOW)
            val sas = try {
                move.transferIn(text)
            } catch (e: VaultFailure) {
                val bad = e.kind == FailureKind.INVITE_INVALID || e.kind == FailureKind.INVITE_EXPIRED
                state.update {
                    if (bad) {
                        it.copy(step = from, busy = false, inputProblem = e.kind)
                    } else {
                        it.copy(step = from, busy = false, error = e.kind, errorCode = e.code)
                    }
                }
                return@launch
            }
            state.update { it.copy(step = TransferInStep.COMPARE, busy = false, sas = sas, deadline = deadline) }
            startTicker(deadline)
            try {
                move.awaitTransferIn()
                ticker?.cancel()
                state.update { it.copy(step = TransferInStep.DONE, sas = null) }
            } catch (e: VaultFailure) {
                ticker?.cancel()
                state.update {
                    when (e.kind) {
                        FailureKind.REJECTED -> it.copy(step = TransferInStep.REJECTED, sas = null)
                        FailureKind.NO_RESPONSE -> it.copy(step = TransferInStep.TIMED_OUT, sas = null)
                        else -> it.copy(step = TransferInStep.INTRO, sas = null, error = e.kind, errorCode = e.code)
                    }
                }
            }
        }
    }

    private fun startTicker(deadline: Instant) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = Duration.between(Instant.now(clock), deadline).seconds.coerceAtLeast(0)
                state.update { it.copy(secondsLeft = left) }
                if (left == 0L) break
                delay(TICK_MS)
            }
        }
    }

    /** Leaves a transfer in progress: nothing was approved, so nothing changes; the handshake state is dropped. */
    override fun cancel() {
        waiter?.cancel()
        ticker?.cancel()
        state.update { it.copy(step = TransferInStep.INTRO, busy = false, sas = null, deadline = null) }
        viewModelScope.launch {
            try {
                move.abandonTransferIn()
            } catch (_: VaultFailure) {
                // nothing to drop
            }
        }
    }

    override fun again() = state.update { TransferInUiState(step = TransferInStep.SCAN) }

    override fun finish() {
        viewModelScope.launch { vault.finishSetup() }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(10)
        const val TICK_MS = 1000L
    }
}
