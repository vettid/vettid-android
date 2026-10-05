package com.vettid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.TransferOfferView
import com.vettid.core.data.vault.TransferPendingView
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** The steps of a direct transfer on the old phone (VAULT-MESSAGING §6.7.1). */
enum class TransferOutStep {
    INTRO,

    /** The QR (pairing kind `p`, TTL 10 minutes); waiting for `device.transfer.pending`. */
    SHOWING,

    /** The new phone's handshake checked out: compare the SAS. */
    COMPARE,

    /** The codes match: the PIN and the password approve, which completes the transfer. */
    APPROVE,
    EXPIRED,
    REJECTED,
}

/** Immutable UI state of the transfer on the old phone. The PIN and password live here only until the approval. */
data class TransferOutUiState(
    val step: TransferOutStep = TransferOutStep.INTRO,
    val offer: TransferOfferView? = null,
    val pending: TransferPendingView? = null,
    /** Until when the current step can go on: the code's expiry, then 10 minutes after the new phone's handshake. */
    val deadline: Instant? = null,
    val secondsLeft: Long = 0,
    val pin: String = "",
    val password: String = "",
    val waitSeconds: Long = 0,
    val confirmReject: Boolean = false,
    val confirmApprove: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
) {
    val canApprove: Boolean get() = pin.length >= MIN_PIN && password.isNotEmpty() && waitSeconds == 0L && !busy

    override fun toString(): String = "TransferOutUiState(step=$step, busy=$busy, error=$error)"

    companion object {
        const val MIN_PIN = 6
    }
}

/** What the transfer screens on the old phone can ask for. */
interface TransferOutActions {
    fun create()
    fun codesMatch()
    fun askReject(show: Boolean)
    fun reject()
    fun setPin(v: String)
    fun setPassword(v: String)
    fun approve()
    fun askApprove(show: Boolean)
    fun leave()
}

/**
 * The old phone's side of a direct transfer (VAULT-MESSAGING §6.7.1), as
 * vettid-vault's `vaultctl transfer`: `device.transfer.create` gives the QR the
 * new phone scans; `device.transfer.pending` brings the SAS once the new
 * phone's handshake checked out; the member compares it and approves with the
 * PIN and the credential password (`device.transfer.approve`), which
 * completes the transfer: this phone is then removed from the vault, and the
 * app shows "This phone no longer holds your vault". A mismatch rejects it.
 */
@HiltViewModel
class TransferOutViewModel @Inject constructor(private val move: MoveRepository) : ViewModel(), TransferOutActions {
    private val state = MutableStateFlow(TransferOutUiState())
    val uiState: StateFlow<TransferOutUiState> = state.asStateFlow()
    private var waiter: Job? = null
    private var ticker: Job? = null
    private var backoff: Job? = null

    /** Tests set a fixed clock. */
    internal var clock: Clock = Clock.systemUTC()

    override fun create() {
        if (state.value.busy) return
        state.update { TransferOutUiState(busy = true) }
        waiter?.cancel()
        waiter = viewModelScope.launch {
            val offer = try {
                move.transferCreate()
            } catch (e: VaultFailure) {
                state.update { it.copy(step = TransferOutStep.INTRO, busy = false, error = e.kind, errorCode = e.code) }
                return@launch
            }
            state.update { it.copy(step = TransferOutStep.SHOWING, busy = false, offer = offer, deadline = offer.expiresAt) }
            startTicker(offer.expiresAt)
            val pending = try {
                move.awaitTransferPending(offer.transferId, offer.expiresAt)
            } catch (e: VaultFailure) {
                state.update { it.copy(error = e.kind, errorCode = e.code) }
                null
            }
            if (pending == null) {
                expire()
                return@launch
            }
            // The approval window: 10 minutes after the new phone's hs.init (§6.7, 0.10.4), counted from here.
            val window = Instant.now(clock).plus(WINDOW)
            state.update { it.copy(step = TransferOutStep.COMPARE, pending = pending, deadline = window, error = null) }
            startTicker(window)
        }
    }

    private fun startTicker(deadline: Instant) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = Duration.between(Instant.now(clock), deadline).seconds.coerceAtLeast(0)
                state.update { it.copy(secondsLeft = left) }
                if (left == 0L) {
                    val s = state.value.step
                    if (s == TransferOutStep.SHOWING || s == TransferOutStep.COMPARE || s == TransferOutStep.APPROVE) expire()
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    private fun expire() {
        waiter?.cancel()
        state.update {
            it.copy(
                step = TransferOutStep.EXPIRED, offer = null, pending = null, pin = "", password = "", busy = false, confirmApprove = false,
            )
        }
    }

    override fun codesMatch() = state.update { it.copy(step = TransferOutStep.APPROVE, error = null) }

    override fun askReject(show: Boolean) = state.update { it.copy(confirmReject = show) }

    override fun reject() {
        val id = state.value.offer?.transferId ?: return
        waiter?.cancel()
        ticker?.cancel()
        state.update { it.copy(confirmReject = false, busy = true) }
        viewModelScope.launch {
            try {
                move.transferReject(id)
                state.update { TransferOutUiState(step = TransferOutStep.REJECTED) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, errorCode = e.code) }
            }
        }
    }

    override fun setPin(v: String) = state.update { it.copy(pin = v.filter { c -> c.isDigit() }.take(MAX_PIN), error = null) }

    override fun setPassword(v: String) = state.update { it.copy(password = v, error = null) }

    override fun askApprove(show: Boolean) = state.update { it.copy(confirmApprove = show && it.canApprove) }

    /** Critical (§6.7.1): the PIN and the password, after the confirmation. Success leaves this screen (the phase changes). */
    override fun approve() {
        val s = state.value
        val id = s.offer?.transferId ?: return
        if (!s.canApprove) return
        state.update { it.copy(busy = true, confirmApprove = false, error = null) }
        viewModelScope.launch {
            try {
                move.transferApprove(id, s.pin, s.password)
                ticker?.cancel()
                state.update { it.copy(busy = false, pin = "", password = "") }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, pin = "", password = "", error = e.kind, errorCode = e.code) }
                if (e.retryAfterSeconds > 0) startBackoff(e.retryAfterSeconds)
            }
        }
    }

    private fun startBackoff(seconds: Long) {
        backoff?.cancel()
        state.update { it.copy(waitSeconds = seconds) }
        backoff = viewModelScope.launch {
            while (state.value.waitSeconds > 0) {
                delay(TICK_MS)
                state.update { it.copy(waitSeconds = (it.waitSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Leaving before the approval cancels the transfer, so the code cannot be used later. */
    override fun leave() {
        val s = state.value
        val id = s.offer?.transferId
        waiter?.cancel()
        ticker?.cancel()
        if (id != null && s.step in setOf(TransferOutStep.SHOWING, TransferOutStep.COMPARE, TransferOutStep.APPROVE)) {
            // Not cancelled with this ViewModel: leaving the screen clears it.
            viewModelScope.launch(NonCancellable) {
                try {
                    move.transferReject(id)
                } catch (_: VaultFailure) {
                    // it expires within 10 minutes anyway
                }
            }
        }
        state.update { TransferOutUiState() }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(10)
        const val TICK_MS = 1000L
        const val MAX_PIN = 32
    }
}
