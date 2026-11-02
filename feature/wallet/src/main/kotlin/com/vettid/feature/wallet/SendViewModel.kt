package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vettid.core.chain.ChainServer
import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.wallet.SendRoute
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.ChainServerSettings
import com.vettid.core.wallet.FeeEstimates
import com.vettid.core.wallet.PrepareResult
import com.vettid.core.wallet.SendHold
import com.vettid.core.wallet.SendRequest
import com.vettid.core.wallet.SendResult
import com.vettid.core.wallet.WalletAvailability
import com.vettid.core.wallet.WalletsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Send (ANDROID-PLAN §4 "Sending"). Prints its type name only: an address, an amount, a fee and the password are in it.
 * The step is [step]: the form (who, how much, fee), the review (the vault's summary, then the password), and the result.
 */
@Suppress("LongParameterList")
data class SendUiState(
    val loading: Boolean = true,
    val network: Network? = null,
    /** The whole screen is a notice: Receive's holds, and [com.vettid.core.wallet.SendHold]. */
    val block: SendBlock? = null,
    val networkSaveFailed: Boolean = false,
    val step: SendStep = SendStep.FORM,
    // The form.
    /** The payee as accepted: leading and trailing spaces removed, a `bitcoin:` URI reduced to its address. */
    val address: String = "",
    val addressIssue: AddressIssue? = null,
    val amountText: String = "",
    val unit: AmountUnit = AmountUnit.BTC,
    val sendAll: Boolean = false,
    val feeChoice: FeeChoice = FeeChoice.NORMAL,
    val customFee: String = "",
    /** The server's fee rates (sat/vB by target); null until they are read, or when they could not be. */
    val estimates: Map<Int, Double>? = null,
    val estimatesFailed: Boolean = false,
    val includeUnconfirmed: Boolean = false,
    val scanning: Boolean = false,
    val scanUnavailable: Boolean = false,
    /** "This fee is much higher than needed right now. Pay it anyway?" is asked. */
    val bigFeeAsk: Boolean = false,
    val problem: SendProblem? = null,
    // The review and the approval.
    val review: Review? = null,
    val password: String = "",
    /** The password was not accepted, or the vault refused before signing. */
    val error: FailureKind? = null,
    /** The unlock window closed even after the app opened it: the password is asked once more. */
    val askAgain: Boolean = false,
    val busy: Boolean = false,
    // The result.
    val outcome: SendOutcome? = null,
    val discardAsk: Boolean = false,
    val closed: Boolean = false,
) {
    val amount: BtcFormat.Amount
        get() = when {
            amountText.isEmpty() -> BtcFormat.Amount.None
            unit == AmountUnit.BTC -> BtcFormat.parse(amountText)
            else -> amountText.toLongOrNull()?.takeIf { it in 1..BtcFormat.MAX_SATS }?.let { BtcFormat.Amount.Valid(it) }
                ?: BtcFormat.Amount.Invalid
        }

    /** The amount to send in sats; null for Send all, or while it is not a valid amount ([amountValid]). */
    val amountSats: Long? get() = if (sendAll) null else (amount as? BtcFormat.Amount.Valid)?.sats

    val amountValid: Boolean get() = sendAll || amount is BtcFormat.Amount.Valid

    /** The fee rate chosen, in sat/vB; null while it is not known (no estimates yet) or a custom one is not valid. */
    val feeRate: Double? get() = SendFees.rate(feeChoice, estimates, customFee)

    val customFeeBad: Boolean get() = feeChoice == FeeChoice.CUSTOM && customFee.isNotEmpty() && SendFees.customRate(customFee) == null

    /** Review can be asked for: a payee that passes the check, an amount, a fee rate. */
    val canReview: Boolean
        get() = address.isNotEmpty() && addressIssue == null && amountValid && feeRate != null && !busy && block == null

    override fun toString(): String = "SendUiState"
}

/**
 * Send (ANDROID-PLAN §4 "Sending", W2): [load] applies Receive's holds (the phrase not backed up, a limit still to be set, settings that
 * cannot be read, the network, the chain server's note) and `SendHold`s (a signing that may still apply), then the fee rates. The
 * form is checked as it is typed ([SendInput]: **spaces at either end of the payee are trimmed**, a `bitcoin:` URI gives its address and
 * amount, the network is checked with the same rules as the build). [review] builds and inspects (nothing is signed) and shows the
 * **vault's** summary; [approve] types the password once, for `credential.unlock` when the window is shut and `wallet.sign`, and it is
 * wiped from the state as soon as it goes. Nothing is kept in saved state.
 */
// One function per action of the screen (they share its state).
@Suppress("TooManyFunctions", "LargeClass")
@HiltViewModel
class SendViewModel internal constructor(
    saved: SavedStateHandle,
    private val wallets: WalletsManager,
    private val settings: ChainServerSettings,
    private val engine: SendEngine,
) : ViewModel() {
    @Inject
    constructor(saved: SavedStateHandle, wallets: WalletsManager, settings: ChainServerSettings) :
        this(saved, wallets, settings, ManagerSendEngine(wallets))

    val walletId: String = saved.toRoute<SendRoute>().walletId
    private val mutable = MutableStateFlow(SendUiState())
    val state: StateFlow<SendUiState> = mutable.asStateFlow()

    private var started = false
    private var seen = false

    init {
        viewModelScope.launch {
            combine(wallets.wallets, wallets.availability, wallets.flagsChanged, wallets.capsPending) { list, availability, _, _ ->
                list.firstOrNull { it.id == walletId } to availability
            }.collect { (w, availability) ->
                if (w == null) {
                    if (seen || availability != WalletAvailability.UNKNOWN) clear()
                } else {
                    seen = true
                    val network = wallets.networkOf(w)
                    mutable.update { it.copy(network = network) }
                    if (!started) {
                        started = true
                        begin()
                    } else if (mutable.value.step == SendStep.FORM && mutable.value.block in GATES) {
                        // The phrase was checked, a limit set or the network chosen meanwhile.
                        gate()
                    }
                }
            }
        }
    }

    /** The wallet is gone (deleted, locked, held, wiped): nothing of it stays. */
    private fun clear() {
        engine.forget()
        mutable.update { SendUiState(loading = false, closed = true) }
    }

    override fun onCleared() {
        engine.forget()
        super.onCleared()
    }

    private suspend fun begin() {
        settle()
        gate()
        if (mutable.value.block == null && engine.hasUnsent(walletId)) {
            mutable.update { it.copy(step = SendStep.DONE, outcome = SendOutcome.Unsent) }
        }
        if (mutable.value.block == null) loadFees()
    }

    /** "Try again" on a notice: the holds are read again. */
    fun load() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            settle()
            gate()
            if (mutable.value.block == null && mutable.value.estimates == null) loadFees()
        }
    }

    /** The limit the member chose is sent first, as before Receive; a failed send leaves it pending and the notice says so. */
    private suspend fun settle() {
        if (!WalletsManager.isPending(wallets.capsPending.value, walletId) || WalletsManager.UNKNOWN in wallets.capsPending.value) return
        try {
            wallets.settleCaps(walletId)
        } catch (_: VaultFailure) {
            // It stays pending.
        }
    }

    /** Receive's holds first (they need no chain server), then the network. */
    private suspend fun gate() {
        wallets.rereadSettings()
        val pending = wallets.capsPending.value
        val network = mutable.value.network
        val block = when {
            WalletsManager.UNKNOWN in pending -> SendBlock.SETTINGS_UNREADABLE
            !wallets.backedUp(walletId) -> SendBlock.NOT_BACKED_UP
            WalletsManager.isPending(pending, walletId) ->
                if (walletId in wallets.capsDuplicate.value) SendBlock.CAPS_DUPLICATE else SendBlock.CAPS_PENDING
            network == null -> SendBlock.NEEDS_NETWORK
            !wallets.offers(network) -> SendBlock.NOT_OFFERED
            else -> null
        }
        mutable.update { it.copy(loading = false, block = block, problem = null) }
    }

    /** "Which test network is this wallet on?": kept on this phone, then the holds again. */
    fun chooseTestNetwork(network: Network) {
        if (mutable.value.block != SendBlock.NEEDS_NETWORK) return
        viewModelScope.launch {
            val kept = wallets.setTestNetwork(walletId, network)
            mutable.update { it.copy(networkSaveFailed = !kept) }
        }
    }

    private suspend fun loadFees() {
        when (val e = engine.feeEstimates(walletId)) {
            is FeeEstimates.Done -> mutable.update { it.copy(estimates = e.byTarget, estimatesFailed = false) }
            else -> mutable.update { it.copy(estimates = null, estimatesFailed = true) }
        }
    }

    /** "Try again" on the fee rates that could not be read. */
    fun retryFees() {
        viewModelScope.launch { loadFees() }
    }

    // --- Who and how much ---

    /** Typed or pasted: spaces at either end are removed; a `bitcoin:` URI gives its address and its amount. */
    fun setAddress(text: String) {
        val parsed = SendInput.parse(text)
        applyPayee(parsed)
    }

    /** A scanned QR code: an address, or a BIP21 URI with its address and amount (other parameters are ignored). */
    fun scanned(text: String) {
        mutable.update { it.copy(scanning = false) }
        applyPayee(SendInput.parse(text))
    }

    private fun applyPayee(p: SendInput.Parsed) {
        mutable.update { s ->
            val issue = s.network?.let { SendInput.issue(it, p.address) }
            val withAmount = p.amountSats?.let {
                s.copy(amountText = BtcFormat.btc(it), unit = AmountUnit.BTC, sendAll = false)
            } ?: s
            withAmount.copy(address = p.address, addressIssue = issue, problem = null)
        }
    }

    fun startScan() = mutable.update { it.copy(scanning = true, scanUnavailable = false) }

    fun stopScan() = mutable.update { it.copy(scanning = false) }

    fun scanUnavailable() = mutable.update { it.copy(scanUnavailable = true) }

    fun setAmount(text: String) = mutable.update {
        val filtered = if (it.unit == AmountUnit.BTC) BtcFormat.filter(text) else text.filter { c -> c in '0'..'9' }
        it.copy(amountText = filtered, problem = null)
    }

    /** BTC or sats: a valid amount is rewritten in the other unit. */
    fun setUnit(unit: AmountUnit) = mutable.update { s ->
        if (s.unit == unit) return@update s
        val text = (s.amount as? BtcFormat.Amount.Valid)?.let { if (unit == AmountUnit.BTC) BtcFormat.btc(it.sats) else it.sats.toString() }
        s.copy(unit = unit, amountText = text ?: "")
    }

    fun setSendAll(on: Boolean) = mutable.update { it.copy(sendAll = on, problem = null) }

    fun setFeeChoice(choice: FeeChoice) = mutable.update { it.copy(feeChoice = choice, problem = null) }

    fun setCustomFee(text: String) = mutable.update { it.copy(customFee = SendFees.filterCustom(text), problem = null) }

    fun setIncludeUnconfirmed(on: Boolean) = mutable.update { it.copy(includeUnconfirmed = on, problem = null) }

    // --- Review ---

    /** "Review": a fee above twice Fast is asked about first. */
    fun review() {
        val s = mutable.value
        if (!s.canReview) return
        val rate = s.feeRate ?: return
        if (SendFees.muchHigher(rate, s.estimates)) {
            mutable.update { it.copy(bigFeeAsk = true) }
        } else {
            prepare()
        }
    }

    fun confirmBigFee() {
        if (!mutable.value.bigFeeAsk) return
        mutable.update { it.copy(bigFeeAsk = false) }
        prepare()
    }

    fun cancelBigFee() = mutable.update { it.copy(bigFeeAsk = false) }

    private fun prepare() {
        val s = mutable.value
        val rate = s.feeRate ?: return
        if (!s.canReview) return
        mutable.update { it.copy(busy = true, problem = null) }
        val request = SendRequest(s.address, s.amountSats, rate, s.includeUnconfirmed)
        viewModelScope.launch {
            try {
                when (val p = engine.prepare(walletId, request)) {
                    is SendPrepared.Ready -> showReview(p.review)
                    is SendPrepared.Stopped -> stopped(p.result)
                }
            } catch (e: VaultFailure) {
                mutable.update { it.copy(busy = false, problem = SendProblem.Failed(e.kind)) }
            }
        }
    }

    private fun showReview(r: Review) {
        val w = wallets.wallets.value.firstOrNull { it.id == walletId }
        val period = w?.perPeriodSats
        val spent = w?.spentSats
        val limit = if (r.hasCap && period != null && spent != null) {
            LimitLine(r.totalOutSats, (period - spent - r.totalOutSats).coerceAtLeast(0))
        } else {
            null
        }
        val cap = r.capRefusal?.let { withLimit(it) }
        mutable.update {
            it.copy(busy = false, step = SendStep.REVIEW, review = Review(
                r.address, r.payeeSats, r.changeSats, r.feeSats, r.vsize, r.totalOutSats, r.toFeeSats, r.sendAll, r.leftBehind,
                r.hasCap, cap, limit,
            ), password = "", error = null, askAgain = false)
        }
    }

    /** A refusal the vault gave without the limit that stopped it: the wallet's own is named. */
    private fun withLimit(c: CapText): CapText {
        if (c.limitSats != null) return c
        val w = wallets.wallets.value.firstOrNull { it.id == walletId }
        return c.copy(limitSats = if (c.reason == CapReason.PER_TX) w?.perTxSats else w?.perPeriodSats)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun stopped(r: PrepareResult) {
        fun problem(p: SendProblem) = mutable.update { it.copy(busy = false, problem = p) }

        fun block(b: SendBlock) = mutable.update { it.copy(busy = false, block = b) }
        when (r) {
            is PrepareResult.Ready -> Unit
            is PrepareResult.Held -> block(
                when (r.hold) {
                    SendHold.CAPS_PENDING -> SendBlock.CAPS_PENDING
                    SendHold.CAPS_DUPLICATE -> SendBlock.CAPS_DUPLICATE
                    SendHold.SETTINGS_UNREADABLE -> SendBlock.SETTINGS_UNREADABLE
                    SendHold.SIGN_UNSETTLED -> SendBlock.SIGN_UNSETTLED
                },
            )
            PrepareResult.NeedsNetwork -> block(SendBlock.NEEDS_NETWORK)
            PrepareResult.NotOffered -> block(SendBlock.NOT_OFFERED)
            PrepareResult.NoteNeeded -> block(SendBlock.NOTE_NEEDED)
            PrepareResult.AddressMismatch -> block(SendBlock.MISMATCH)
            is PrepareResult.BadAddress -> mutable.update {
                it.copy(busy = false, addressIssue = it.network?.let { n -> SendInput.issue(n, it.address) } ?: AddressIssue.NotAnAddress)
            }
            PrepareResult.SelfSend -> problem(SendProblem.SelfSend)
            PrepareResult.BadFeeRate -> problem(SendProblem.BadFee)
            PrepareResult.BadAmount -> problem(SendProblem.BadAmount)
            PrepareResult.TooManyInputs -> problem(SendProblem.TooManyInputs)
            is PrepareResult.NotEnough -> problem(SendProblem.NotEnough(r.availableSats, r.neededSats))
            is PrepareResult.NoChain -> problem(SendProblem.NoChain(r.open))
            is PrepareResult.ServerFailed -> problem(SendProblem.ServerFailed)
            is PrepareResult.Refused -> problem(SendProblem.Refused(r.reason, r.alarm, r.byVault))
            is PrepareResult.Different -> problem(SendProblem.Different)
            PrepareResult.Cleared -> clear()
        }
    }

    /** Back from the review to the form (the payment prepared is dropped; nothing was signed). */
    fun edit() {
        if (mutable.value.step != SendStep.REVIEW || mutable.value.busy) return
        engine.forget()
        mutable.update { it.copy(step = SendStep.FORM, review = null, password = "", error = null, askAgain = false) }
    }

    // --- Approve ---

    fun setPassword(v: String) = mutable.update { it.copy(password = v, error = null, askAgain = false) }

    /** "Approve with password": the password is typed once and wiped from the state the moment it goes. */
    fun approve() {
        val s = mutable.value
        val review = s.review
        if (s.step != SendStep.REVIEW || review == null || review.capRefusal != null) return
        if (s.password.isEmpty() || s.busy) return
        val pw = s.password
        mutable.update { it.copy(busy = true, password = "", error = null, askAgain = false) }
        viewModelScope.launch {
            try {
                handle(engine.approve(pw))
            } catch (e: VaultFailure) {
                // A wrong password and the like: the payment stays prepared and can be approved again with a new password.
                mutable.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    /** "Try again": the same bytes to the chain server as it is now, else the same signing request to the vault. */
    fun retry() {
        val s = mutable.value
        if (s.step != SendStep.DONE || s.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                handle(engine.retry(walletId))
            } catch (e: VaultFailure) {
                mutable.update { it.copy(busy = false, error = e.kind) }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handle(r: SendResult) {
        fun done(o: SendOutcome) = mutable.update { it.copy(busy = false, step = SendStep.DONE, outcome = o, error = null) }
        when (r) {
            is SendResult.Sent -> done(SendOutcome.Sent(timeNow()))
            is SendResult.BroadcastFailed -> {
                val reason = BroadcastReason.of(r.cause)
                done(SendOutcome.BroadcastFailed(hostNow(), reason))
                if (reason == BroadcastReason.NO_ANSWER) checkSent()
            }
            is SendResult.CapExceeded -> done(
                SendOutcome.CapExceeded(withLimit(CapText(r.reason, r.limitSats, r.remainingSats, r.allowedAfter))),
            )
            is SendResult.Refused -> done(SendOutcome.InvalidPsbt(r.reason, r.alarm))
            SendResult.CredentialLocked -> mutable.update { it.copy(busy = false, askAgain = true) }
            SendResult.DuplicateWallet -> done(SendOutcome.DuplicateWallet)
            SendResult.Unanswered -> done(SendOutcome.Unanswered)
            SendResult.SignLapsed -> done(SendOutcome.Lapsed)
            SendResult.SignedDiffers -> done(SendOutcome.SignedDiffers)
            SendResult.AlreadySigned -> done(SendOutcome.AlreadySigned)
            SendResult.Superseded -> done(SendOutcome.Superseded)
            SendResult.NotOnServer -> mutable.update { it.copy(busy = false) }
            SendResult.NothingToSend -> startOver()
            is SendResult.Held -> mutable.update { it.copy(busy = false, block = gateBlockOf(r.hold)) }
            SendResult.Cleared -> clear()
        }
    }

    private fun gateBlockOf(hold: SendHold) = when (hold) {
        SendHold.CAPS_PENDING -> SendBlock.CAPS_PENDING
        SendHold.CAPS_DUPLICATE -> SendBlock.CAPS_DUPLICATE
        SendHold.SETTINGS_UNREADABLE -> SendBlock.SETTINGS_UNREADABLE
        SendHold.SIGN_UNSETTLED -> SendBlock.SIGN_UNSETTLED
    }

    /**
     * Review note 2: after a broadcast that got no answer the server may have taken the transaction: `GET /tx/<txid>` before the
     * member may discard it or send again. Known there is Sent.
     */
    private suspend fun checkSent() {
        updateFailed { it.copy(checking = true) }
        when (val c = engine.checkSent(walletId)) {
            is SendResult.Sent -> mutable.update { it.copy(step = SendStep.DONE, outcome = SendOutcome.Sent(timeNow())) }
            SendResult.NotOnServer -> updateFailed { it.copy(checking = false, checked = true) }
            else -> {
                updateFailed { it.copy(checking = false) }
                if (c == SendResult.Cleared) clear()
            }
        }
    }

    private fun updateFailed(change: (SendOutcome.BroadcastFailed) -> SendOutcome.BroadcastFailed) = mutable.update { s ->
        (s.outcome as? SendOutcome.BroadcastFailed)?.let { s.copy(outcome = change(it)) } ?: s
    }

    private fun timeNow(): ConfirmTime {
        val s = mutable.value
        return when (s.feeChoice) {
            FeeChoice.SLOW -> ConfirmTime.DAY
            FeeChoice.NORMAL -> ConfirmTime.HOUR
            FeeChoice.FAST -> ConfirmTime.MINUTES_20
            FeeChoice.CUSTOM -> SendFees.timeFor(s.review?.feeRate ?: SendFees.customRate(s.customFee) ?: 1.0, s.estimates)
        }
    }

    private suspend fun hostNow(): String? {
        val network = mutable.value.network ?: return null
        return when (val s = settings.server(network)) {
            is ChainServer.Default -> s.url.host
            is ChainServer.Own -> s.url.host
            else -> null
        }
    }

    // --- Giving up, and starting again ---

    /** "Discard": asked first (the signed payment is dropped from this phone; the vault may have counted it already). */
    fun askDiscard() {
        if (mutable.value.step == SendStep.DONE) mutable.update { it.copy(discardAsk = true) }
    }

    fun cancelDiscard() = mutable.update { it.copy(discardAsk = false) }

    fun discard() {
        if (!mutable.value.discardAsk) return
        engine.discard(walletId)
        startOver()
    }

    /** Back to the form, keeping what was typed. Nothing of the review stays. */
    fun startOver() {
        engine.forget()
        mutable.update {
            it.copy(
                step = SendStep.FORM, review = null, outcome = null, password = "", error = null, askAgain = false, busy = false,
                discardAsk = false, problem = null,
            )
        }
    }

    private companion object {
        /** The holds that Receive has too: they lift when the phrase is checked, the limit is set or the network is chosen. */
        val GATES = setOf(
            SendBlock.NOT_BACKED_UP, SendBlock.SETTINGS_UNREADABLE, SendBlock.CAPS_PENDING, SendBlock.CAPS_DUPLICATE,
            SendBlock.NEEDS_NETWORK,
        )
    }
}
