package com.vettid.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.MoveRepository
import com.vettid.core.data.vault.TransferInWaiting
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

    /**
     * No approval by the 11-minute deadline (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5): the collects for the
     * settlement window at
     * the deadline brought no `device.paired`, so the handshake was dropped. A new code is offered.
     */
    TIMED_OUT,

    /**
     * (A6 audit N1) The steps after the approval (the credential's take-over) failed: kept, and tried again (also at the
     * next start). "Finishing the move" with Try again.
     */
    FINISHING_FAILED,

    /**
     * No `hs.resp` within 60 seconds (§6.7.1 step 2, 0.10.6): the code may have been used already, or this phone
     * could not be verified (a dropped `hs.init` is never answered). A new code is offered.
     */
    NOT_ANSWERED,

    /**
     * (VAULT-MESSAGING 0.85.2 §11.14.10 rule 5; the recheck of #302) The session ended before the settlement could say
     * whether the old phone approved (a dead mailbox, a wipe, a sign-out): neither dropped nor finishing. The member
     * scans a new code, or recovers the vault if the move was approved.
     */
    SESSION_ENDED,
}

/** Immutable UI state of the transfer on the new phone. */
data class TransferInUiState(
    val step: TransferInStep = TransferInStep.INTRO,
    /** (Release R) This phone made a phone key for the transfer: "Finish the move" follows the approval. */
    val phoneSecret: Boolean = false,
    val input: String = "",
    /** Why the scanned or pasted text is not used: [FailureKind.INVITE_INVALID] or [FailureKind.INVITE_EXPIRED]. */
    val inputProblem: FailureKind? = null,
    val sas: String? = null,
    /** 10 minutes after `hs.init` (§6.7, 0.10.4). */
    val deadline: Instant? = null,
    val secondsLeft: Long = 0,
    /**
     * (VAULT-MESSAGING 0.85.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.121 (vettid.org #537))
     * The countdown reached 0: the old phone can no longer
     * approve, and the repository is checking one last time (the settlement window, about 90 s). The screen hides the
     * code and says so, and offers no cancel or new code: the repository refuses both until the window ends.
     */
    val settling: Boolean = false,
    val busy: Boolean = false,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorShown: String? = null,
    val errorCode: String? = null,
    /** (A6 audit N1) The member asked to cancel a transfer whose code was shown: confirmed first. */
    val confirmCancel: Boolean = false,
    /**
     * (ANDROID-PLAN 0.1.142) A different code scanned or pasted while a transfer to this phone is live, before minute 10: it
     * waits behind the same confirmation, and is used only once the waiting transfer is abandoned and that is saved.
     */
    val pendingCode: String? = null,
    /**
     * (ANDROID-PLAN 0.1.141) The screen is still reading back a transfer that may wait (after a restart): Back keeps the
     * move screen meanwhile.
     */
    val reading: Boolean = true,
    /** The confirmed cancel's abandon is on its way: the screen stays and offers no other cancel meanwhile. */
    val abandoning: Boolean = false,
) {
    override fun toString(): String = "TransferInUiState(step=$step, busy=$busy, error=$error)"
}

/** What the transfer screens on the new phone can ask for. */
@Suppress("TooManyFunctions") // one action per button of the transfer's steps
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

    /** (A6 audit N1) Tries the steps after the approval again ([TransferInStep.FINISHING_FAILED]). */
    fun retryFinish() {}

    /** (N1) The confirmed cancel of a transfer whose code was shown. */
    fun confirmCancel() {}

    /** (N1) The cancel was not confirmed: the transfer goes on. */
    fun dismissCancel() {}
}

/**
 * The new phone's side of a direct transfer (VAULT-MESSAGING §6.7.1), as
 * vettid-vault's `vaultctl pair`: scan the old phone's QR (or paste its link),
 * send the attested `hs.init`, show the SAS once the handshake checked out,
 * (or, without the vault's `hs.resp` within 60 seconds, say that the code may have
 * been used or this phone could not be verified, and offer a new code, §6.7.1 step 2)
 * and wait for the old phone's approval, which completes the transfer
 * (`device.paired{transfer: true}`), or its rejection (`device.pair.rejected`).
 * Then the credential the vault kept is fetched and confirmed.
 */
@Suppress("TooManyFunctions") // one action per button of the transfer's steps
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

    /** Closes a confirmation of [TransferInUiState.pendingCode] at minute 10, when no abandon is offered any more. */
    private var pendingCloser: Job? = null

    init {
        // (A6 audit N1) A transfer that waits for the old phone (a restart, or the screen left): its code again, waiting.
        viewModelScope.launch {
            val w = try {
                move.transferInWaiting()
            } catch (_: VaultFailure) {
                null
            }
            state.update { it.copy(reading = false) }
            if (w == null) return@launch toldStoppedMove()
            // (ANDROID-PLAN 0.1.141) Also when the member went on to scan or paste meanwhile, unless a code of theirs is being
            // checked or waits behind the confirmation (that path shows the waiting transfer itself).
            val s = state.value
            val first = s.step == TransferInStep.INTRO || s.step == TransferInStep.SCAN || s.step == TransferInStep.PASTE
            val theirs = s.busy || s.confirmCancel
            if (!first || theirs || waiter?.isActive == true) return@launch
            resumeWaiting(w)
        }
    }

    /** The waiting transfer [w] on screen again: its code, the countdown from its stored start, and the wait for its approval. */
    private fun resumeWaiting(w: TransferInWaiting) {
        val deadline = w.startedAt.plus(WINDOW)
        state.update { it.copy(step = TransferInStep.COMPARE, busy = false, sas = w.sas, deadline = deadline) }
        waiter?.cancel()
        waiter = viewModelScope.launch { waitForApproval(deadline) { move.awaitTransferIn() } }
    }

    /**
     * (VAULT-MESSAGING §6.7.1) No transfer waits: if the last one stopped for its `user_guid` before a restart, its
     * existing "The move stopped on this phone", once (the repository clears it as it is read).
     */
    private suspend fun toldStoppedMove() {
        if (state.value.step != TransferInStep.INTRO || waiter?.isActive == true) return
        val stopped = try {
            move.takeTransferStopped()
        } catch (_: VaultFailure) {
            false
        }
        if (stopped) state.update { it.copy(step = TransferInStep.SESSION_ENDED, sas = null, secondsLeft = 0, settling = false) }
    }

    override fun scan() = state.update { it.copy(step = TransferInStep.SCAN, inputProblem = null, error = null) }

    override fun paste() = state.update { it.copy(step = TransferInStep.PASTE, inputProblem = null, error = null) }

    /** No cancel, Back or new code while the settlement window runs. */
    private fun settlingNow() = state.value.step == TransferInStep.COMPARE && state.value.settling

    override fun back(): Boolean {
        val s = state.value
        // During the window Back is consumed: nothing is abandoned, and leaving would only route straight back here
        // (the phase is still TRANSFER_WAITING). The settlement goes on in the manager's scope.
        // (ANDROID-PLAN 0.1.141) Also while the screen still reads back a transfer that may wait, or an abandon is on its
        // way: the move screen stays.
        val held = s.reading || s.abandoning || s.busy && s.step == TransferInStep.COMPARE
        if (settlingNow() || held) return true
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
        // A repeated camera frame while the code is being checked, or while it waits behind "Cancel the move?".
        if (state.value.step != TransferInStep.SCAN || state.value.busy || state.value.confirmCancel) return
        start(text)
    }

    override fun setInput(v: String) = state.update { it.copy(input = v, inputProblem = null) }

    override fun submitInput() = start(state.value.input)

    /** Checks the code locally first: nothing is fetched for a code of another kind or an expired one. */
    private fun start(text: String) {
        if (settlingNow()) return
        val problem = when (InviteLinks.parseTransfer(text, Instant.now(clock))) {
            is InviteLinks.TransferParsed.Ok -> null
            InviteLinks.TransferParsed.Expired -> FailureKind.INVITE_EXPIRED
            else -> FailureKind.INVITE_INVALID
        }
        when (problem) {
            // (VAULT-MESSAGING 0.88.4 §11.14.10 rule 5) The open transfer's own code outlives its 10 minutes: asked first,
            // so a re-scan of it goes back to that move, never "expired" (and then a new code that would drop it).
            FailureKind.INVITE_EXPIRED -> {
                // Busy while asked: a repeated camera frame or a quick second scan does not start another (review of #334).
                if (state.value.busy) return
                state.update { it.copy(busy = true) }
                viewModelScope.launch {
                    val own = try {
                        move.isOpenTransferCode(text)
                    } catch (_: VaultFailure) {
                        false
                    }
                    state.update { it.copy(busy = false) }
                    val step = state.value.step
                    when {
                        !own -> state.update { it.copy(inputProblem = problem) }
                        step == TransferInStep.SCAN || step == TransferInStep.PASTE -> begin(text)
                        else -> Unit // left meanwhile: nothing to go back to
                    }
                }
            }
            null -> beginUnlessAnotherWaits(text)
            else -> state.update { it.copy(inputProblem = problem) }
        }
    }

    /**
     * (ANDROID-PLAN 0.1.142) A valid code while a transfer to this phone is live: the same one goes back to its move (as
     * [begin] does: the repository recognizes it). A different one, before minute 10, first opens "Cancel the move?" and
     * starts only after the waiting transfer is abandoned; from minute 10 it is refused, as any new code is then, and
     * nothing is dropped. A transfer this screen cannot read back is left to the repository, which refuses a new code
     * while one waits.
     */
    private fun beginUnlessAnotherWaits(text: String) {
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val waiting = try {
                move.transferInWaiting()
            } catch (_: VaultFailure) {
                null
            }
            val own = waiting != null && try {
                move.isOpenTransferCode(text)
            } catch (_: VaultFailure) {
                false
            }
            state.update { it.copy(busy = false) }
            val step = state.value.step
            if (step != TransferInStep.SCAN && step != TransferInStep.PASTE) return@launch // left meanwhile
            val tenMinutes = waiting?.startedAt?.plus(WINDOW)
            when {
                tenMinutes == null || own -> begin(text)
                Instant.now(clock).isBefore(tenMinutes) -> askBeforeAnotherCode(text, tenMinutes)
                else -> state.update {
                    it.copy(error = FailureKind.BUSY, errorCode = MoveRepository.CODE_TRANSFER_SETTLING, errorShown = null)
                }
            }
        }
    }

    /** "Cancel the move?" for [text], closed by itself at [tenMinutes] (no abandon from then on). */
    private fun askBeforeAnotherCode(text: String, tenMinutes: Instant) {
        state.update { it.copy(confirmCancel = true, pendingCode = text, error = null) }
        pendingCloser?.cancel()
        pendingCloser = viewModelScope.launch {
            delay(Duration.between(Instant.now(clock), tenMinutes).toMillis().coerceAtLeast(0))
            if (state.value.pendingCode != null) backToTheWaitingTransfer()
        }
    }

    private fun begin(text: String) {
        val from = state.value.step
        state.update { it.copy(step = TransferInStep.CONNECTING, busy = true, inputProblem = null, error = null, sas = null) }
        waiter?.cancel()
        waiter = viewModelScope.launch {
            val deadline = Instant.now(clock).plus(WINDOW)
            // (The review of c58c8fc3, M1) No timer of the screen's own: the repository's 60 s for the hs.resp (§6.7.1
            // step 2) start only once the hs.init is sent, after a claim fetch and a deposit that are each bounded but may
            // take longer than any margin here, so a screen timer could abandon a transfer still waited for. The repository
            // decides "not answered" (CODE_HS_UNANSWERED, [connectFailed]); Cancel leaves meanwhile.
            val sas = try {
                move.transferIn(text)
            } catch (e: VaultFailure) {
                connectFailed(from, e)
                return@launch
            }
            // The countdown from the stored start (what the repository judges by), not from before the claim.
            val stored = try {
                move.transferInWaiting()?.startedAt?.plus(WINDOW)
            } catch (_: VaultFailure) {
                null
            }
            val shown = stored ?: deadline
            state.update { it.copy(step = TransferInStep.COMPARE, busy = false, sas = sas, deadline = shown) }
            waitForApproval(shown) { move.awaitTransferIn() }
        }
    }

    /**
     * Waits for the old phone's approval; [deadline] is the countdown shown (the vault's 10 minutes). The wait itself runs
     * to the one 11-minute deadline (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5), where the repository
     * collects for about 90 s (the relay's visibility timeout + 25 s + 5 s) and then completes or drops; nothing here extends it.
     */
    private suspend fun waitForApproval(deadline: Instant, wait: suspend () -> Unit) {
        startTicker(deadline)
        try {
            wait()
            ticker?.cancel()
            val phoneSecret = vault.usesPhoneSecret
            state.update { it.copy(step = TransferInStep.DONE, sas = null, phoneSecret = phoneSecret) }
        } catch (e: VaultFailure) {
            ticker?.cancel()
            state.update {
                when (e.kind) {
                    FailureKind.REJECTED -> it.copy(step = TransferInStep.REJECTED, sas = null)
                    FailureKind.NO_RESPONSE -> it.copy(step = TransferInStep.TIMED_OUT, sas = null, secondsLeft = 0)
                    FailureKind.RETRY ->
                        if (e.code == MoveRepository.CODE_TRANSFER_SESSION_ENDED) {
                            it.copy(step = TransferInStep.SESSION_ENDED, sas = null, secondsLeft = 0, settling = false)
                        } else {
                            it.copy(step = TransferInStep.FINISHING_FAILED, error = e.kind, errorCode = e.code, errorShown = e.shownCode)
                        }
                    // The steps after the approval (the take-over): kept, and resumed (here, or at the next start).
                    else -> it.copy(step = TransferInStep.FINISHING_FAILED, error = e.kind, errorCode = e.code, errorShown = e.shownCode)
                }
            }
        }
    }

    override fun retryFinish() {
        val s = state.value
        if (s.step != TransferInStep.FINISHING_FAILED || waiter?.isActive == true) return
        state.update { it.copy(busy = true, error = null, errorCode = null, errorShown = null) }
        waiter = viewModelScope.launch {
            waitForApproval(s.deadline ?: Instant.now(clock)) { move.awaitTransferIn() }
            state.update { it.copy(busy = false) }
        }
    }

    /** The transfer could not start: a bad code, no `hs.resp` within 60 s (§6.7.1 step 2), or another failure. */
    private fun connectFailed(from: TransferInStep, e: VaultFailure) {
        val bad = e.kind == FailureKind.INVITE_INVALID || e.kind == FailureKind.INVITE_EXPIRED
        state.update {
            when {
                bad -> it.copy(step = from, busy = false, inputProblem = e.kind)
                e.code == MoveRepository.CODE_HS_UNANSWERED -> it.copy(step = TransferInStep.NOT_ANSWERED, busy = false)
                else -> it.copy(step = from, busy = false, error = e.kind, errorCode = e.code, errorShown = e.shownCode)
            }
        }
    }

    private fun startTicker(deadline: Instant) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = Duration.between(Instant.now(clock), deadline).seconds.coerceAtLeast(0)
                state.update {
                    it.copy(
                        secondsLeft = left,
                        settling = left == 0L,
                        confirmCancel = it.confirmCancel && left != 0L,
                        pendingCode = it.pendingCode.takeIf { left != 0L },
                    )
                }
                if (left == 0L) break
                delay(TICK_MS)
            }
        }
    }

    /**
     * Leaves a transfer in progress. Before the code was shown nothing can have been approved: dropped at once. (A6 audit
     * N1) Once it was shown, the old phone may have approved it, so the member confirms first ([confirmCancel]).
     */
    override fun cancel() {
        if (settlingNow() || state.value.abandoning) return
        val step = state.value.step
        if (step == TransferInStep.COMPARE) {
            state.update { it.copy(confirmCancel = true) }
            return
        }
        drop()
    }

    override fun confirmCancel() {
        val s = state.value
        if (!s.confirmCancel || settlingNow()) return
        val next = s.pendingCode
        pendingCloser?.cancel()
        state.update { it.copy(confirmCancel = false, pendingCode = null) }
        if (next == null && s.step != TransferInStep.COMPARE) return drop()
        abandonThen(next)
    }

    /**
     * Keep waiting: the transfer goes on. A different code that waited behind the confirmation is not used, and the screen
     * goes back to the waiting transfer as it stands.
     */
    override fun dismissCancel() {
        pendingCloser?.cancel()
        val waited = state.value.pendingCode != null
        state.update { it.copy(confirmCancel = false, pendingCode = null) }
        if (waited) viewModelScope.launch { backToTheWaitingTransfer() }
    }

    /**
     * (ANDROID-PLAN 0.1.141, 0.1.142) The confirmed cancel of a transfer whose code was shown: the screen leaves it only once
     * the abandon is done and saved, then shows the first step, or starts [next] (the different code that waited). An
     * abandon refused because minute 10 was reached meanwhile, or that failed, starts nothing and drops nothing: back to
     * the waiting or checking screen while the transfer is still live.
     */
    @Suppress("TooGenericExceptionCaught") // a drop whose save failed throws a state exception, not a VaultFailure
    private fun abandonThen(next: String?) {
        waiter?.cancel()
        ticker?.cancel()
        state.update { it.copy(busy = true, abandoning = true) }
        viewModelScope.launch {
            val done = try {
                move.abandonTransferIn()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // An approval that landed meanwhile is not dropped (the repository keeps a paired transfer): still there, so
            // the move goes on to its end rather than reading as cancelled.
            val left = if (done) {
                try {
                    move.transferInWaiting()
                } catch (_: VaultFailure) {
                    null
                }
            } else {
                null
            }
            state.update { it.copy(abandoning = false, confirmCancel = false) }
            when {
                !done || left != null -> backToTheWaitingTransfer()
                next != null -> {
                    state.update { it.copy(step = TransferInStep.SCAN, busy = false, sas = null, deadline = null) }
                    begin(next)
                }
                else -> state.update { it.copy(step = TransferInStep.INTRO, busy = false, sas = null, deadline = null) }
            }
        }
    }

    /** The waiting transfer again (its code and countdown, or the checking text from minute 10); the first step if none. */
    private suspend fun backToTheWaitingTransfer() {
        val w = try {
            move.transferInWaiting()
        } catch (_: VaultFailure) {
            null
        }
        state.update { it.copy(busy = false, confirmCancel = false, pendingCode = null) }
        if (w != null) resumeWaiting(w) else state.update { it.copy(step = TransferInStep.INTRO, sas = null, deadline = null) }
    }

    private fun drop() {
        waiter?.cancel()
        ticker?.cancel()
        state.update { it.copy(step = TransferInStep.INTRO, busy = false, sas = null, deadline = null, confirmCancel = false) }
        viewModelScope.launch { abandon() }
    }

    private suspend fun abandon() {
        try {
            move.abandonTransferIn()
        } catch (_: VaultFailure) {
            // nothing to drop
        }
    }

    override fun again() {
        if (settlingNow()) return
        state.update { TransferInUiState(step = TransferInStep.SCAN) }
    }

    override fun finish() {
        viewModelScope.launch { vault.finishSetup() }
    }

    private companion object {
        val WINDOW: Duration = com.vettid.core.data.vault.TransferInWait.VAULT_WINDOW
        const val TICK_MS = 1000L
    }
}
